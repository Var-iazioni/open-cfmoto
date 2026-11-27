// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto.carwith

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays CarWith's CarLife PCM channels ([CarLifeChannel.MEDIA], [CarLifeChannel.TTS],
 * [CarLifeChannel.VOICE]) locally on the phone via [AudioTrack].
 *
 * Because the motorcycle dashboard has no audio output, playing through standard media and
 * navigation [AudioAttributes] automatically routes sound to the phone's speaker or any
 * connected Bluetooth audio device (headset / helmet intercom), without stealing audio focus
 * from apps running on the same phone.
 */
class CarWithAudioPlayer(
    private val log: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)

    @Volatile
    private var promptDuckUntilMs = 0L

    private val mediaSink = PcmTrackSink(
        name = "media",
        // Use USAGE_ASSISTANCE_NAVIGATION_GUIDANCE (still STRATEGY_MEDIA / STREAM_MUSIC) so
        // CarWith's AudioPolicyMix (which captures USAGE_MEDIA/GAME/UNKNOWN into REMOTE_SUBMIX)
        // does not loop our playback back into CarWith instead of the phone speaker / Bluetooth.
        attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build(),
        onIdleTick = { refreshDucking() },
        log = log,
    )

    private val promptSink = PcmTrackSink(
        name = "prompt",
        attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
        onIdleTick = { refreshDucking() },
        log = log,
    )

    fun start() {
        if (!running.compareAndSet(false, true)) return
        promptDuckUntilMs = 0L
        mediaSink.start()
        promptSink.start()
        log("[CARWITH] local audio player started (phone speaker / Bluetooth)")
    }

    fun submit(channel: CarLifeChannel, event: CarLifeAudioEvent) {
        if (!running.get()) return
        val sink = sinkFor(channel) ?: return
        when (event) {
            is CarLifeAudioEvent.Init -> {
                sink.submitConfigure(event.sampleRate, event.channels)
            }
            is CarLifeAudioEvent.Pcm -> {
                if (channel != CarLifeChannel.MEDIA) {
                    promptDuckUntilMs = SystemClock.elapsedRealtime() + PROMPT_ACTIVE_HOLD_MS
                    mediaSink.setVolume(DUCK_VOLUME)
                } else {
                    refreshDucking()
                }
                sink.submitPcm(event.sampleRate, event.channels, event.littleEndian)
            }
            is CarLifeAudioEvent.Stop -> {
                val releaseTrack = event.kind != CarLifeProtocol.MEDIA_PAUSE
                sink.submitStop(releaseTrack)
                if (channel != CarLifeChannel.MEDIA) {
                    promptDuckUntilMs = SystemClock.elapsedRealtime() + PROMPT_END_HOLD_MS
                    refreshDucking()
                }
            }
            is CarLifeAudioEvent.Resume -> Unit
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        promptDuckUntilMs = 0L
        mediaSink.stop()
        promptSink.stop()
        log("[CARWITH] local audio player stopped")
    }

    private fun sinkFor(channel: CarLifeChannel): PcmTrackSink? =
        when (channel) {
            CarLifeChannel.MEDIA -> mediaSink
            CarLifeChannel.TTS, CarLifeChannel.VOICE -> promptSink
            else -> null
        }

    private fun refreshDucking() {
        val want = if (SystemClock.elapsedRealtime() < promptDuckUntilMs) DUCK_VOLUME else 1.0f
        mediaSink.setVolume(want)
    }

    private class PcmTrackSink(
        private val name: String,
        private val attrs: AudioAttributes,
        private val onIdleTick: () -> Unit,
        private val log: (String) -> Unit,
    ) {
        private sealed interface Task {
            data class Configure(val sampleRate: Int, val channels: Int) : Task
            data class Pcm(val sampleRate: Int, val channels: Int, val data: ByteArray) : Task
            data class Stop(val releaseTrack: Boolean) : Task
        }

        private val queue = LinkedBlockingQueue<Task>(QUEUE_LIMIT)
        private val active = AtomicBoolean(false)
        private var worker: Thread? = null

        private val trackLock = Any()
        private var track: AudioTrack? = null
        private var trackSampleRate = 0
        private var trackChannels = 0

        @Volatile
        private var targetVolume = 1.0f

        @Volatile
        private var appliedVolume = 1.0f

        @Volatile
        private var droppedPackets = 0L

        fun start() {
            if (!active.compareAndSet(false, true)) return
            queue.clear()
            targetVolume = 1.0f
            appliedVolume = 1.0f
            droppedPackets = 0L
            worker = Thread(
                {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    try {
                        while (active.get()) {
                            val task = queue.poll(IDLE_POLL_MS, TimeUnit.MILLISECONDS)
                            if (task == null) {
                                onIdleTick()
                                continue
                            }
                            handle(task)
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        releaseTrack()
                    }
                },
                "carwith-audio-$name",
            ).apply {
                isDaemon = true
                start()
            }
        }

        fun submitConfigure(sampleRate: Int, channels: Int) {
            offer(Task.Configure(sampleRate, channels))
        }

        fun submitPcm(sampleRate: Int, channels: Int, data: ByteArray) {
            offer(Task.Pcm(sampleRate, channels, data))
        }

        fun submitStop(releaseTrack: Boolean) {
            // Drop stale queued PCM immediately so pause/stop takes effect without lag.
            queue.removeAll { it is Task.Pcm }
            offer(Task.Stop(releaseTrack))
        }

        fun setVolume(volume: Float) {
            val clamped = volume.coerceIn(0f, 1f)
            targetVolume = clamped
            if (clamped == appliedVolume) return
            synchronized(trackLock) {
                val t = track ?: return
                if (t.state == AudioTrack.STATE_INITIALIZED) {
                    runCatching { t.setVolume(clamped) }
                    appliedVolume = clamped
                }
            }
        }

        fun stop() {
            if (!active.compareAndSet(true, false)) return
            worker?.interrupt()
            worker = null
            queue.clear()
            releaseTrack()
        }

        private fun offer(task: Task) {
            if (!active.get()) return
            if (!queue.offer(task)) {
                queue.poll()
                droppedPackets++
                queue.offer(task)
            }
        }

        private fun handle(task: Task) {
            when (task) {
                is Task.Configure -> ensureTrack(task.sampleRate, task.channels)
                is Task.Pcm -> {
                    val t = ensureTrack(task.sampleRate, task.channels) ?: return
                    if (appliedVolume != targetVolume) {
                        runCatching { t.setVolume(targetVolume) }
                        appliedVolume = targetVolume
                    }
                    if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        runCatching { t.play() }.onFailure { e ->
                            log("[CARWITH] audio($name) play failed: ${e.message}")
                            releaseTrack()
                            return
                        }
                    }
                    val written = runCatching {
                        t.write(task.data, 0, task.data.size, AudioTrack.WRITE_BLOCKING)
                    }.getOrElse { e ->
                        log("[CARWITH] audio($name) write failed: ${e.message}")
                        releaseTrack()
                        return
                    }
                    if (written < 0) {
                        log("[CARWITH] audio($name) write err=$written — resetting track")
                        releaseTrack()
                    }
                }
                is Task.Stop -> {
                    synchronized(trackLock) {
                        val t = track ?: return
                        runCatching {
                            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
                            t.flush()
                        }
                        if (task.releaseTrack) {
                            runCatching { t.release() }
                            track = null
                            trackSampleRate = 0
                            trackChannels = 0
                        }
                    }
                }
            }
        }

        private fun ensureTrack(sampleRate: Int, channels: Int): AudioTrack? {
            synchronized(trackLock) {
                val existing = track
                if (existing != null &&
                    existing.state == AudioTrack.STATE_INITIALIZED &&
                    trackSampleRate == sampleRate &&
                    trackChannels == channels
                ) {
                    return existing
                }
                releaseTrackLocked()
                val channelMask =
                    if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                val frameBytes = channels * 2
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuf <= 0) {
                    log("[CARWITH] audio($name) unsupported format ${sampleRate}Hz/${channels}ch (minBuf=$minBuf)")
                    return null
                }
                val targetBuf = (sampleRate * frameBytes * BUFFER_MS) / 1_000
                val rawBuf = maxOf(minBuf * 2, targetBuf)
                val alignedBuf = ((rawBuf + frameBytes - 1) / frameBytes) * frameBytes
                val created = runCatching {
                    AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelMask)
                                .build(),
                        )
                        .setBufferSizeInBytes(alignedBuf)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                }.getOrElse { e ->
                    log("[CARWITH] audio($name) AudioTrack init failed: ${e.message}")
                    return null
                }
                if (created.state != AudioTrack.STATE_INITIALIZED) {
                    log("[CARWITH] audio($name) AudioTrack state=${created.state} (uninitialized)")
                    runCatching { created.release() }
                    return null
                }
                runCatching { created.setVolume(targetVolume) }
                appliedVolume = targetVolume
                track = created
                trackSampleRate = sampleRate
                trackChannels = channels
                log("[CARWITH] audio($name) track ready: ${sampleRate}Hz ${channels}ch buf=${alignedBuf}B")
                return created
            }
        }

        private fun releaseTrack() {
            synchronized(trackLock) { releaseTrackLocked() }
        }

        private fun releaseTrackLocked() {
            val t = track ?: return
            runCatching {
                if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
                t.flush()
                t.release()
            }
            track = null
            trackSampleRate = 0
            trackChannels = 0
        }
    }

    private companion object {
        const val QUEUE_LIMIT = 64
        const val IDLE_POLL_MS = 100L
        const val BUFFER_MS = 200
        const val DUCK_VOLUME = 0.25f
        const val PROMPT_ACTIVE_HOLD_MS = 600L
        const val PROMPT_END_HOLD_MS = 250L
    }
}
