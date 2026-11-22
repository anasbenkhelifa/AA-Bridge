package dev.caraalink.aa

import android.os.Process

/**
 * The sending half of an established session, as the service channels see it.
 * [AaTlsChannel] is the real one; tests use a fake.
 */
interface AaLink {
    /** ENCRYPTED message on the CONTROL channel. */
    fun sendControlEncrypted(messageId: Int, body: ByteArray)

    /** ENCRYPTED `MessageType::CONTROL` message on a service's own channel (channel open replies). */
    fun sendChannelControl(channelId: Int, messageId: Int, body: ByteArray)

    /** ENCRYPTED `MessageType::SPECIFIC` message on a service's own channel (everything else). */
    fun sendChannelSpecific(channelId: Int, messageId: Int, body: ByteArray)
}

/**
 * One Android Auto service channel's share of a session. An instance lives as
 * long as its [AaSessionProbe]; a session is one connection to the phone, and
 * a probe that reconnects runs several. [onMessage] and [onIdle] run on the
 * probe's read thread.
 */
internal abstract class AaChannel(val channelId: Int) {

    /** A message for this channel. CHANNEL_OPEN_REQUEST never gets here; the probe accepts those itself. */
    abstract fun onMessage(link: AaLink, msgId: Int?, body: ByteArray)

    /** Runs between messages, for channels that send without being asked. */
    open fun onIdle(link: AaLink) {}

    /** TLS is up and [link] can carry messages. */
    open fun onSessionStart(link: AaLink) {}

    /** The connection is gone; a later session starts from scratch. */
    open fun onSessionEnd() {}

    /** The probe has stopped for good. */
    open fun release() {}
}

/** Replies shared by the media sink and source channels. */
internal object AaMedia {

    /** `Config{status=READY(2), max_unacked, configuration_indices=[0]}`, the answer to a MEDIA_SETUP. */
    fun setupResponse(maxUnacked: Int): ByteArray =
        AaProtobuf.Writer().int(1, 2).int(2, maxUnacked).int(3, 0).build()

    /**
     * `AVMediaAckIndication{session, ack}` (0x8004). The phone counts unacked
     * frames against the `max_unacked` it was granted and stops sending once
     * it's hit, with no timeout recovery, so every frame is acked on receipt.
     * Field 2 is how many frames this ack covers, not a running total; AA
     * drops the session above 4096.
     */
    fun ack(session: Int): ByteArray = AaProtobuf.Writer().int(1, session).int(2, 1).build()

    /** The `session` of an `AVChannelStartIndication{session=1, config=2}`, echoed back in every ack. */
    fun startSession(body: ByteArray): Int =
        AaProtobuf.parse(body).firstOrNull { it.number == 1 }?.varint?.toInt() ?: 0

    /** The 8-byte big-endian microsecond timestamp that prefixes every MEDIA_DATA body. */
    fun timestampUs(body: ByteArray): Long {
        var ts = 0L
        for (i in 0 until 8) ts = (ts shl 8) or (body[i].toLong() and 0xFF)
        return ts
    }

    const val TIMESTAMP_BYTES = 8
}

/** Daemon thread at display priority, for the video and touch path. */
internal fun urgentThread(r: Runnable, name: String) = Thread({
    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
    r.run()
}, name).apply { isDaemon = true }
