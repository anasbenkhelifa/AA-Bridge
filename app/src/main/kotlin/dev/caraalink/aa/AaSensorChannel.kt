package dev.caraalink.aa

import dev.caraalink.link.Motion

/** The sensor source: what the car tells Android Auto about itself. */
internal class AaSensorChannel(
    /** Day or night right now; null when unknown, and then nothing is sent. */
    private val night: () -> Boolean?,
    /** Stopped, moving or unknown; null when driving status reporting is switched off. */
    private val motion: () -> Motion?,
    private val log: (String) -> Unit,
    /** Milliseconds on a clock that never steps backwards, so a time correction can't hold a status change back. */
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : AaChannel(AaServiceDiscovery.CHANNEL_SENSOR) {

    private var nightStarted = false
    private var nightSent: Boolean? = null
    private var nightCheckedAt = 0L

    private var drivingStarted = false
    private var drivingSent: Int? = null
    private var drivingCheckedAt = 0L

    override fun onSessionStart(link: AaLink) {
        nightStarted = false
        nightSent = null
        drivingStarted = false
        drivingSent = null
    }

    override fun onMessage(link: AaLink, msgId: Int?, body: ByteArray) {
        if (msgId != AaFraming.MSG_SENSOR_REQUEST) return
        // SensorRequest{type=1, min_update_period=2}. Reply
        // SensorStartResponseMessage{status=0} on SENSOR_MESSAGE_RESPONSE (32770).
        val sensorType = AaProtobuf.parse(body).firstOrNull { it.number == 1 }?.varint?.toInt()
        log("RX SENSOR_MESSAGE_REQUEST on SENSOR type=$sensorType")
        link.sendChannelSpecific(channelId, AaFraming.MSG_SENSOR_RESPONSE, AaProtobuf.Writer().int(1, 0).build())
        when (sensorType) {
            AaServiceDiscovery.SENSOR_NIGHT_MODE -> {
                nightStarted = true
                sendNightIfChanged(link)
            }
            AaServiceDiscovery.SENSOR_DRIVING_STATUS_DATA -> {
                drivingStarted = true
                sendDrivingIfChanged(link)
            }
        }
    }

    override fun onIdle(link: AaLink) {
        if (nightStarted && now() - nightCheckedAt > NIGHT_CHECK_MS) sendNightIfChanged(link)
        if (drivingStarted && now() - drivingCheckedAt > DRIVING_CHECK_MS) sendDrivingIfChanged(link)
    }

    private fun sendNightIfChanged(link: AaLink) {
        nightCheckedAt = now()
        val isNight = night() ?: return
        if (isNight == nightSent) return
        nightSent = isNight
        log("TX SENSOR_EVENT night=$isNight")
        // SensorBatch.night_mode = 10 { is_night = 1 }
        val batch = AaProtobuf.Writer().message(10, AaProtobuf.Writer().bool(1, isNight).build()).build()
        runCatching { link.sendChannelSpecific(channelId, AaFraming.MSG_SENSOR_EVENT, batch) }
            .onFailure { log("SENSOR_EVENT send failed: $it") }
    }

    private fun sendDrivingIfChanged(link: AaLink) {
        drivingCheckedAt = now()
        val status = drivingStatus(motion(), drivingSent) ?: return
        if (status == drivingSent) return
        drivingSent = status
        log("TX SENSOR_EVENT driving_status=$status")
        // SensorBatch.driving_status = 13 { status = 1 } -- the same message openauto's
        // SensorService::sendDrivingStatusUnrestricted() sends, with a real value in it.
        val batch = AaProtobuf.Writer().message(13, AaProtobuf.Writer().int(1, status).build()).build()
        runCatching { link.sendChannelSpecific(channelId, AaFraming.MSG_SENSOR_EVENT, batch) }
            .onFailure { log("SENSOR_EVENT send failed: $it") }
    }

    companion object {
        const val NIGHT_CHECK_MS = 5_000L
        /** Short, so limits come back within half a second of the car pulling away. */
        const val DRIVING_CHECK_MS = 500L

        /** `DrivingStatus` (sensorsource/message/DrivingStatus.proto). */
        const val DRIVE_STATUS_UNRESTRICTED = 0
        const val DRIVE_STATUS_NO_VIDEO = 1
        const val DRIVE_STATUS_NO_KEYBOARD_INPUT = 2
        const val DRIVE_STATUS_NO_VOICE_INPUT = 4
        const val DRIVE_STATUS_NO_CONFIG = 8
        const val DRIVE_STATUS_LIMIT_MESSAGE_LEN = 16

        /** Everything a moving car restricts except voice, which is the one input that is safe while driving. */
        const val DRIVE_STATUS_MOVING =
            DRIVE_STATUS_NO_VIDEO or DRIVE_STATUS_NO_KEYBOARD_INPUT or DRIVE_STATUS_NO_CONFIG or DRIVE_STATUS_LIMIT_MESSAGE_LEN

        /**
         * The status to report, or null to say nothing. Only a car known to be
         * stopped is unrestricted. With no answer (no GPS, or reporting off)
         * nothing is said and AA keeps its own default -- unless "parked" was
         * the last thing reported, which must not be left standing.
         */
        fun drivingStatus(motion: Motion?, lastSent: Int?): Int? = when {
            motion == Motion.PARKED -> DRIVE_STATUS_UNRESTRICTED
            motion == Motion.MOVING -> DRIVE_STATUS_MOVING
            lastSent == null -> null
            else -> DRIVE_STATUS_MOVING
        }
    }
}
