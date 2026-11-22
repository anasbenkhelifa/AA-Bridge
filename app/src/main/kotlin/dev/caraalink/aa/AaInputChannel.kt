package dev.caraalink.aa

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The input source: the car's touchscreen, reported to the phone. */
internal class AaInputChannel(
    private val log: (String) -> Unit,
    // sendTouch() is called from whatever thread owns the UI -- a raw socket
    // write there would throw NetworkOnMainThreadException. One background
    // thread serializes all sends; the link's own sends are synchronized, so
    // this doesn't race the read thread.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> urgentThread(r, "AaSessionProbe-touch") },
) : AaChannel(AaServiceDiscovery.CHANNEL_INPUT_SOURCE) {

    @Volatile
    private var link: AaLink? = null

    override fun onSessionStart(link: AaLink) {
        this.link = link
    }

    override fun onSessionEnd() {
        link = null
    }

    override fun onMessage(link: AaLink, msgId: Int?, body: ByteArray) {
        if (msgId != AaFraming.MSG_INPUT_BINDING_REQUEST) return
        // Key binding for steering-wheel buttons; openauto answers BindingResponse{status=OK(0)}.
        log("RX INPUT_BINDING_REQUEST -> OK")
        link.sendChannelSpecific(channelId, AaFraming.MSG_INPUT_BINDING_RESPONSE, AaProtobuf.Writer().int(1, 0).build())
    }

    /**
     * Sends one touch event: `InputReport{timestamp=1, touch_event=3
     * {touch_location=1 (repeated) [{x,y,pointer_id}], action_index=2,
     * action=3}}`, `INPUT_MESSAGE_INPUT_REPORT` (32769), ENCRYPTED/SPECIFIC --
     * cited from aasdk's `InputSourceService::sendInputReport()`, field
     * numbers cross-checked against mrmees/open-android-auto's
     * `docs/protocol-reference.md` (GPL-3.0, paraphrased facts only). No-op
     * while no session is up.
     *
     * [pointers] lists every currently-down finger (x, y, pointerId).
     * [actionIndex] is which entry in [pointers] triggered this event;
     * [action] is a `PointerAction`: DOWN=0, UP=1, MOVED=2.
     */
    fun sendTouch(pointers: List<Triple<Int, Int, Int>>, actionIndex: Int, action: Int) {
        val link = link ?: return
        if (pointers.isEmpty()) return
        executor.execute {
            val report = touchReport(System.nanoTime() / 1000, pointers, actionIndex, action)
            runCatching { link.sendChannelSpecific(channelId, AaFraming.MSG_INPUT_REPORT, report) }
                .onFailure { log("sendTouch failed: $it") }
        }
    }

    override fun release() {
        executor.shutdownNow()
    }

    companion object {
        fun touchReport(timestampUs: Long, pointers: List<Triple<Int, Int, Int>>, actionIndex: Int, action: Int): ByteArray {
            val touchEvent = AaProtobuf.Writer()
            for ((x, y, pointerId) in pointers) {
                touchEvent.message(1, AaProtobuf.Writer().int(1, x).int(2, y).int(3, pointerId).build())
            }
            touchEvent.int(2, actionIndex).int(3, action)
            return AaProtobuf.Writer().varint(1, timestampUs).message(3, touchEvent.build()).build()
        }
    }
}
