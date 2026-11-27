// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.zanderp.opencfmoto.carwith

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class CarLifeProtocolTest {

    @Test
    fun commandHeaderRoundTrip() {
        val payload = CarLifeProtocol.protocolVersionPayload()
        val wire = CarLifeProtocol.encodeCommand(CarLifeProtocol.HU_PROTOCOL_VERSION, payload)
        val (len, kind) = CarLifeProtocol.decodeCommandHeader(wire.copyOfRange(0, 8))
        assertEquals(payload.size, len)
        assertEquals(CarLifeProtocol.HU_PROTOCOL_VERSION, kind)
        assertArrayEquals(payload, wire.copyOfRange(8, wire.size))
    }

    @Test
    fun videoHeaderRoundTrip() {
        val payload = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte())
        val wire = CarLifeProtocol.encodeVideo(CarLifeProtocol.VIDEO_FRAME, 123456, payload)
        val (len, ts, kind) = CarLifeProtocol.decodeVideoHeader(wire.copyOfRange(0, 12))
        assertEquals(payload.size, len)
        assertEquals(123456, ts)
        assertEquals(CarLifeProtocol.VIDEO_FRAME, kind)
    }

    @Test
    fun aesCipherRoundTrip() {
        val key = ByteArray(16) { (it + 1).toByte() }
        val cipher = CarLifePayloadCipher(key)
        val plain = "hello-carwith".toByteArray()
        val enc = cipher.encrypt(plain)
        assertArrayEquals(plain, cipher.decrypt(enc))
        assertEquals(0, cipher.encrypt(ByteArray(0)).size)
    }

    @Test
    fun authV2VerificationMatchesMd5Digest() {
        val info = CarLifeProtocol.DeviceInfo(brand = "Xiaomi", model = "K60", sdk = "35")
        val challenge = "2.0;abcdef123456"
        val digest = MessageDigest.getInstance("MD5")
            .digest("CARLIFEXiaomiK6035abcdef123456".toByteArray())
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        val payload = CarLifeProtocol.pbString(1, digest)
        assertTrue(CarLifeAuthVerifier.xiaomi().verify(challenge, payload, info))
    }

    @Test
    fun videoParserSplitsAccessUnitsByStartCode() {
        val parser = CarLifeVideoParser()
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1F)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x0D, 0x88.toByte())
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte())
        val pSlice = byteArrayOf(0, 0, 0, 1, 0x41, 0x9A.toByte(), 0x22)
        val units = parser.push(100L, sps + pps + idr + pSlice) + parser.flush()
        assertEquals(2, units.size)
        assertTrue(units[0].keyFrame)
        assertNotNull(units[0].sps)
        assertNotNull(units[0].pps)
        assertEquals(false, units[1].keyFrame)
    }
}
