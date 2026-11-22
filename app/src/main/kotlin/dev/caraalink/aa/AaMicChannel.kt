package dev.caraalink.aa

import android.content.Context

/** The microphone source: the phone asks for it, and PCM goes back while it's open. */
internal class AaMicChannel(
    private val context: Context,
    private val log: (String) -> Unit,
) : AaChannel(AaServiceDiscovery.CHANNEL_MEDIA_SOURCE_MICROPHONE) {

    @Volatile
    private var capture: AaMicCapture? = null

    override fun onMessage(link: AaLink, msgId: Int?, body: ByteArray) {
        when (msgId) {
            AaFraming.MSG_MEDIA_SETUP ->
                // The microphone source quirkily reuses MEDIA_MESSAGE_SETUP (32768)
                // as its own response id (aasdk's
                // MediaSourceService::sendChannelSetupResponse()).
                link.sendChannelSpecific(channelId, AaFraming.MSG_MEDIA_SETUP, AaMedia.setupResponse(MAX_UNACKED))
            AaFraming.MSG_MEDIA_MICROPHONE_REQUEST -> {
                // MicrophoneRequest{open=1 bool}. Reply MicrophoneResponse{status=1,
                // session_id=2} on the SAME id (aasdk quirk, see AaFraming), then
                // start/stop capture -- openauto's onMediaSourceOpenRequest()/
                // onMediaSourceOpenSuccess() flow.
                val open = AaProtobuf.parse(body).firstOrNull { it.number == 1 }?.varint == 1L
                log("RX MICROPHONE_REQUEST open=$open")
                val response = AaProtobuf.Writer().int(1, 0).int(2, 0).build() // status=SUCCESS, session_id=0
                link.sendChannelSpecific(channelId, AaFraming.MSG_MEDIA_MICROPHONE_REQUEST, response)
                capture?.stop()
                capture = if (open) AaMicCapture(context) { pcm -> sendChunk(link, pcm) }.also { it.start() } else null
            }
        }
    }

    private fun sendChunk(link: AaLink, pcm: ByteArray) {
        val payload = ByteArray(AaMedia.TIMESTAMP_BYTES + pcm.size)
        var ts = System.nanoTime() / 1000
        for (i in 7 downTo 0) { payload[i] = (ts and 0xFF).toByte(); ts = ts shr 8 }
        pcm.copyInto(payload, AaMedia.TIMESTAMP_BYTES)
        runCatching { link.sendChannelSpecific(channelId, AaFraming.MSG_MEDIA_DATA, payload) }
    }

    override fun onSessionEnd() {
        capture?.stop()
        capture = null
    }

    companion object {
        const val MAX_UNACKED = 1
    }
}
