package dev.caraalink.aa

import dev.caraalink.link.Motion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/** The per-channel halves of a session, driven with the messages the phone sends. */
class AaChannelsTest {

    private val link = FakeLink()
    private val logs = ArrayList<String>()
    private val log: (String) -> Unit = { logs += it }

    private fun timestamped(payload: ByteArray, timestampUs: Long = 0x0102030405060708): ByteArray {
        val out = ByteArray(8 + payload.size)
        for (i in 0 until 8) out[i] = (timestampUs shr (8 * (7 - i))).toByte()
        payload.copyInto(out, 8)
        return out
    }

    // --- video ---

    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
    private val pFrame = byteArrayOf(0, 0, 0, 1, 0x41, 9, 9)

    @Test
    fun `video setup grants ten unacked frames and gives the phone video focus`() {
        val video = AaVideoChannel(log, DirectExecutor())

        video.onMessage(link, AaFraming.MSG_MEDIA_SETUP, AaProtobuf.Writer().int(1, 1).build())

        assertEquals(listOf(AaFraming.MSG_MEDIA_CONFIG, AaFraming.MSG_MEDIA_VIDEO_FOCUS_NOTIFICATION), link.ids())
        assertEquals(2L, link.sent[0].body.field(1)) // STATUS_READY
        assertEquals(10L, link.sent[0].body.field(2))
        assertEquals(1L, link.sent[1].body.field(1)) // PROJECTED
    }

    @Test
    fun `a video frame reaches the sink without its timestamp and is acked with the start session`() {
        val video = AaVideoChannel(log, DirectExecutor())
        val got = ArrayList<Pair<Long, ByteArray>>()
        video.sink = { ts, nal -> got += ts to nal }
        video.onMessage(link, AaFraming.MSG_MEDIA_START, AaProtobuf.Writer().int(1, 7).int(2, 0).build())

        video.onMessage(link, AaFraming.MSG_MEDIA_DATA, timestamped(idr))

        assertEquals(1, got.size)
        assertEquals(0x0102030405060708, got[0].first)
        assertArrayEquals(idr, got[0].second)
        assertTrue(video.hasReceivedVideo)
        val ack = link.sent.single()
        assertEquals(AaFraming.MSG_AV_MEDIA_ACK, ack.messageId)
        assertEquals(7L, ack.body.field(1))
        assertEquals(1L, ack.body.field(2))
    }

    @Test
    fun `a sink attached mid-session gets the codec config and last keyframe first`() {
        val video = AaVideoChannel(log, DirectExecutor())
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 1)
        video.onMessage(link, AaFraming.MSG_MEDIA_CODEC_CONFIG, sps)
        video.onMessage(link, AaFraming.MSG_MEDIA_DATA, timestamped(idr))
        video.onMessage(link, AaFraming.MSG_MEDIA_DATA, timestamped(pFrame))

        val got = ArrayList<ByteArray>()
        video.attachSink { _, nal -> got += nal }

        assertEquals(2, got.size)
        assertArrayEquals(sps, got[0])
        assertArrayEquals(idr, got[1])
    }

    @Test
    fun `a new session does not replay the old one's keyframe`() {
        val video = AaVideoChannel(log, DirectExecutor())
        video.onMessage(link, AaFraming.MSG_MEDIA_DATA, timestamped(idr))
        video.onSessionEnd()

        val got = ArrayList<ByteArray>()
        video.attachSink { _, nal -> got += nal }

        assertTrue(got.isEmpty())
    }

    @Test
    fun `an empty video data message is still acked`() {
        val video = AaVideoChannel(log, DirectExecutor())

        video.onMessage(link, AaFraming.MSG_MEDIA_DATA, ByteArray(8))

        assertFalse(video.hasReceivedVideo)
        assertEquals(listOf(AaFraming.MSG_AV_MEDIA_ACK), link.ids())
    }

    @Test
    fun `containsIdr finds a keyframe behind either start code`() {
        assertTrue(AaVideoChannel.containsIdr(byteArrayOf(0, 0, 0, 1, 0x65, 0, 0)))
        assertTrue(AaVideoChannel.containsIdr(byteArrayOf(0, 0, 1, 0x67, 5, 0, 0, 1, 0x65, 0, 0)))
        assertFalse(AaVideoChannel.containsIdr(pFrame))
    }

    @Test
    fun `leaving the car screen takes video focus away, and coming back asks for the picture again`() {
        val video = AaVideoChannel(log, DirectExecutor())
        video.onSessionStart(link)

        video.setFocus(true) // already on screen: nothing to say
        assertTrue(link.sent.isEmpty())

        video.setFocus(false)
        video.setFocus(false)
        video.setFocus(true)

        assertEquals(listOf(AaFraming.MSG_MEDIA_VIDEO_FOCUS_NOTIFICATION, AaFraming.MSG_MEDIA_VIDEO_FOCUS_NOTIFICATION), link.ids())
        assertEquals(listOf(2L, 1L), link.sent.map { it.body.field(1) }) // NATIVE, then PROJECTED
        assertEquals(listOf(1L, 1L), link.sent.map { it.body.field(2) }) // unsolicited
    }

    @Test
    fun `video focus is not sent without a session, and a new session starts on screen`() {
        val video = AaVideoChannel(log, DirectExecutor())
        video.setFocus(false)
        assertTrue(link.sent.isEmpty())

        video.onSessionStart(link)
        video.setFocus(false)
        video.onSessionEnd()
        video.onSessionStart(link)
        link.sent.clear()
        video.setFocus(true)

        assertTrue(link.sent.isEmpty())
    }

    // --- audio ---

    @Test
    fun `audio setup grants sixteen unacked frames`() {
        val audio = AaAudioChannel(mock(android.content.Context::class.java), preview = false, log = log)

        audio.onMessage(link, AaFraming.MSG_MEDIA_SETUP, ByteArray(0))

        assertEquals(AaFraming.MSG_MEDIA_CONFIG, link.sent.single().messageId)
        assertEquals(16L, link.sent.single().body.field(2))
        audio.release()
    }

    @Test
    fun `system sounds are acked and dropped when the phone plays its own media`() {
        val audio = AaAudioChannel(mock(android.content.Context::class.java), preview = false, log = log)
        audio.phoneMediaAudio = true
        audio.onMessage(link, AaFraming.MSG_MEDIA_START, AaProtobuf.Writer().int(1, 3).build())

        audio.onMessage(link, AaFraming.MSG_MEDIA_CODEC_CONFIG, byteArrayOf(0x11, 0x90.toByte()))
        audio.onMessage(link, AaFraming.MSG_MEDIA_DATA, timestamped(ByteArray(32)))

        assertEquals(listOf(AaFraming.MSG_AV_MEDIA_ACK, AaFraming.MSG_AV_MEDIA_ACK), link.ids())
        assertEquals(3L, link.sent[0].body.field(1))
        audio.release()
    }

    // --- microphone ---

    @Test
    fun `microphone setup answers on the setup id with one unacked frame`() {
        val mic = AaMicChannel(mock(android.content.Context::class.java), log)

        mic.onMessage(link, AaFraming.MSG_MEDIA_SETUP, ByteArray(0))

        assertEquals(AaFraming.MSG_MEDIA_SETUP, link.sent.single().messageId)
        assertEquals(1L, link.sent.single().body.field(2))
    }

    @Test
    fun `a microphone close is answered on the request id`() {
        val mic = AaMicChannel(mock(android.content.Context::class.java), log)

        mic.onMessage(link, AaFraming.MSG_MEDIA_MICROPHONE_REQUEST, AaProtobuf.Writer().bool(1, false).build())

        assertEquals(AaFraming.MSG_MEDIA_MICROPHONE_REQUEST, link.sent.single().messageId)
        assertEquals(0L, link.sent.single().body.field(1))
    }

    // --- sensors ---

    private fun sensorRequest(type: Int) = AaProtobuf.Writer().int(1, type).int(2, 0).build()

    private fun nightOf(sent: FakeLink.Sent): Long? =
        AaProtobuf.parse(sent.body).firstOrNull { it.number == 10 }?.bytes?.field(1)

    @Test
    fun `a night mode request is accepted and answered with the current state`() {
        val sensors = AaSensorChannel(night = { true }, motion = { null }, log = log, now = { 0L })
        sensors.onSessionStart(link)

        sensors.onMessage(link, AaFraming.MSG_SENSOR_REQUEST, sensorRequest(AaServiceDiscovery.SENSOR_NIGHT_MODE))

        assertEquals(listOf(AaFraming.MSG_SENSOR_RESPONSE, AaFraming.MSG_SENSOR_EVENT), link.ids())
        assertEquals(0L, link.sent[0].body.field(1))
        assertEquals(1L, nightOf(link.sent[1]))
    }

    @Test
    fun `night mode is sent again only when it changes, checked every five seconds`() {
        var night = false
        var clock = 0L
        val sensors = AaSensorChannel(night = { night }, motion = { null }, log = log, now = { clock })
        sensors.onSessionStart(link)
        sensors.onMessage(link, AaFraming.MSG_SENSOR_REQUEST, sensorRequest(AaServiceDiscovery.SENSOR_NIGHT_MODE))
        link.sent.clear()

        night = true
        clock = 4_000
        sensors.onIdle(link)
        assertTrue(link.sent.isEmpty())

        clock = 5_001
        sensors.onIdle(link)
        assertEquals(1L, nightOf(link.sent.single()))

        clock = 20_000
        sensors.onIdle(link)
        assertEquals(1, link.sent.size)
    }

    @Test
    fun `nothing is sent while day or night is unknown, or before the phone asks`() {
        val sensors = AaSensorChannel(night = { null }, motion = { null }, log = log, now = { 10_000L })
        sensors.onSessionStart(link)
        sensors.onIdle(link)
        assertTrue(link.sent.isEmpty())

        sensors.onMessage(link, AaFraming.MSG_SENSOR_REQUEST, sensorRequest(AaServiceDiscovery.SENSOR_NIGHT_MODE))

        assertEquals(listOf(AaFraming.MSG_SENSOR_RESPONSE), link.ids())
    }

    @Test
    fun `a new session forgets what the last one was told`() {
        val sensors = AaSensorChannel(night = { true }, motion = { null }, log = log, now = { 0L })
        sensors.onSessionStart(link)
        sensors.onMessage(link, AaFraming.MSG_SENSOR_REQUEST, sensorRequest(AaServiceDiscovery.SENSOR_NIGHT_MODE))
        link.sent.clear()

        sensors.onSessionStart(link)
        sensors.onMessage(link, AaFraming.MSG_SENSOR_REQUEST, sensorRequest(AaServiceDiscovery.SENSOR_NIGHT_MODE))

        assertEquals(listOf(AaFraming.MSG_SENSOR_RESPONSE, AaFraming.MSG_SENSOR_EVENT), link.ids())
    }

    private fun drivingOf(sent: FakeLink.Sent): Long? =
        AaProtobuf.parse(sent.body).firstOrNull { it.number == 13 }?.bytes?.field(1)

    @Test
    fun `a stopped car is reported unrestricted, and restricted again once it moves`() {
        var motion: Motion? = Motion.PARKED
        var clock = 0L
        val sensors = AaSensorChannel(night = { null }, motion = { motion }, log = log, now = { clock })
        sensors.onSessionStart(link)

        sensors.onMessage(link, AaFraming.MSG_SENSOR_REQUEST, sensorRequest(AaServiceDiscovery.SENSOR_DRIVING_STATUS_DATA))
        assertEquals(listOf(AaFraming.MSG_SENSOR_RESPONSE, AaFraming.MSG_SENSOR_EVENT), link.ids())
        assertEquals(0L, drivingOf(link.sent[1]))
        link.sent.clear()

        motion = Motion.MOVING
        clock = 501
        sensors.onIdle(link)
        // No video, no keyboard, no setup, short messages; voice stays allowed.
        assertEquals(27L, drivingOf(link.sent.single()))

        clock = 1_100
        sensors.onIdle(link)
        assertEquals(1, link.sent.size)
    }

    @Test
    fun `with no GPS nothing is said about driving, so Android Auto keeps its own default`() {
        val sensors = AaSensorChannel(night = { null }, motion = { Motion.UNKNOWN }, log = log, now = { 0L })
        sensors.onSessionStart(link)

        sensors.onMessage(link, AaFraming.MSG_SENSOR_REQUEST, sensorRequest(AaServiceDiscovery.SENSOR_DRIVING_STATUS_DATA))

        assertEquals(listOf(AaFraming.MSG_SENSOR_RESPONSE), link.ids())
    }

    @Test
    fun `losing GPS, or switching reporting off, never leaves parked standing`() {
        for (after in listOf<Motion?>(Motion.UNKNOWN, null)) {
            link.sent.clear()
            var motion: Motion? = Motion.PARKED
            var clock = 0L
            val sensors = AaSensorChannel(night = { null }, motion = { motion }, log = log, now = { clock })
            sensors.onSessionStart(link)
            sensors.onMessage(link, AaFraming.MSG_SENSOR_REQUEST, sensorRequest(AaServiceDiscovery.SENSOR_DRIVING_STATUS_DATA))

            motion = after
            clock = 600
            sensors.onIdle(link)

            assertEquals(27L, drivingOf(link.sent.last()))
        }
    }

    @Test
    fun `driving status waits for the phone to ask`() {
        val sensors = AaSensorChannel(night = { null }, motion = { Motion.PARKED }, log = log, now = { 10_000L })
        sensors.onSessionStart(link)

        sensors.onIdle(link)

        assertTrue(link.sent.isEmpty())
    }

    // --- input ---

    @Test
    fun `a key binding request is accepted`() {
        val input = AaInputChannel(log, DirectExecutor())

        input.onMessage(link, AaFraming.MSG_INPUT_BINDING_REQUEST, AaProtobuf.Writer().int(1, 4).build())

        assertEquals(AaFraming.MSG_INPUT_BINDING_RESPONSE, link.sent.single().messageId)
        assertEquals(0L, link.sent.single().body.field(1))
    }

    @Test
    fun `touch goes out as an input report with every finger`() {
        val input = AaInputChannel(log, DirectExecutor())
        input.onSessionStart(link)

        input.sendTouch(listOf(Triple(10, 20, 0), Triple(300, 400, 1)), actionIndex = 1, action = 0)

        val report = link.sent.single()
        assertEquals(AaServiceDiscovery.CHANNEL_INPUT_SOURCE, report.channelId)
        assertEquals(AaFraming.MSG_INPUT_REPORT, report.messageId)
        val touch = AaProtobuf.parse(report.body).first { it.number == 3 }.bytes!!
        val fingers = AaProtobuf.parse(touch).filter { it.number == 1 }.map { it.bytes!! }
        assertEquals(2, fingers.size)
        assertEquals(300L, fingers[1].field(1))
        assertEquals(400L, fingers[1].field(2))
        assertEquals(1L, fingers[1].field(3))
        assertEquals(1L, touch.field(2))
        assertEquals(0L, touch.field(3))
    }

    @Test
    fun `a button press goes out as a key down then a key up`() {
        val input = AaInputChannel(log, DirectExecutor())
        input.onSessionStart(link)

        input.sendKey(87)

        assertEquals(listOf(AaFraming.MSG_INPUT_REPORT, AaFraming.MSG_INPUT_REPORT), link.ids())
        val keys = link.sent.map { report ->
            val keyEvent = AaProtobuf.parse(report.body).first { it.number == 4 }.bytes!!
            AaProtobuf.parse(keyEvent).single { it.number == 1 }.bytes!!
        }
        assertEquals(listOf(87L, 87L), keys.map { it.field(1) })
        assertEquals(listOf(1L, 0L), keys.map { it.field(2) })
        assertEquals(listOf(0L, 0L), keys.map { it.field(3) }) // metastate is required
    }

    @Test
    fun `a button press is dropped while no session is up`() {
        val input = AaInputChannel(log, DirectExecutor())

        input.sendKey(87)

        assertTrue(link.sent.isEmpty())
    }

    @Test
    fun `touch is dropped while no session is up`() {
        val input = AaInputChannel(log, DirectExecutor())

        input.sendTouch(listOf(Triple(1, 1, 0)), 0, 0)
        input.onSessionStart(link)
        input.onSessionEnd()
        input.sendTouch(listOf(Triple(1, 1, 0)), 0, 0)

        assertTrue(link.sent.isEmpty())
        assertNull(link.sent.firstOrNull())
    }
}
