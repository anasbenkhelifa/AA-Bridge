package dev.caraalink.aa

/** The sensor source: what the car tells Android Auto about itself. */
internal class AaSensorChannel(
    /** Day or night right now; null when unknown, and then nothing is sent. */
    private val night: () -> Boolean?,
    private val log: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : AaChannel(AaServiceDiscovery.CHANNEL_SENSOR) {

    private var nightStarted = false
    private var nightSent: Boolean? = null
    private var nightCheckedAt = 0L

    override fun onSessionStart(link: AaLink) {
        nightStarted = false
        nightSent = null
    }

    override fun onMessage(link: AaLink, msgId: Int?, body: ByteArray) {
        if (msgId != AaFraming.MSG_SENSOR_REQUEST) return
        // SensorRequest{type=1, min_update_period=2}. Reply
        // SensorStartResponseMessage{status=0} on SENSOR_MESSAGE_RESPONSE (32770).
        val sensorType = AaProtobuf.parse(body).firstOrNull { it.number == 1 }?.varint?.toInt()
        log("RX SENSOR_MESSAGE_REQUEST on SENSOR type=$sensorType")
        link.sendChannelSpecific(channelId, AaFraming.MSG_SENSOR_RESPONSE, AaProtobuf.Writer().int(1, 0).build())
        if (sensorType == AaServiceDiscovery.SENSOR_NIGHT_MODE) {
            nightStarted = true
            sendNightIfChanged(link)
        }
    }

    override fun onIdle(link: AaLink) {
        if (nightStarted && now() - nightCheckedAt > NIGHT_CHECK_MS) sendNightIfChanged(link)
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

    companion object {
        const val NIGHT_CHECK_MS = 5_000L
    }
}
