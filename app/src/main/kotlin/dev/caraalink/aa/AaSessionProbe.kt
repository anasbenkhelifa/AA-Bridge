package dev.caraalink.aa

import android.content.Context
import android.util.Log
import dev.caraalink.diagnostics.DiagnosticLogger
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Android Auto receiver: connects to the phone's head unit server, runs
 * the version exchange and TLS handshake ([AaHandshake]), answers the control
 * channel, and hands every other message to its service channel
 * ([AaVideoChannel], [AaAudioChannel], [AaMicChannel], [AaSensorChannel],
 * [AaInputChannel]).
 *
 * The version exchange is confirmed on hardware (S25 Ultra, AA 18.0.664244):
 *
 * ```
 * TX 00 03 00 06 00 01 00 01 00 06
 * RX 00 02 00 01 00 07 00 00   -> VERSION_RESPONSE 1.7, status 0
 * ```
 */
class AaSessionProbe(
    private val context: Context,
    private val config: Config = Config(),
) {

    data class Config(
        val host: String = "127.0.0.1",
        val port: Int = 5277,
        val connectTimeoutMs: Int = 5_000,
        val readTimeoutMs: Int = 15_000,
        // Discovery + audio focus + one CHANNEL_OPEN_REQUEST per registered
        // service already uses ~10 messages before any media setup happens;
        // the old default of 10 made the probe hang up on itself (closing the
        // socket in its own `finally`) right as the real session began --
        // gearhead's reader thread logged "end of stream received" at that
        // exact point, which looked like a protocol rejection but wasn't one.
        val maxMessages: Int = 2_000,
        /** Overrides the version we advertise; null uses AaFraming.PROTOCOL_MAJOR/MINOR. */
        val versionMajor: Int? = null,
        val versionMinor: Int? = null,
        // The server can refuse connections for ~20s after Start, so retry well past that.
        val maxAttempts: Int = 8,
        val retryDelayMs: Long = 2_000,
        /** True only for the on-phone Car Preview session -- see AaServiceDiscovery.configFromSettings(). */
        val preview: Boolean = false,
    )

    companion object {
        private const val TAG = "AaSessionProbe"
        private const val SHUTDOWN_ACK_TIMEOUT_MS = 1_000L
        private const val PING_INTERVAL_MS = 5_000L

        @Volatile
        var active: AaSessionProbe? = null
            private set
    }

    private val logger = DiagnosticLogger(context, "aa-session")
    private val running = AtomicBoolean(false)
    private val handshake = AaHandshake(logger) { running.get() }
    /** Called each time the phone reaches service discovery, i.e. a session is up. */
    @Volatile
    var onEstablished: (() -> Unit)? = null
    /** Called when the initial retries ran out without a session (not after stop()). */
    @Volatile
    var onGaveUp: (() -> Unit)? = null
    private var socket: Socket? = null
    private var worker: Thread? = null

    private val video = AaVideoChannel(logger::log)
    private val audio = AaAudioChannel(context, config.preview, logger::log)
    private val mic = AaMicChannel(context, logger::log)
    private val sensors = AaSensorChannel(
        night = { NightMode.current(context) },
        motion = { if (dev.caraalink.settings.AppSettings.parkedDetection(context)) dev.caraalink.link.CarMotion.motion() else null },
        log = logger::log,
    )
    private val input = AaInputChannel(logger::log)
    private val channels: Map<Int, AaChannel> = listOf(video, audio, mic, sensors, input).associateBy { it.channelId }

    /** Where the phone's video frames go; see [AaVideoChannel.sink]. Set before [start]. */
    var videoSink: ((timestampUs: Long, nal: ByteArray) -> Unit)?
        get() = video.sink
        set(value) { video.sink = value }

    /** Wall-clock time of the last video DATA frame actually received -- the watchdog's stall signal. */
    val lastVideoFrameAt: Long get() = video.lastFrameAt

    /** True only while a session is up, not during reconnect waits. */
    @Volatile
    var isConnectionActive: Boolean = false
        private set

    /** Set on the first real video frame; separates "still starting" from "stalled". */
    val hasReceivedVideo: Boolean get() = video.hasReceivedVideo

    /** Like setting [videoSink] directly, but also replays cached SPS/PPS + last keyframe if we have them. */
    fun attachVideoSink(sink: (timestampUs: Long, nal: ByteArray) -> Unit) = video.attachSink(sink)

    /** Whether Android Auto's picture is on the car screen; see [AaVideoChannel.setFocus]. */
    fun setVideoFocus(onScreen: Boolean) = video.setFocus(onScreen)

    /** One touch event to the phone; see [AaInputChannel.sendTouch]. No-op while no session is up. */
    fun sendTouch(pointers: List<Triple<Int, Int, Int>>, actionIndex: Int, action: Int) =
        input.sendTouch(pointers, actionIndex, action)

    /** One press of an Android key code to the phone; see [AaInputChannel.sendKey]. No-op while no session is up. */
    fun sendKey(keycode: Int) = input.sendKey(keycode)

    /** Single-finger convenience overload -- same as `sendTouch(listOf(Triple(x,y,pointerId)), 0, action)`. */
    fun sendTouch(x: Int, y: Int, action: Int, pointerId: Int = 0) {
        sendTouch(listOf(Triple(x, y, pointerId)), 0, action)
    }

    @Volatile
    private var activeChannel: AaTlsChannel? = null

    // Set by stop() before `running` clears, so the read loop keeps going until the phone answers the ShutdownRequest.
    @Volatile
    private var draining = false

    private val shutdownAcked = java.util.concurrent.CountDownLatch(1)

    fun start() {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "start: already running")
            return
        }
        logger.log("=== AA session probe ${config.host}:${config.port}")
        Log.i(TAG, "start")
        // CarSession's watchdog starts polling within milliseconds of start()
        // returning, but the stall clock otherwise stays at 0 until deep into
        // attemptOnce() (after the TLS handshake). Its first tick then reads
        // `now - 0` -- always "stale" -- and force-restarts a session that was
        // simply still negotiating. Confirmed from a real log. Seeding it here
        // closes that gap; the later resets (post-handshake, per real frame)
        // still apply.
        video.startGrace()
        worker = urgentThread({ run() }, "AaSessionProbe")
        active = this
        worker!!.start()
    }

    fun stop() {
        val ch = activeChannel
        draining = ch != null
        if (!running.compareAndSet(true, false)) {
            draining = false
            return
        }
        logger.log("=== session probe stopped")
        if (active === this) active = null
        // A session end is supposed to be announced with ShutdownRequest BEFORE
        // the transport closes, so the other side can clean up its own session
        // state (mrmees/open-android-auto's
        // docs/interactions/05-session-maintenance-teardown.md, GPL-3.0, facts
        // only). Closing the raw socket instead left gearhead's developer head
        // unit server crashing with "Already connected" on a later reconnect.
        // Best-effort: this must not block or throw if the channel's already
        // unusable. stop() is usually called on the main thread, where a socket
        // write throws NetworkOnMainThreadException, so tear down on a worker.
        Thread({
            if (ch != null) {
                val sent = runCatching {
                    val reason = AaProtobuf.Writer().int(1, 1).build() // ShutdownReason.USER_SELECTION
                    ch.sendControlEncrypted(AaFraming.MSG_SHUTDOWN_REQUEST, reason)
                }.onFailure { logger.log("graceful ShutdownRequest send failed (continuing teardown): $it") }.isSuccess
                if (sent && !shutdownAcked.await(SHUTDOWN_ACK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    logger.log("no SHUTDOWN_RESPONSE within ${SHUTDOWN_ACK_TIMEOUT_MS}ms -- closing anyway")
                }
                draining = false
            }
            runCatching { socket?.close() }
            socket = null
            worker?.interrupt()
            worker = null
            // Each reconnect builds a brand-new AaSessionProbe (openAndroidAuto()),
            // each with its own executors -- without this they'd leak one idle
            // daemon thread pool per reconnect.
            channels.values.forEach { it.release() }
        }, "AaSessionProbe-stop").apply { isDaemon = true }.start()
    }

    /**
     * Set once a real session establishes (service discovery accepted). After
     * that point `run()` keeps reconnecting indefinitely rather than giving
     * up after [Config.maxAttempts] -- a dropped link (car power cycle, phone
     * screen lock, etc.) should auto-recover, not need a manual retrigger.
     * Bounded retries still apply before the *first* successful session, so a
     * genuinely absent/dead head-unit-server doesn't get hammered forever.
     */
    @Volatile
    private var everEstablished = false

    private fun run() {
        // The gearhead's head unit server appears to service one connection at
        // a time and goes deaf to the next one after a session dies uncleanly
        // (observed: attempt at 16:54:18 answered instantly, the one at
        // 16:55:17 got no reply at all). Retry on a fresh socket rather than
        // reporting a protocol failure that is really a stale-session artefact.
        var attempt = 0
        try {
            while (running.get() && (everEstablished || attempt < config.maxAttempts)) {
                attempt++
                logger.log(
                    if (everEstablished) "--- reconnecting (auto-reconnect, session was live before)"
                    else "--- attempt $attempt/${config.maxAttempts}",
                )
                attemptOnce()
                if (!running.get()) return
                if (everEstablished) attempt = 0
                // stop() interrupts this worker thread -- if that lands while
                // it's sleeping here between retries, Thread.sleep() throws
                // InterruptedException, which once crashed the whole app. A
                // stop mid-sleep is just stop() doing its job.
                try {
                    Thread.sleep(config.retryDelayMs)
                } catch (e: InterruptedException) {
                    logger.log("sleep interrupted (stop() requested) -- ending retry loop")
                    return
                }
            }
            logger.log("gave up after $attempt attempt(s)")
            if (running.get()) runCatching { onGaveUp?.invoke() }
        } finally {
            running.set(false)
            runCatching { socket?.close() }
            socket = null
            if (active === this) active = null
        }
    }

    /** @return true if a full session ran (handshake + observation) rather than failing. */
    private fun attemptOnce(): Boolean {
        return try {
            val identity = AaIdentity.load(context)
            val s = Socket().apply {
                // Nagle held our small acks and touch writes about 30 ms each.
                tcpNoDelay = true
                connect(InetSocketAddress(config.host, config.port), config.connectTimeoutMs)
                soTimeout = config.readTimeoutMs
            }
            socket = s
            val input = DataInputStream(s.getInputStream())
            val output = s.getOutputStream()
            logger.log("tcp connected ${s.localSocketAddress} -> ${s.remoteSocketAddress}")

            // --- version exchange ---
            val request = AaFraming.versionRequest(
                major = config.versionMajor ?: AaFraming.PROTOCOL_MAJOR,
                minor = config.versionMinor ?: AaFraming.PROTOCOL_MINOR,
            )
            logger.logBytes("TX", request, request.size)
            output.write(request); output.flush()

            val response = handshake.readFrame(input)
            handshake.logFrame(1, response)
            val versionId = AaFraming.controlMessageId(response.payload)
            if (versionId != AaFraming.MSG_VERSION_RESPONSE) {
                logger.log("unexpected first reply ${AaFraming.controlMessageName(versionId ?: -1)}")
                return false
            }
            val major = AaFraming.u16(response.payload, 2)
            val minor = AaFraming.u16(response.payload, 4)
            val status = if (response.payload.size >= 8) AaFraming.u16(response.payload, 6) else -1
            logger.log("VERSION_RESPONSE $major.$minor status=$status")

            // --- TLS inside encapsulated-SSL control messages ---
            val engine = identity.sslContextLeafOnly.createSSLEngine(config.host, config.port).apply {
                useClientMode = true
                // Reference app pins TLSv1.2 only (`enabled=[TLSv1.2]`); offering
                // 1.3 as well made our ClientHello 233B vs their 138B. Match it.
                enabledProtocols = arrayOf("TLSv1.2")
                beginHandshake()
            }
            logger.log("TLS engine: client mode, ${engine.session.protocol} max=${engine.session.packetBufferSize}")
            handshake.run(engine, input, output)
            if (!running.get()) return true

            val session = engine.session
            logger.log("TLS ESTABLISHED ${session.protocol} ${session.cipherSuite}")
            Log.i(TAG, "TLS established ${session.protocol} ${session.cipherSuite}")
            handshake.logPeer(engine)

            // --- control channel is live, wait for the phone ---
            //
            // The *phone* sends SERVICE_DISCOVERY_REQUEST and the head unit
            // answers with SERVICE_DISCOVERY_RESPONSE (ENCRYPTED), so there is
            // nothing to send unprompted here beyond AUTH_COMPLETE.
            val channel = AaTlsChannel(engine, input, output, logger)
            activeChannel = channel
            channels.values.forEach { it.onSessionStart(channel) }

            // AUTH_COMPLETE (PLAIN) immediately after the handshake.
            // openauto's AndroidAutoEntity::onHandshake() does exactly this:
            //   if (cryptor_->doHandshake()) { AuthResponse{status=STATUS_SUCCESS};
            //     controlServiceChannel_->sendAuthComplete(...); }
            val authResponse = AaProtobuf.Writer()
                .int(1, 0) // AuthResponse.status = MessageStatus::STATUS_SUCCESS
                .build()
            logger.log("TX AUTH_COMPLETE status=0")
            channel.sendControlPlain(AaFraming.MSG_AUTH_COMPLETE, authResponse)
            video.startGrace() // grace period before the watchdog can call this stale
            isConnectionActive = true

            // "Either side may initiate a ping" (mrmees/open-android-auto's
            // docs/interactions/05-session-maintenance-teardown.md, GPL-3.0,
            // facts only), so the head unit pings too rather than only replying.
            //
            // maxMessages was sized for the pre-streaming startup phase and was
            // never meant to cap an ACTIVE streaming session: at 30fps video
            // plus audio, 2000 messages pass in well under a minute, and hitting
            // the cap mid-stream was the long-standing ~30s periodic disconnect.
            // Once established there's no cap; it still protects the startup
            // phase from hanging indefinitely on a wedged handshake.
            var index = 0
            var lastPingSentAt = System.currentTimeMillis()
            while ((running.get() || draining) && (everEstablished || index < config.maxMessages)) {
                index++
                if (System.currentTimeMillis() - lastPingSentAt > PING_INTERVAL_MS) {
                    lastPingSentAt = System.currentTimeMillis()
                    val ping = AaProtobuf.Writer().varint(1, lastPingSentAt * 1000).int(2, 0).build()
                    runCatching { channel.sendControlEncrypted(AaFraming.MSG_PING_REQUEST, ping) }
                        .onFailure { logger.log("proactive PING_REQUEST send failed: $it") }
                }
                channels.values.forEach { it.onIdle(channel) }
                val message = channel.receiveMessage()
                if (message.channelId == AaFraming.CHANNEL_CONTROL) {
                    onControlMessage(channel, message)
                } else {
                    onChannelMessage(channel, message)
                }
            }
            true
        } catch (e: Exception) {
            logger.log("failed: ${e.javaClass.simpleName}: ${e.message}")
            Log.w(TAG, "session failed", e)
            false
        } finally {
            isConnectionActive = false
            activeChannel = null
            channels.values.forEach { it.onSessionEnd() }
            runCatching { socket?.close() }
            socket = null
        }
    }

    /** A message on a service's own channel. */
    private fun onChannelMessage(channel: AaTlsChannel, message: AaTlsChannel.Message) {
        val msgId = AaFraming.controlMessageId(message.payload)
        val body = if (message.payload.size >= 2) message.payload.copyOfRange(2, message.payload.size) else ByteArray(0)
        // MEDIA_DATA fires once per video/audio frame -- DiagnosticLogger does a
        // synchronous file write per call, so logging every one of those
        // unconditionally was quietly throttling the decode loop. Only log
        // those when the sniffer toggle is on; everything else
        // (open/setup/focus messages) is rare enough to always log.
        val sniffing = dev.caraalink.settings.AppSettings.verboseLogging(context)
        if (msgId != AaFraming.MSG_MEDIA_DATA || sniffing) {
            val hex = if (sniffing) " " + dev.caraalink.diagnostics.ProtocolSniffer.hexPreview(body) else ""
            logger.log(
                "MESSAGE on ${AaFraming.channelName(message.channelId)} " +
                    "${AaFraming.controlMessageName(msgId ?: -1)} (${body.size}B)$hex",
            )
        }
        if (msgId == AaFraming.MSG_CHANNEL_OPEN_REQUEST) {
            // ChannelOpenRequest{priority=1 sint32, service_id=2 int32}. We
            // accept every channel the phone asks for -- aasdk's per-service
            // handlers (e.g. SensorSourceService::sendChannelOpenResponse())
            // always reply ENCRYPTED, MessageType::CONTROL, msg id 8,
            // ChannelOpenResponse{status=0 (STATUS_SUCCESS)}, on the
            // service's own channel, not CONTROL.
            val fields = AaProtobuf.parse(body)
            val priority = fields.firstOrNull { it.number == 1 }?.varint
            val serviceId = fields.firstOrNull { it.number == 2 }?.varint
            logger.log(
                "RX CHANNEL_OPEN_REQUEST on ${AaFraming.channelName(message.channelId)} " +
                    "priority=$priority service_id=$serviceId",
            )
            val response = AaProtobuf.Writer().int(1, 0).build() // status = STATUS_SUCCESS
            channel.sendChannelControl(message.channelId, AaFraming.MSG_CHANNEL_OPEN_RESPONSE, response)
            return
        }
        val handler = channels[message.channelId]
        if (handler != null) {
            handler.onMessage(channel, msgId, body)
        } else if (msgId == AaFraming.MSG_MEDIA_SETUP) {
            // A channel we registered without a handler of its own still gets its setup answered.
            channel.sendChannelSpecific(message.channelId, AaFraming.MSG_MEDIA_CONFIG, AaMedia.setupResponse(AaAudioChannel.MAX_UNACKED))
        }
    }

    /** A message on the CONTROL channel: discovery, pings, focus and shutdown. */
    private fun onControlMessage(channel: AaTlsChannel, message: AaTlsChannel.Message) {
        val id = AaFraming.controlMessageId(message.payload)
        val body = message.payload.copyOfRange(2, message.payload.size)
        logger.log("MESSAGE ${AaFraming.controlMessageName(id ?: -1)} (${body.size}B, ${message.frames} frame(s))")
        logger.logBytes("RXBODY", body, minOf(body.size, 256))
        when (id) {
            AaFraming.MSG_SERVICE_DISCOVERY_REQUEST -> {
                decodeDiscoveryRequest(body)
                logger.log("phone asked for service discovery -- session established")
                everEstablished = true
                runCatching { onEstablished?.invoke() }
                val discoveryConfig = AaServiceDiscovery.configFromSettings(context, preview = config.preview)
                audio.phoneMediaAudio = discoveryConfig.phoneMediaAudio
                val response = AaServiceDiscovery.build(discoveryConfig)
                logger.log("TX SERVICE_DISCOVERY_RESPONSE ${response.size}B (${AaServiceDiscovery.describe(discoveryConfig)})")
                logger.logBytes("TXBODY", response, response.size)
                channel.sendControlEncrypted(AaFraming.MSG_SERVICE_DISCOVERY_RESPONSE, response)
            }
            AaFraming.MSG_PING_REQUEST -> channel.sendControlEncrypted(AaFraming.MSG_PING_RESPONSE, body)
            AaFraming.MSG_SHUTDOWN_REQUEST -> {
                // A dedicated shutdown handshake exists separate from a plain
                // connection drop, with a reason field distinguishing "user
                // tapped Exit" from other causes. Unhandled, every exit --
                // including a deliberate one via AA's own exit control --
                // looked identical to a dropped link to run()'s auto-reconnect
                // loop, which just reconnected right back. Acknowledge, then
                // stop for real instead of reconnecting.
                val reason = AaProtobuf.parse(body).firstOrNull { it.number == 1 }?.varint
                logger.log("RX SHUTDOWN_REQUEST reason=$reason -- acking and ending session (no auto-reconnect)")
                channel.sendControlEncrypted(AaFraming.MSG_SHUTDOWN_RESPONSE, ByteArray(0))
                running.set(false)
            }
            AaFraming.MSG_SHUTDOWN_RESPONSE -> {
                logger.log("RX SHUTDOWN_RESPONSE -- phone closed the session cleanly")
                draining = false
                shutdownAcked.countDown()
            }
            AaFraming.MSG_AUDIO_FOCUS_REQUEST -> {
                // AudioFocusRequest.audio_focus_type = 1 (AUDIO_FOCUS_RELEASE = 4).
                // openauto's AndroidAutoEntity::onAudioFocusRequest(): release -> LOSS(3),
                // anything else -> GAIN(1). AudioFocusNotification.focus_state = 1.
                val requestType = AaProtobuf.parse(body).firstOrNull { it.number == 1 }?.varint
                val focusState = if (requestType == 4L) 3 else 1
                logger.log("RX AUDIO_FOCUS_REQUEST type=$requestType -> focus_state=$focusState")
                val notification = AaProtobuf.Writer().int(1, focusState).build()
                channel.sendControlEncrypted(AaFraming.MSG_AUDIO_FOCUS_NOTIFICATION, notification)
            }
            AaFraming.MSG_NAV_FOCUS_REQUEST -> {
                // Unanswered, AA gives nav focus back to the car after 15 s and Maps ends the trip.
                // openauto and libheadunit always grant PROJECTED (2).
                val requestType = AaProtobuf.parse(body).firstOrNull { it.number == 1 }?.varint
                logger.log("RX NAV_FOCUS_REQUEST type=$requestType -> granting projected")
                channel.sendControlEncrypted(AaFraming.MSG_NAV_FOCUS_NOTIFICATION, AaProtobuf.Writer().int(1, 2).build())
            }
        }
    }

    /** ServiceDiscoveryRequest.proto: label_text=4, device_name=5, phone_info=6. */
    private fun decodeDiscoveryRequest(body: ByteArray) {
        val fields = runCatching { AaProtobuf.parse(body) }.getOrElse {
            logger.log("  could not parse discovery request: ${it.message}")
            return
        }
        for (f in fields) {
            when (f.number) {
                4 -> logger.log("  label_text='${AaProtobuf.utf8(f)}'")
                5 -> logger.log("  device_name='${AaProtobuf.utf8(f)}'")
                6 -> logger.log("  phone_info (${f.bytes?.size ?: 0}B)")
                1, 2, 3 -> logger.log("  icon field ${f.number} (${f.bytes?.size ?: 0}B)")
            }
        }
    }
}
