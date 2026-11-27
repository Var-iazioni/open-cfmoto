// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.zanderp.opencfmoto.carwith

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLifeAudioTest {
    private val cipher = CarLifePayloadCipher(ByteArray(16) { (it + 1).toByte() })

    @Test
    fun xiaomiMixMediaPcmIsPlainEvenWhenLengthIsMultipleOf16() {
        val pcm = ByteArray(4096) { (it * 13).toByte() }
        val frame = audioFrame(CarLifeProtocol.PORT_MEDIA, CarLifeProtocol.MEDIA_DATA, pcm)
        assertArrayEquals(pcm, CarLifeAudioPayload.decode(frame, cipher, CarLifeAudioPayload.Mode.XIAOMI_MIX))
    }

    @Test
    fun xiaomiMixInitAndTtsUseAesDecryption() {
        val init = CarLifeProtocol.pbInt(1, 44_100) + CarLifeProtocol.pbInt(2, 2) + CarLifeProtocol.pbInt(3, 16)
        val initFrame = audioFrame(CarLifeProtocol.PORT_MEDIA, CarLifeProtocol.MEDIA_INIT, cipher.encrypt(init))
        assertArrayEquals(init, CarLifeAudioPayload.decode(initFrame, cipher, CarLifeAudioPayload.Mode.XIAOMI_MIX))

        val ttsPcm = ByteArray(320) { it.toByte() }
        val ttsFrame = audioFrame(CarLifeProtocol.PORT_TTS, CarLifeProtocol.NAVI_TTS_DATA, cipher.encrypt(ttsPcm))
        assertArrayEquals(ttsPcm, CarLifeAudioPayload.decode(ttsFrame, cipher, CarLifeAudioPayload.Mode.XIAOMI_MIX))
    }

    @Test
    fun emptyControlPayloadsBypassDecrypt() {
        val pause = audioFrame(CarLifeProtocol.PORT_MEDIA, CarLifeProtocol.MEDIA_PAUSE, ByteArray(0))
        assertArrayEquals(ByteArray(0), CarLifeAudioPayload.decode(pause, cipher, CarLifeAudioPayload.Mode.XIAOMI_MIX))
    }

    @Test
    fun parsesMediaInitAndNormalizesMasks() {
        val parser = CarLifeAudioParser()
        val init = initFrame(CarLifeProtocol.PORT_MEDIA, CarLifeProtocol.MEDIA_INIT, 44_100, 12, 2)
        assertEquals(CarLifeAudioEvent.Init(44_100, 2, 16), parser.parse(CarLifeChannel.MEDIA, init))

        val pcm = byteArrayOf(1, 2, 3, 4)
        val data = parser.parse(CarLifeChannel.MEDIA, audioFrame(CarLifeProtocol.PORT_MEDIA, CarLifeProtocol.MEDIA_DATA, pcm))
        assertTrue(data is CarLifeAudioEvent.Pcm)
        assertArrayEquals(pcm, (data as CarLifeAudioEvent.Pcm).littleEndian)
    }

    @Test
    fun rejectsPcmBeforeInitAndInvalidFrameSizes() {
        val parser = CarLifeAudioParser()
        assertNull(parser.parse(CarLifeChannel.MEDIA, audioFrame(CarLifeProtocol.PORT_MEDIA, CarLifeProtocol.MEDIA_DATA, ByteArray(4))))

        parser.parse(CarLifeChannel.MEDIA, initFrame(CarLifeProtocol.PORT_MEDIA, CarLifeProtocol.MEDIA_INIT, 48_000, 2, 16))
        assertNull(parser.parse(CarLifeChannel.MEDIA, audioFrame(CarLifeProtocol.PORT_MEDIA, CarLifeProtocol.MEDIA_DATA, ByteArray(3))))
    }

    @Test
    fun normalizesTtsAndVoiceToMono() {
        val parser = CarLifeAudioParser()
        val init = initFrame(CarLifeProtocol.PORT_TTS, CarLifeProtocol.NAVI_TTS_INIT, 16_000, 2, 16)
        assertEquals(CarLifeAudioEvent.Init(16_000, 1, 16), parser.parse(CarLifeChannel.TTS, init))

        val pcm = ByteArray(160) { 0x11 }
        val parsed = parser.parse(CarLifeChannel.TTS, audioFrame(CarLifeProtocol.PORT_TTS, CarLifeProtocol.NAVI_TTS_DATA, pcm))
        assertTrue(parsed is CarLifeAudioEvent.Pcm)
        assertEquals(1, (parsed as CarLifeAudioEvent.Pcm).channels)
    }

    @Test
    fun pauseRetainsFormatAndStopResetsFormat() {
        val parser = CarLifeAudioParser()
        parser.parse(CarLifeChannel.MEDIA, initFrame(CarLifeProtocol.PORT_MEDIA, CarLifeProtocol.MEDIA_INIT, 44_100, 2, 16))
        assertTrue(parser.parse(CarLifeChannel.MEDIA, audioFrame(CarLifeProtocol.PORT_MEDIA, CarLifeProtocol.MEDIA_PAUSE, ByteArray(0))) is CarLifeAudioEvent.Stop)
        assertTrue(parser.parse(CarLifeChannel.MEDIA, audioFrame(CarLifeProtocol.PORT_MEDIA, CarLifeProtocol.MEDIA_RESUME_PLAY, ByteArray(0))) is CarLifeAudioEvent.Resume)
        assertTrue(parser.parse(CarLifeChannel.MEDIA, audioFrame(CarLifeProtocol.PORT_MEDIA, CarLifeProtocol.MEDIA_DATA, ByteArray(8))) is CarLifeAudioEvent.Pcm)

        parser.parse(CarLifeChannel.MEDIA, audioFrame(CarLifeProtocol.PORT_MEDIA, CarLifeProtocol.MEDIA_STOP, ByteArray(0)))
        assertNull(parser.parse(CarLifeChannel.MEDIA, audioFrame(CarLifeProtocol.PORT_MEDIA, CarLifeProtocol.MEDIA_DATA, ByteArray(8))))
    }

    @Test
    fun malformedInitClearsPreviousFormat() {
        val parser = CarLifeAudioParser()
        parser.parse(CarLifeChannel.MEDIA, initFrame(CarLifeProtocol.PORT_MEDIA, CarLifeProtocol.MEDIA_INIT, 44_100, 2, 16))
        assertNull(parser.parse(CarLifeChannel.MEDIA, audioFrame(CarLifeProtocol.PORT_MEDIA, CarLifeProtocol.MEDIA_INIT, byteArrayOf(0x0F))))
        assertNull(parser.parse(CarLifeChannel.MEDIA, audioFrame(CarLifeProtocol.PORT_MEDIA, CarLifeProtocol.MEDIA_DATA, ByteArray(8))))
    }

    private fun initFrame(port: Int, kind: Int, rate: Int, channels: Int, bits: Int): CarLifeFrame =
        audioFrame(
            port,
            kind,
            CarLifeProtocol.pbInt(1, rate) + CarLifeProtocol.pbInt(2, channels) + CarLifeProtocol.pbInt(3, bits),
        )

    private fun audioFrame(port: Int, kind: Int, payload: ByteArray): CarLifeFrame =
        CarLifeFrame(channel = port, kind = kind, timestamp = 1, reserved = 0, payload = payload)
}
