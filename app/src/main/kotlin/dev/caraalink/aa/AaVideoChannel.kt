package dev.caraalink.aa

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The video sink: H.264 from the phone, handed to whatever is decoding it. */
internal class AaVideoChannel(
    private val log: (String) -> Unit,
    // Decode used to run inline on the probe's single read thread (the same
    // thread doing TLS unwrap and replying to every other control message) --
    // any decode/render stall there delayed ping replies and every other
    // channel too. A dedicated thread keeps the protocol loop free.
    private val decodeExecutor: ExecutorService = Executors.newSingleThreadExecutor { r -> urgentThread(r, "AaSessionProbe-media") },
) : AaChannel(AaServiceDiscovery.CHANNEL_MEDIA_SINK_VIDEO) {

    /**
     * Called for each `MEDIA_MESSAGE_DATA` (id 0) frame, with the raw H.264
     * Annex-B NAL bytes (the 8-byte big-endian timestamp aasdk's
     * `handleMediaWithTimestampIndication()` prefixes each frame with is
     * already stripped). Null means frames are discarded.
     */
    @Volatile
    var sink: ((timestampUs: Long, nal: ByteArray) -> Unit)? = null

    // The phone sends SPS/PPS exactly once per session (MEDIA_MESSAGE_CODEC_CONFIG,
    // right after setup), not on every attach. A fresh MediaCodec decoder needs
    // it to initialize -- without this, closing and reopening AaPreviewActivity
    // while the same session is still running attaches a brand-new decoder that
    // never gets parameter sets and just stays black. Cache it and replay it to
    // every new sink.
    @Volatile
    private var cachedCodecConfig: ByteArray? = null

    // SPS/PPS alone isn't enough to produce a picture: a brand-new decoder
    // still needs an actual IDR (keyframe) frame to decode from, and the
    // stream's own next keyframe can be many seconds away (H.264 GOP
    // interval, not something we control). Caching the last IDR and replaying
    // it right after the codec config gives a fresh attach a self-contained
    // starting point instead of waiting on the stream's own schedule.
    @Volatile
    private var cachedKeyframe: ByteArray? = null

    /** Wall-clock time of the last video DATA frame actually received -- the watchdog's stall signal. */
    @Volatile
    var lastFrameAt: Long = 0L
        private set

    /** Set on the first real video frame; separates "still starting" from "stalled". */
    @Volatile
    var hasReceivedVideo: Boolean = false
        private set

    // Survives a reconnect on purpose: the next MEDIA_START overwrites it.
    @Volatile
    private var ackSession = 0

    /** Like setting [sink] directly, but also replays cached SPS/PPS + last keyframe if we have them. */
    fun attachSink(sink: (timestampUs: Long, nal: ByteArray) -> Unit) {
        this.sink = sink
        cachedCodecConfig?.let { sink(0L, it) }
        cachedKeyframe?.let { sink(0L, it) }
    }

    /** Restarts the stall clock, so a session that is still negotiating isn't called stale. */
    fun startGrace() {
        lastFrameAt = System.currentTimeMillis()
    }

    override fun onMessage(link: AaLink, msgId: Int?, body: ByteArray) {
        when (msgId) {
            AaFraming.MSG_MEDIA_SETUP -> {
                // Video's documented typical max_unacked is 10 (a sliding window,
                // pipelined) -- mrmees/open-android-auto's
                // docs/interactions/04-channel-lifecycle.md, GPL-3.0, facts only.
                // At 1 every frame waited a full ack round-trip before the phone
                // would send the next one.
                link.sendChannelSpecific(channelId, AaFraming.MSG_MEDIA_CONFIG, AaMedia.setupResponse(MAX_UNACKED))
                // VideoFocusNotification{focus=PROJECTED(1), unsolicited=false}:
                // openauto's VideoMediaSinkService::sendVideoFocusIndication(),
                // sent right after the setup response grants the phone focus
                // to start encoding video.
                val focus = AaProtobuf.Writer().int(1, 1).bool(2, false).build()
                link.sendChannelSpecific(channelId, AaFraming.MSG_MEDIA_VIDEO_FOCUS_NOTIFICATION, focus)
            }
            AaFraming.MSG_MEDIA_START -> {
                ackSession = AaMedia.startSession(body)
                log("RX MEDIA_START on ${AaFraming.channelName(channelId)} session=$ackSession")
            }
            AaFraming.MSG_MEDIA_DATA -> {
                if (body.size > AaMedia.TIMESTAMP_BYTES) {
                    val ts = AaMedia.timestampUs(body)
                    val frame = body.copyOfRange(AaMedia.TIMESTAMP_BYTES, body.size)
                    if (containsIdr(frame)) cachedKeyframe = frame
                    lastFrameAt = System.currentTimeMillis()
                    hasReceivedVideo = true
                    decodeExecutor.execute { sink?.invoke(ts, frame) }
                }
                // Ack immediately on receipt rather than waiting for decode, so a
                // slow/dropped decode doesn't also stall the phone's own send side.
                ack(link)
            }
            AaFraming.MSG_MEDIA_CODEC_CONFIG -> {
                // SPS/PPS, no timestamp prefix (unlike MEDIA_MESSAGE_DATA) --
                // feed as-is, timestamp 0, ahead of the IDR frame that follows it.
                log("RX MEDIA_CODEC_CONFIG on MEDIA_SINK_VIDEO (${body.size}B)")
                if (body.isNotEmpty()) {
                    cachedCodecConfig = body
                    decodeExecutor.execute { sink?.invoke(0L, body) }
                }
                ack(link)
            }
        }
    }

    private fun ack(link: AaLink) {
        runCatching { link.sendChannelSpecific(channelId, AaFraming.MSG_AV_MEDIA_ACK, AaMedia.ack(ackSession)) }
            .onFailure { log("AV_MEDIA_ACK send failed on ch$channelId: $it") }
    }

    @Volatile
    private var link: AaLink? = null

    @Volatile
    private var projected = true

    override fun onSessionStart(link: AaLink) {
        this.link = link
        projected = true
    }

    /**
     * Tells the phone whether its picture is on the car screen right now.
     * `VideoFocusNotification{focus = PROJECTED(1) | NATIVE(2), unsolicited =
     * true}` is how a head unit says it is showing something of its own: the
     * phone stops sending video and keeps everything else running, so
     * navigation and its voice carry on, and it starts again with a fresh
     * keyframe when focus comes back. No-op without a session or a change.
     */
    fun setFocus(onScreen: Boolean) {
        val link = link ?: return
        if (projected == onScreen) return
        projected = onScreen
        if (onScreen) startGrace()
        decodeExecutor.execute {
            val focus = AaProtobuf.Writer().int(1, if (onScreen) FOCUS_PROJECTED else FOCUS_NATIVE).bool(2, true).build()
            runCatching { link.sendChannelSpecific(channelId, AaFraming.MSG_MEDIA_VIDEO_FOCUS_NOTIFICATION, focus) }
                .onFailure { log("VIDEO_FOCUS send failed: $it") }
            log("TX VIDEO_FOCUS ${if (onScreen) "projected" else "native"}")
        }
    }

    override fun onSessionEnd() {
        link = null
        cachedCodecConfig = null
        cachedKeyframe = null
    }

    override fun release() {
        decodeExecutor.shutdownNow()
    }

    companion object {
        const val MAX_UNACKED = 10
        const val FOCUS_PROJECTED = 1
        const val FOCUS_NATIVE = 2

        /** True if [nal] (one or more Annex-B NAL units) contains an IDR slice (type 5). */
        fun containsIdr(nal: ByteArray): Boolean {
            var i = 0
            while (i < nal.size - 4) {
                if (nal[i] == 0.toByte() && nal[i + 1] == 0.toByte() &&
                    (nal[i + 2] == 1.toByte() || (nal[i + 2] == 0.toByte() && i + 3 < nal.size && nal[i + 3] == 1.toByte()))
                ) {
                    val headerAt = if (nal[i + 2] == 1.toByte()) i + 3 else i + 4
                    if (headerAt < nal.size && (nal[headerAt].toInt() and 0x1F) == 5) return true
                    i = headerAt
                } else {
                    i++
                }
            }
            return false
        }
    }
}
