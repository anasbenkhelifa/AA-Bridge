package dev.caraalink.aa

import dev.caraalink.diagnostics.DiagnosticLogger
import java.io.DataInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

/**
 * The plaintext part of a session, before an [AaTlsChannel] exists: raw frame
 * reads and the TLS handshake carried *inside* PLAIN control messages.
 *
 * Follows `ControlServiceChannel::sendHandshake()` / `receive()` in aasdk:
 * the head unit is the TLS *client*, and each TLS record is shipped as a
 * PLAIN control message with id `MESSAGE_ENCAPSULATED_SSL` (3). That is why
 * raw TLS on the socket never worked -- the socket only ever carries AA
 * frames. Java has no socket to hand to JSSE here, so this uses [SSLEngine],
 * which does the same job over memory buffers: wrap -> send as an
 * encapsulated-SSL message, receive -> unwrap.
 */
internal class AaHandshake(
    private val logger: DiagnosticLogger,
    private val running: () -> Boolean,
) {

    /**
     * Drives [engine] over AA frames. Each `wrap()` output becomes the body
     * of a PLAIN `MESSAGE_ENCAPSULATED_SSL` message; each such message we
     * receive is fed back into `unwrap()`.
     *
     * Important detail: one AA message can carry several TLS records, and
     * `unwrap` may stop mid-buffer. Leftover bytes are kept in `pending` and
     * fed back in before reading another frame -- discarding them deadlocks
     * the handshake (first hardware run did exactly that: ServerHello
     * arrived, unwrap said NEED_UNWRAP, and we blocked waiting for data we
     * had already been given).
     */
    fun run(engine: SSLEngine, input: DataInputStream, output: OutputStream) {
        val app = ByteBuffer.allocate(engine.session.applicationBufferSize)
        val netOut = ByteBuffer.allocate(engine.session.packetBufferSize)
        val empty = ByteBuffer.allocate(0)
        var pending = ByteArray(0)

        fun send(record: ByteArray) {
            if (record.isEmpty()) return
            val message = AaFraming.controlMessage(AaFraming.MSG_ENCAPSULATED_SSL, record)
            logger.log("TX ENCAPSULATED_SSL (${record.size}B)")
            logger.logBytes("TX", record, minOf(record.size, 64))
            output.write(message)
            output.flush()
        }

        /** Next encapsulated-SSL record body, skipping anything else. */
        fun nextRecord(): ByteArray {
            while (true) {
                val frame = readFrame(input)
                val id = AaFraming.controlMessageId(frame.payload)
                if (id == AaFraming.MSG_ENCAPSULATED_SSL) {
                    val record = frame.payload.copyOfRange(2, frame.payload.size)
                    logger.log("RX ENCAPSULATED_SSL (${record.size}B)")
                    return record
                }
                logger.log("skipping ${AaFraming.controlMessageName(id ?: -1)} during handshake")
                logFrame(-1, frame)
            }
        }

        var rounds = 0
        while (running() && rounds++ < 120) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> engine.delegatedTask?.run()

                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val result = engine.wrap(empty, netOut)
                    netOut.flip()
                    val record = ByteArray(netOut.remaining())
                    netOut.get(record)
                    logger.log("wrap ${result.status} -> ${record.size}B")
                    send(record)
                }

                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    var progressed = false
                    while (true) {
                        if (pending.isEmpty()) {
                            pending = nextRecord()
                        }
                        val buf = ByteBuffer.wrap(pending)
                        app.clear()
                        val result = engine.unwrap(buf, app)
                        val consumed = buf.position()
                        if (consumed > 0) {
                            pending = pending.copyOfRange(consumed, pending.size)
                            progressed = true
                        }
                        logger.log(
                            "unwrap ${result.status}/${result.handshakeStatus} " +
                                "consumed=$consumed produced=${result.bytesProduced()} left=${pending.size}"
                        )
                        if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                            pending += nextRecord()
                            continue
                        }
                        if (result.status == SSLEngineResult.Status.CLOSED) {
                            throw SSLException("engine closed during handshake")
                        }
                        if (engine.handshakeStatus != SSLEngineResult.HandshakeStatus.NEED_UNWRAP) break
                        if (consumed == 0 && pending.isNotEmpty()) break
                    }
                    if (!progressed && pending.isEmpty()) {
                        throw SSLException("handshake stalled with nothing buffered")
                    }
                }

                SSLEngineResult.HandshakeStatus.FINISHED,
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> return

                null -> return
            }
        }
        throw SSLException("handshake did not complete in $rounds rounds")
    }

    fun logPeer(engine: SSLEngine) {
        val session = engine.session
        val peer = runCatching { session.peerCertificates }.getOrNull()
        if (peer.isNullOrEmpty()) {
            logger.log("  peer: no certificate (phone does not authenticate us at TLS layer)")
        } else {
            peer.forEachIndexed { i, cert ->
                (cert as? X509Certificate)?.let {
                    logger.log("  peer[$i] '${it.subjectX500Principal.name}' sha256=${AaIdentity.fingerprint(it)}")
                }
            }
        }
        runCatching { session.localCertificates }.getOrNull()?.forEachIndexed { i, cert ->
            (cert as? X509Certificate)?.let {
                logger.log("  local[$i] '${it.subjectX500Principal.name}'")
            }
        }
    }

    fun readFrame(input: DataInputStream): AaFraming.Frame {
        val header = ByteArray(2)
        input.readFully(header)
        val channelId = header[0].toInt() and 0xFF
        val flags = header[1].toInt() and 0xFF
        val frameType = flags and 0x03
        val messageType = flags and 0x04
        val encryption = flags and 0x08

        val sizeField = if (frameType == AaFraming.FRAME_FIRST) 6 else 2
        val sizeBytes = ByteArray(sizeField)
        input.readFully(sizeBytes)
        val frameSize = AaFraming.u16(sizeBytes, 0)
        val totalSize = if (sizeField == 6) AaFraming.u32(sizeBytes, 2) else frameSize

        val payload = ByteArray(frameSize)
        if (frameSize > 0) input.readFully(payload)
        return AaFraming.Frame(channelId, frameType, messageType, encryption, frameSize, totalSize, payload)
    }

    fun logFrame(index: Int, f: AaFraming.Frame) {
        val enc = if (f.isPlain) "PLAIN" else "ENCRYPTED"
        val mtype = if (f.messageType == AaFraming.MESSAGE_CONTROL) "CONTROL" else "SPECIFIC"
        val named = if (f.channelId == AaFraming.CHANNEL_CONTROL && f.isPlain) {
            AaFraming.controlMessageId(f.payload)?.let { " ${AaFraming.controlMessageName(it)}" } ?: ""
        } else ""
        logger.log(
            "RX #$index ch=${AaFraming.channelName(f.channelId)}(${f.channelId}) " +
                "type=${AaFraming.frameTypeName(f.frameType)} $enc $mtype size=${f.frameSize}$named"
        )
        if (f.payload.isNotEmpty()) logger.logBytes("RX", f.payload, minOf(f.payload.size, 256))
    }
}
