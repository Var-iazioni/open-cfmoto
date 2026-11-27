// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from mcarplay (https://github.com/wangxso/mcarplay, MIT License).
// Copyright (C) wangxso / mcarplay contributors, OpenCfMoto contributors.
package dev.zanderp.opencfmoto.carwith

import android.content.Context
import android.net.ConnectivityManager
import android.view.KeyEvent
import android.view.Surface
import dev.zanderp.opencfmoto.AaVideoBridge
import dev.zanderp.opencfmoto.BikeProfileHolder
import dev.zanderp.opencfmoto.BikeWifi
import dev.zanderp.opencfmoto.aa.AaLog
import dev.zanderp.opencfmoto.aa.VideoDecoder
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Local loopback receiver for Xiaomi CarWith (via Xiaomi CarLife's 7 TCP channels on 127.0.0.1).
 *
 * Mirrors [dev.zanderp.opencfmoto.aa.AaReceiver]'s surface & bridge contract so
 * [dev.zanderp.opencfmoto.AndroidAutoService], [dev.zanderp.opencfmoto.VideoPipeline], and
 * [dev.zanderp.opencfmoto.EasyConnProber] work identically without touching bike-side PXC code.
 */
class CarWithReceiver(
    private val context: Context,
    private val encoderSurface: Surface,
    private val log: (String) -> Unit,
) {
    companion object {
        private const val HOST = "127.0.0.1"
        private const val CONNECT_TIMEOUT_MS = 800
        private const val LISTENER_WAIT_MS = 25_000L
        private const val RETRY_DELAY_MS = 400L
        private const val VIDEO_HEARTBEAT_INTERVAL_MS = 2_000L
    }

    private val running = AtomicBoolean(false)
    private val sockets = EnumMap<CarLifeChannel, Socket>(CarLifeChannel::class.java)
    private val stateMachine = CarLifeStateMachine(
        authVerifier = CarLifeAuthVerifier.xiaomi(),
        seedExchange = CarLifeSeedExchange(),
        preferVehicleResolution = false,
    )
    private val commandRouter = CarLifeCommandRouter(stateMachine)
    private val videoParser = CarLifeVideoParser()
    private val touchState = CarLifeTouchState()
    private val audioParsers =
        listOf(CarLifeChannel.MEDIA, CarLifeChannel.TTS, CarLifeChannel.VOICE)
            .associateWith { CarLifeAudioParser() }
    private val audioPlayer = CarWithAudioPlayer(log)
    private val seenAudioKinds = Collections.synchronizedSet(HashSet<Pair<CarLifeChannel, Int>>())
    private val audioPcmFrames = ConcurrentHashMap<CarLifeChannel, Long>()
    private val unparsedAudio = ConcurrentHashMap<CarLifeChannel, Long>()

    private var sessionThread: Thread? = null
    private var heartbeatThread: Thread? = null
    private val touchExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "carwith-touch").apply { isDaemon = true }
    }
    @Volatile private var steadyVideoFired = false
    @Volatile private var videoWidth = CarLifeProtocol.DEFAULT_WIDTH
    @Volatile private var videoHeight = CarLifeProtocol.DEFAULT_HEIGHT
    @Volatile private var loggedTouchMap = false
    @Volatile private var entryTriggered = false
    @Volatile private var framesDecoded = 0L

    /** Fired when the CarWith session ends ([userExit] true if user tapped return-to-car). */
    @Volatile var onSessionEnded: ((userExit: Boolean) -> Unit)? = null

    private val videoDecoder = VideoDecoder().apply {
        fallbackWidth = CarLifeProtocol.DEFAULT_WIDTH
        fallbackHeight = CarLifeProtocol.DEFAULT_HEIGHT
        onFpsChanged = { fps ->
            log("[CARWITH] decode fps=$fps (${videoWidth}x$videoHeight)")
            AaVideoBridge.aaDecoding = true
            // CarWith idles at ~12-17 fps on static screens, so trigger steady video at >= 5 fps.
            if (!steadyVideoFired && fps >= 5) {
                steadyVideoFired = true
                log("[CARWITH] steady video reached (fps=$fps) — signalling ready for bike hand-off")
                try { AaVideoBridge.onSteadyVideo?.invoke() } catch (_: Exception) {}
            }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) {
            log("[CARWITH] already running")
            return
        }
        AaLog.sink = log
        BikeWifi.unbindIfNoBikeNetwork(context)
        if (!CarWithSelfMode.isAvailable(context)) {
            log("[CARWITH] CarWith / Xiaomi CarLife not installed on this phone")
            running.set(false)
            return
        }
        installBridgeSinks()
        sessionThread = thread(name = "carwith-client", isDaemon = true) {
            runSession()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        clearBridgeSinks()
        AaVideoBridge.aaSessionLive = false
        releaseActiveTouch()
        audioPlayer.stop()
        audioParsers.values.forEach { it.reset() }
        closeAllSockets()
        heartbeatThread?.interrupt()
        heartbeatThread = null
        sessionThread?.interrupt()
        sessionThread = null
        stateMachine.close()
        try { videoDecoder.stop("CarWithReceiver stop") } catch (_: Exception) {}
        log("[CARWITH] stopped")
    }

    private fun runSession() {
        var userExit = false
        try {
            if (!connectAllChannels()) {
                if (running.get()) {
                    log("[CARWITH] timed out waiting for CarLife 7 channels on 127.0.0.1")
                }
                return
            }
            if (!running.get()) return
            AaVideoBridge.aaSessionLive = true
            AaVideoBridge.aaSessionSeen = true
            log("[CARWITH] all 7 CarLife channels connected — starting handshake")

            videoDecoder.setSurface(encoderSurface)
            seenAudioKinds.clear()
            audioPcmFrames.clear()
            unparsedAudio.clear()
            audioParsers.values.forEach { it.reset() }
            audioPlayer.start()
            stateMachine.onConnected().forEach(::sendAction)

            startDrainReader(CarLifeChannel.VIDEO)
            startDrainReader(CarLifeChannel.MEDIA)
            startDrainReader(CarLifeChannel.TTS)
            startDrainReader(CarLifeChannel.VOICE)
            startDrainReader(CarLifeChannel.DATA)

            userExit = readCommandLoop()
        } catch (e: Exception) {
            if (running.get()) {
                log("[CARWITH] session error: ${e.javaClass.simpleName}: ${e.message}")
            }
        } finally {
            AaVideoBridge.aaSessionLive = false
            val wasRunning = running.getAndSet(false)
            clearBridgeSinks()
            audioPlayer.stop()
            audioParsers.values.forEach { it.reset() }
            closeAllSockets()
            heartbeatThread?.interrupt()
            heartbeatThread = null
            stateMachine.close()
            try { videoDecoder.stop("CarWith session ended") } catch (_: Exception) {}
            if (wasRunning) {
                try { onSessionEnded?.invoke(userExit) } catch (_: Exception) {}
            }
        }
    }

    private fun connectAllChannels(): Boolean {
        val deadline = System.currentTimeMillis() + LISTENER_WAIT_MS
        var attempt = 0
        while (running.get() && System.currentTimeMillis() < deadline) {
            attempt++
            val candidate = EnumMap<CarLifeChannel, Socket>(CarLifeChannel::class.java)
            var failedPort = 0
            try {
                for (channel in CarLifeChannel.entries) {
                    if (!running.get()) throw IOException("cancelled")
                    failedPort = channel.port
                    candidate[channel] = connectLoopback(channel.port)
                }
                synchronized(sockets) {
                    if (!running.get()) throw IOException("cancelled")
                    sockets.putAll(candidate)
                }
                return true
            } catch (e: Exception) {
                candidate.values.forEach { runCatching { it.close() } }
                if (!running.get()) return false
                if (!entryTriggered) {
                    entryTriggered = true
                    log("[CARWITH] ports not listening yet (port=$failedPort) — launching CarWith entry")
                    CarWithSelfMode.trigger(context, log)
                } else if (attempt == 1 || attempt % 10 == 0) {
                    log("[CARWITH] waiting for CarLife ports (try $attempt, port=$failedPort: ${e.message})")
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    return false
                }
            }
        }
        return false
    }

    /**
     * Loopback connect that survives bike Wi-Fi process binding (same pattern as AaReceiver).
     */
    private fun connectLoopback(port: Int): Socket {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val bound = try { cm?.boundNetworkForProcess } catch (_: Exception) { null }
        try {
            if (bound != null) try { cm?.bindProcessToNetwork(null) } catch (_: Exception) {}
            return Socket().apply {
                connect(InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MS)
                tcpNoDelay = true
                soTimeout = 0
            }
        } finally {
            if (bound != null) try { cm?.bindProcessToNetwork(bound) } catch (_: Exception) {}
        }
    }

    private fun readCommandLoop(): Boolean {
        val socket = socketFor(CarLifeChannel.COMMAND) ?: throw EOFException("command channel missing")
        val input = socket.getInputStream()
        while (running.get()) {
            val frame = readFrame(input, CarLifeChannel.COMMAND.port)
            val result = commandRouter.route(frame)
            result.log?.let(log)
            result.actions.forEach(::sendAction)
            if (result.returnToCar) return true
            if (frame.kind == CarLifeProtocol.MD_PROTOCOL_MATCH && !result.failed) {
                startHeartbeat()
            }
            if (result.encryptionReady) {
                val reqW = CarLifeProtocol.DEFAULT_WIDTH
                val reqH = CarLifeProtocol.DEFAULT_HEIGHT
                log("[CARWITH] AES ready — requesting video ${reqW}x$reqH")
                stateMachine.requestVideoInit(reqW, reqH).forEach(::sendAction)
                startHeartbeat()
            }
            if (frame.kind == CarLifeProtocol.VIDEO_ENCODER_INIT_DONE &&
                stateMachine.state != CarLifeStateMachine.State.FAILED
            ) {
                stateMachine.videoFormat?.let { fmt ->
                    releaseActiveTouch()
                    videoWidth = fmt.width
                    videoHeight = fmt.height
                    videoDecoder.fallbackWidth = fmt.width
                    videoDecoder.fallbackHeight = fmt.height
                }
            }
            if (stateMachine.state == CarLifeStateMachine.State.FAILED || result.failed) {
                log("[CARWITH] handshake state machine failed")
                return false
            }
        }
        return false
    }

    private fun startDrainReader(channel: CarLifeChannel) {
        val socket = socketFor(channel) ?: return
        thread(name = "carwith-${channel.name.lowercase()}", isDaemon = true) {
            try {
                val input = socket.getInputStream()
                while (running.get()) {
                    val frame = readFrame(input, channel.port)
                    when (channel) {
                        CarLifeChannel.VIDEO -> handleVideoFrame(frame)
                        CarLifeChannel.MEDIA, CarLifeChannel.TTS, CarLifeChannel.VOICE ->
                            handleAudioFrame(channel, frame)
                        else -> Unit // Data channel drained so CarWith never blocks.
                    }
                }
            } catch (_: EOFException) {
                if (running.get()) log("[CARWITH] ${channel.name} channel closed")
            } catch (e: Exception) {
                if (running.get()) log("[CARWITH] ${channel.name} read error: ${e.message}")
            }
        }
    }

    private fun handleAudioFrame(channel: CarLifeChannel, frame: CarLifeFrame) {
        val parser = audioParsers[channel] ?: return
        if (seenAudioKinds.add(channel to frame.kind)) {
            log("[CARWITH] ${channel.name} first frame kind=0x${frame.kind.toString(16)} ${frame.payload.size}B")
        }
        val payload = runCatching {
            CarLifeAudioPayload.decode(frame, stateMachine.payloadCipher, CarLifeAudioPayload.Mode.XIAOMI_MIX)
        }.getOrElse { e ->
            parser.reset()
            log("[CARWITH] ${channel.name} audio decrypt failed (kind=0x${frame.kind.toString(16)}): ${e.message}")
            return
        }
        val event = parser.parse(channel, frame.copy(payload = payload))
        if (event == null) {
            val count = unparsedAudio.merge(channel, 1L, Long::plus) ?: 1L
            if (count == 1L || count % 2_000L == 0L) {
                log("[CARWITH] ${channel.name} audio unparsed kind=0x${frame.kind.toString(16)} ${payload.size}B (#$count)")
            }
            return
        }
        when (event) {
            is CarLifeAudioEvent.Init ->
                log("[CARWITH] ${channel.name} audio init ${event.sampleRate}Hz ${event.channels}ch ${event.bitsPerSample}bit")
            is CarLifeAudioEvent.Pcm -> {
                val n = audioPcmFrames.merge(channel, 1L, Long::plus) ?: 1L
                if (n == 1L || n % 2_000L == 0L) {
                    log("[CARWITH] ${channel.name} audio PCM #$n (${event.littleEndian.size}B)")
                }
            }
            is CarLifeAudioEvent.Stop ->
                log("[CARWITH] ${channel.name} audio stop kind=0x${frame.kind.toString(16)}")
            is CarLifeAudioEvent.Resume ->
                log("[CARWITH] ${channel.name} audio resume")
        }
        audioPlayer.submit(channel, event)
    }

    private fun handleVideoFrame(frame: CarLifeFrame) {
        if (frame.kind != CarLifeProtocol.VIDEO_FRAME) return
        val cipher = stateMachine.payloadCipher
        val payload =
            if (cipher != null && frame.payload.isNotEmpty()) {
                runCatching { cipher.decrypt(frame.payload) }.getOrElse {
                    log("[CARWITH] video decrypt failed: ${it.message}")
                    return
                }
            } else {
                frame.payload
            }
        val units = videoParser.push(frame.timestamp.toLong() and 0xFFFF_FFFFL, payload)
        for (unit in units) {
            framesDecoded++
            if (framesDecoded == 1L) {
                log("[CARWITH] first video access unit (${unit.data.size}B, key=${unit.keyFrame})")
            }
            videoDecoder.decode(unit.data, 0, unit.data.size)
        }
    }

    private fun readFrame(input: InputStream, port: Int): CarLifeFrame {
        val headerSize = CarLifeProtocol.videoHeaderSize(port)
        val header = readExactBytes(input, headerSize)
        val length: Int
        val timestamp: Int
        val kind: Int
        val reserved: Int
        if (headerSize == 12) {
            val decoded = CarLifeProtocol.decodeVideoHeader(header)
            length = decoded.first
            timestamp = decoded.second
            kind = decoded.third
            reserved = 0
        } else {
            val decoded = CarLifeProtocol.decodeCommandHeader(header)
            length = decoded.first
            kind = decoded.second
            timestamp = 0
            reserved = (header[2].toInt() and 0xFF shl 8) or (header[3].toInt() and 0xFF)
        }
        return CarLifeFrame(
            channel = port,
            kind = kind,
            timestamp = timestamp,
            reserved = reserved,
            payload = readExactBytes(input, length),
        )
    }

    private fun readExactBytes(input: InputStream, count: Int): ByteArray {
        val output = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(output, offset, count - offset)
            if (read < 0) throw EOFException("CarLife socket closed")
            offset += read
        }
        return output
    }

    private fun sendAction(action: CarLifeStateMachine.Action) {
        val socket = socketFor(CarLifeChannel.COMMAND) ?: return
        val cipher = stateMachine.payloadCipher
        val body = if (cipher != null) cipher.encrypt(action.payload) else action.payload
        synchronized(socket) {
            socket.getOutputStream().apply {
                write(CarLifeProtocol.encodeCommand(action.kind, body))
                flush()
            }
        }
    }

    private fun startHeartbeat() {
        if (heartbeatThread != null) return
        heartbeatThread = thread(name = "carwith-heartbeat", isDaemon = true) {
            try {
                while (running.get()) {
                    Thread.sleep(VIDEO_HEARTBEAT_INTERVAL_MS)
                    if (!running.get()) break
                    sendVideoHeartbeat()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun sendVideoHeartbeat() {
        val socket = socketFor(CarLifeChannel.VIDEO) ?: return
        val timestamp = (System.currentTimeMillis() / 1000L).toInt()
        val cipher = stateMachine.payloadCipher
        val payload = if (cipher == null) ByteArray(0) else cipher.encrypt(ByteArray(0))
        runCatching {
            synchronized(socket) {
                socket.getOutputStream().apply {
                    write(CarLifeProtocol.encodeVideo(CarLifeProtocol.VIDEO_HEARTBEAT, timestamp, payload))
                    flush()
                }
            }
        }
    }

    private fun installBridgeSinks() {
        AaVideoBridge.touchSink = { action, pointerId, cx, cy ->
            if (pointerId == 0) {
                val mapped = AaVideoBridge.pipeline?.mapBikeTouchToSource(cx, cy)
                if (mapped != null) {
                    if (!loggedTouchMap) {
                        log("[CARWITH] dash touch ($cx,$cy) -> source (${mapped.first},${mapped.second})")
                        loggedTouchMap = true
                    }
                    sendTouch(mapped.first, mapped.second, down = action == 0 || action == 2)
                }
            }
        }
        AaVideoBridge.previewTouchSink = { action, pointerId, sx, sy ->
            if (pointerId == 0) {
                val w = videoDecoder.videoWidth.takeIf { it > 0 } ?: videoWidth
                val h = videoDecoder.videoHeight.takeIf { it > 0 } ?: videoHeight
                val uw = BikeProfileHolder.aaUsableWidth
                val uh = BikeProfileHolder.aaUsableHeight
                val tx = if (uw > 0 && uw != w) (sx.toLong() * w / uw).toInt().coerceIn(0, w - 1) else sx
                val ty = if (uh > 0 && uh != h) (sy.toLong() * h / uh).toInt().coerceIn(0, h - 1) else sy
                if (action == 0 || action == 1) {
                    log("[CARWITH] preview touch action=$action ($sx,$sy) -> ($tx,$ty) [${w}x$h]")
                }
                sendTouch(tx, ty, down = action == 0 || action == 2)
            }
        }
        AaVideoBridge.keySink = { keycode ->
            val hardKey = when (keycode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> CarLifeProtocol.HARD_KEY_SEEK_ADD
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> CarLifeProtocol.HARD_KEY_SEEK_SUB
                else -> null
            }
            if (hardKey != null) {
                sendTouchFrame(CarLifeProtocol.TOUCH_CAR_HARD_KEY_CODE, CarLifeProtocol.hardKeyPayload(hardKey))
            }
        }
    }

    private fun clearBridgeSinks() {
        AaVideoBridge.touchSink = null
        AaVideoBridge.previewTouchSink = null
        AaVideoBridge.keySink = null
        AaVideoBridge.scrollSink = null
        AaVideoBridge.nightSink = null
    }

    private fun sendTouch(x: Int, y: Int, down: Boolean) {
        if (stateMachine.state != CarLifeStateMachine.State.VIDEO_RUNNING) return
        val w = videoDecoder.videoWidth.takeIf { it > 0 } ?: videoWidth
        val h = videoDecoder.videoHeight.takeIf { it > 0 } ?: videoHeight
        val action = touchState.onContact(x, y, down, w, h) ?: return
        val kind = when (action) {
            CarLifeTouchAction.DOWN -> CarLifeProtocol.TOUCH_DOWN
            CarLifeTouchAction.MOVE -> CarLifeProtocol.TOUCH_MOVE
            CarLifeTouchAction.UP -> CarLifeProtocol.TOUCH_UP
        }
        sendTouchFrame(kind, CarLifeProtocol.touchPayload(x, y))
    }

    private fun releaseActiveTouch() {
        val pt = touchState.releasePoint() ?: return
        sendTouchFrame(CarLifeProtocol.TOUCH_UP, CarLifeProtocol.touchPayload(pt.first, pt.second))
    }

    private fun sendTouchFrame(kind: Int, payload: ByteArray) {
        val socket = socketFor(CarLifeChannel.TOUCH) ?: return
        val cipher = stateMachine.payloadCipher
        val body = if (cipher != null) cipher.encrypt(payload) else payload
        touchExecutor.execute {
            runCatching {
                synchronized(socket) {
                    socket.getOutputStream().apply {
                        write(CarLifeProtocol.encodeCommand(kind, body))
                        flush()
                    }
                }
            }.onFailure { e ->
                if (running.get()) log("[CARWITH] touch send failed: ${e.message}")
            }
        }
    }

    private fun socketFor(channel: CarLifeChannel): Socket? =
        synchronized(sockets) { sockets[channel]?.takeUnless { it.isClosed } }

    private fun closeAllSockets() {
        synchronized(sockets) {
            sockets.values.forEach { runCatching { it.close() } }
            sockets.clear()
        }
    }
}
