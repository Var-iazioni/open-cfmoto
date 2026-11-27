// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from mcarplay (https://github.com/wangxso/mcarplay, MIT License).
// Copyright (C) wangxso / mcarplay contributors, OpenCfMoto contributors.
package dev.zanderp.opencfmoto.carwith

/** Explicit package-specific payload decryption; failed decryption never falls back to plaintext. */
object CarLifeAudioPayload {
    enum class Mode { ENCRYPTED, XIAOMI_MIX }

    fun decode(
        frame: CarLifeFrame,
        cipher: CarLifePayloadCipher?,
        mode: Mode = Mode.XIAOMI_MIX,
    ): ByteArray {
        // Xiaomi's PcmHoldHelper encrypts INIT via PCMPackageHead, but copies
        // MEDIA_DATA samples directly into the socket buffer without AES.
        val rawMixPcm =
            mode == Mode.XIAOMI_MIX &&
                frame.channel == CarLifeProtocol.PORT_MEDIA &&
                frame.kind == CarLifeProtocol.MEDIA_DATA
        return if (cipher == null || frame.payload.isEmpty() || rawMixPcm) {
            frame.payload
        } else {
            cipher.decrypt(frame.payload)
        }
    }
}

/** Parsed CarLife audio control/data payload for one audio channel. */
sealed class CarLifeAudioEvent {
    data class Init(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
    ) : CarLifeAudioEvent()

    data class Pcm(
        val littleEndian: ByteArray,
        val sampleRate: Int,
        val channels: Int,
    ) : CarLifeAudioEvent()

    data class Stop(
        val kind: Int,
    ) : CarLifeAudioEvent()

    data class Resume(
        val kind: Int,
    ) : CarLifeAudioEvent()
}

/**
 * Parses the 12-byte media frames emitted by Xiaomi CarWith/CarLife on MEDIA / TTS / VOICE.
 *
 * Uses protobuf fields 1/2/3 (sampleRate, channelCountOrMask, bitsOrEncoding) for init frames
 * and little-endian 16-bit PCM payloads for data frames.
 */
class CarLifeAudioParser {
    private var sampleRate = 0
    private var channels = 0
    private var bitsPerSample = 0

    fun reset() {
        sampleRate = 0
        channels = 0
        bitsPerSample = 0
    }

    fun parse(
        channel: CarLifeChannel,
        frame: CarLifeFrame,
    ): CarLifeAudioEvent? =
        when (frame.kind) {
            CarLifeProtocol.MEDIA_INIT,
            CarLifeProtocol.NAVI_TTS_INIT,
            CarLifeProtocol.VR_AUDIO_INIT,
            ->
                runCatching { parseInit(frame.payload, channel) }.getOrElse {
                    reset()
                    null
                }

            CarLifeProtocol.MEDIA_DATA,
            CarLifeProtocol.NAVI_TTS_DATA,
            CarLifeProtocol.VR_AUDIO_DATA,
            -> pcm(frame.payload)

            CarLifeProtocol.MEDIA_RESUME_PLAY -> CarLifeAudioEvent.Resume(frame.kind)

            CarLifeProtocol.MEDIA_STOP,
            CarLifeProtocol.MEDIA_PAUSE,
            CarLifeProtocol.NAVI_TTS_END,
            CarLifeProtocol.VR_AUDIO_STOP,
            -> {
                if (frame.kind != CarLifeProtocol.MEDIA_PAUSE) reset()
                CarLifeAudioEvent.Stop(frame.kind)
            }

            else -> null
        }

    private fun parseInit(
        payload: ByteArray,
        channel: CarLifeChannel,
    ): CarLifeAudioEvent.Init? {
        reset()
        val rate = CarLifeProtocol.firstIntField(payload, 1)
        val channelField = CarLifeProtocol.firstIntField(payload, 2)
        val formatField = CarLifeProtocol.firstIntField(payload, 3)
        if (rate == null || rate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) return null
        // Normalize Android channel masks (OUT_MONO=4, IN_MONO=16/8, OUT_STEREO=12) and direct counts.
        val channelCount =
            when (channelField) {
                1, 4, 8 -> 1
                2, 12 -> 2
                else -> return null
            }
        val bits =
            when (formatField) {
                8, 3 -> 8
                16, 2 -> 16
                else -> return null
            }
        sampleRate = rate
        channels = if (channel == CarLifeChannel.MEDIA) channelCount else 1
        bitsPerSample = bits
        return CarLifeAudioEvent.Init(rate, channels, bits)
    }

    private fun pcm(payload: ByteArray): CarLifeAudioEvent.Pcm? =
        if (sampleRate != 0 &&
            channels != 0 &&
            bitsPerSample == 16 &&
            payload.isNotEmpty() &&
            payload.size % (channels * 2) == 0
        ) {
            CarLifeAudioEvent.Pcm(payload, sampleRate, channels)
        } else {
            null
        }

    companion object {
        const val MIN_SAMPLE_RATE = 8_000
        const val MAX_SAMPLE_RATE = 192_000
    }
}
