package dev.caraalink.aa

import android.content.Context
import java.util.concurrent.Executors

/** The media audio sink: AAC-LC from the phone, played by [AaAudioPlayer]. */
internal class AaAudioChannel(
    private val context: Context,
    /** True only for the on-phone Car Preview session -- see AaServiceDiscovery.configFromSettings(). */
    private val preview: Boolean,
    private val log: (String) -> Unit,
) : AaChannel(AaServiceDiscovery.CHANNEL_MEDIA_SINK_MEDIA_AUDIO) {

    /** Set from service discovery; when true the channel only carries system sounds, which are acked and dropped. */
    @Volatile
    var phoneMediaAudio = false

    @Volatile
    private var player: AaAudioPlayer? = null

    // Survives a reconnect on purpose: the next MEDIA_START overwrites it.
    @Volatile
    private var ackSession = 0

    // Audio used to share the video decode thread, so a video backlog delayed
    // audio right along with it. A separate thread means one falling behind
    // can't stall the other.
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "AaSessionProbe-audio").apply { isDaemon = true } }

    override fun onMessage(link: AaLink, msgId: Int?, body: ByteArray) {
        when (msgId) {
            AaFraming.MSG_MEDIA_SETUP ->
                // At 1 AA's media queue starved (its log: "out of permits", 25 s queued).
                // open-headunit grants audio 16 over USB.
                link.sendChannelSpecific(channelId, AaFraming.MSG_MEDIA_CONFIG, AaMedia.setupResponse(MAX_UNACKED))
            AaFraming.MSG_MEDIA_START -> {
                ackSession = AaMedia.startSession(body)
                log("RX MEDIA_START on ${AaFraming.channelName(channelId)} session=$ackSession")
            }
            AaFraming.MSG_MEDIA_CODEC_CONFIG, AaFraming.MSG_MEDIA_DATA -> {
                // Symmetric to video: AAC-LC codec config (csd-0) once, then
                // timestamped frames.
                if (phoneMediaAudio) {
                    // Only PCM system sounds arrive here (music plays on the phone). Ack and drop.
                } else if (msgId == AaFraming.MSG_MEDIA_CODEC_CONFIG) {
                    log("RX MEDIA_CODEC_CONFIG on MEDIA_SINK_MEDIA_AUDIO (${body.size}B)")
                    if (body.isNotEmpty()) {
                        // Reuse the existing player (setCodecConfig() no-ops if
                        // the bytes are unchanged) instead of always tearing down
                        // and recreating -- a codec-config message mid-stream
                        // (e.g. a voice prompt's own short-lived config arriving
                        // while music was already playing) was silently killing
                        // whatever was already playing and never resuming it,
                        // since music doesn't resend its own config afterward.
                        val p = player
                            ?: AaAudioPlayer(context, sampleRate = 48_000, channelCount = 2, preview = preview).also { player = it }
                        executor.execute { p.setCodecConfig(body) }
                    }
                } else if (body.size > AaMedia.TIMESTAMP_BYTES) {
                    val frame = body.copyOfRange(AaMedia.TIMESTAMP_BYTES, body.size)
                    executor.execute { player?.feed(frame) }
                }
                // Without this ack the phone sends exactly one buffer then waits
                // forever -- the confirmed root cause of "no music, ever".
                runCatching { link.sendChannelSpecific(channelId, AaFraming.MSG_AV_MEDIA_ACK, AaMedia.ack(ackSession)) }
                    .onFailure { log("AV_MEDIA_ACK send failed on ch$channelId: $it") }
            }
        }
    }

    override fun onSessionEnd() {
        player?.stop()
        player = null
    }

    override fun release() {
        executor.shutdownNow()
    }

    companion object {
        const val MAX_UNACKED = 16
    }
}
