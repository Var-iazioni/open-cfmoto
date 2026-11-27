// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from mcarplay (https://github.com/wangxso/mcarplay, MIT License).
// Copyright (C) wangxso / mcarplay contributors, OpenCfMoto contributors.
package dev.zanderp.opencfmoto.carwith

import java.io.ByteArrayOutputStream

data class CarLifeVideoAccessUnit(
    val timestamp: Long,
    val data: ByteArray,
    val keyFrame: Boolean,
    val sps: ByteArray?,
    val pps: ByteArray?,
)

/**
 * Converts CarLife's per-packet Annex B data into complete access units.
 *
 * CarLife timestamps are Unix seconds and can be shared by many frames, so they
 * are not access-unit boundaries. H.264 start codes and slice headers are used instead.
 */
class CarLifeVideoParser {
    private var pendingTimestamp: Long? = null
    private val pendingNals = ArrayList<ByteArray>()
    private var pendingHasVcl = false
    private var pendingHasIdr = false
    private var pendingSps: ByteArray? = null
    private var pendingPps: ByteArray? = null
    private var annexBBuffer = ByteArray(0)
    private var annexBTimestamp: Long? = null
    private var latestSps: ByteArray? = null
    private var latestPps: ByteArray? = null

    fun push(
        timestamp: Long,
        payload: ByteArray,
    ): List<CarLifeVideoAccessUnit> {
        if (payload.isEmpty()) return emptyList()
        val complete = ArrayList<CarLifeVideoAccessUnit>(2)

        if (annexBTimestamp != null && annexBTimestamp != timestamp && annexBBuffer.isNotEmpty()) {
            processAvailableNals(annexBTimestamp!!, final = true, target = complete)
        }
        if (annexBBuffer.isEmpty()) annexBTimestamp = timestamp
        annexBBuffer += payload
        processAvailableNals(timestamp, final = false, target = complete)
        return complete
    }

    fun flush(): List<CarLifeVideoAccessUnit> {
        val complete = ArrayList<CarLifeVideoAccessUnit>(1)
        annexBTimestamp?.let { timestamp ->
            processAvailableNals(timestamp, final = true, target = complete)
        }
        flushAccessUnit(complete)
        return complete
    }

    private fun processAvailableNals(
        timestamp: Long,
        final: Boolean,
        target: MutableList<CarLifeVideoAccessUnit>,
    ) {
        val starts = annexBStartCodes(annexBBuffer)
        var startsAccessUnitBeforeTrailing = false
        if (starts.isEmpty()) {
            if (final && annexBBuffer.isNotEmpty()) annexBBuffer = ByteArray(0)
            return
        }
        for (index in starts.indices) {
            val (start, prefixLength) = starts[index]
            if (index == starts.lastIndex && !final) break
            val bodyStart = start + prefixLength
            val bodyEnd = if (index + 1 < starts.size) starts[index + 1].first else annexBBuffer.size
            if (bodyEnd > bodyStart) {
                consumeNal(annexBBuffer.copyOfRange(bodyStart, bodyEnd), timestamp, target)
            }
        }
        if (!final) {
            val (start, prefixLength) = starts.last()
            if (start + prefixLength < annexBBuffer.size) {
                val trailing = annexBBuffer.copyOfRange(start + prefixLength, annexBBuffer.size)
                val type = trailing[0].toInt() and 0x1F
                val startsAccessUnit =
                    pendingHasVcl &&
                        (
                            type == 9 ||
                                type == 6 ||
                                type == 7 ||
                                type == 8 ||
                                ((type == 1 || type == 5) && firstMbInSlice(trailing) == 0)
                        )
                startsAccessUnitBeforeTrailing = startsAccessUnit
            }
        }
        val lastStart = starts.last().first
        annexBBuffer =
            if (final) {
                ByteArray(0)
            } else {
                annexBBuffer.copyOfRange(lastStart, annexBBuffer.size)
            }
        if (annexBBuffer.isEmpty()) annexBTimestamp = null
        if (startsAccessUnitBeforeTrailing) flushAccessUnit(target)
    }

    private fun consumeNal(
        nal: ByteArray,
        timestamp: Long,
        target: MutableList<CarLifeVideoAccessUnit>,
    ) {
        if (nal.isEmpty()) return
        val type = nal[0].toInt() and 0x1F
        val isVcl = type == 1 || type == 5
        val startsAccessUnit =
            type == 9 ||
                (pendingHasVcl && (type == 6 || type == 7 || type == 8)) ||
                (pendingHasVcl && isVcl && firstMbInSlice(nal) == 0)
        if (startsAccessUnit) flushAccessUnit(target)
        if (pendingTimestamp == null) pendingTimestamp = timestamp
        pendingNals += nal
        pendingHasVcl = pendingHasVcl || isVcl
        when (type) {
            5 -> pendingHasIdr = true
            7 -> {
                pendingSps = nal
                latestSps = nal
            }
            8 -> {
                pendingPps = nal
                latestPps = nal
            }
        }
        val total = pendingNals.sumOf { it.size + ANNEX_B_START_CODE.size }
        if (total > MAX_ACCESS_UNIT_BYTES) {
            resetAccessUnit()
        }
    }

    private fun flushAccessUnit(target: MutableList<CarLifeVideoAccessUnit>) {
        val timestamp = pendingTimestamp
        if (timestamp == null || pendingNals.isEmpty()) {
            resetAccessUnit()
            return
        }
        val bytes = ByteArrayOutputStream()
        pendingNals.forEach { nal ->
            bytes.write(ANNEX_B_START_CODE)
            bytes.write(nal)
        }
        target +=
            CarLifeVideoAccessUnit(
                timestamp = timestamp,
                data = bytes.toByteArray(),
                keyFrame = pendingHasIdr,
                sps = pendingSps ?: latestSps,
                pps = pendingPps ?: latestPps,
            )
        resetAccessUnit()
    }

    private fun resetAccessUnit() {
        pendingTimestamp = null
        pendingNals.clear()
        pendingHasVcl = false
        pendingHasIdr = false
        pendingSps = null
        pendingPps = null
    }

    private companion object {
        val ANNEX_B_START_CODE = byteArrayOf(0, 0, 0, 1)
        const val MAX_ACCESS_UNIT_BYTES = 4 * 1024 * 1024
    }
}

internal fun annexBStartCodes(data: ByteArray): List<Pair<Int, Int>> {
    val starts = ArrayList<Pair<Int, Int>>()
    var index = 0
    while (index + 3 <= data.size) {
        when {
            index + 4 <= data.size &&
                data[index] == 0.toByte() &&
                data[index + 1] == 0.toByte() &&
                data[index + 2] == 0.toByte() &&
                data[index + 3] == 1.toByte() -> {
                starts += index to 4
                index += 4
            }
            data[index] == 0.toByte() &&
                data[index + 1] == 0.toByte() &&
                data[index + 2] == 1.toByte() -> {
                starts += index to 3
                index += 3
            }
            else -> index++
        }
    }
    return starts
}

internal fun firstMbInSlice(nal: ByteArray): Int? {
    if (nal.size < 2) return null
    val type = nal[0].toInt() and 0x1F
    if (type != 1 && type != 5) return null
    val rbsp = ByteArrayOutputStream(minOf(nal.size, 32))
    var zeros = 0
    for (index in 1 until minOf(nal.size, 32)) {
        val value = nal[index].toInt() and 0xFF
        if (zeros >= 2 && value == 3) {
            zeros = 0
            continue
        }
        rbsp.write(value)
        zeros = if (value == 0) zeros + 1 else 0
    }
    val data = rbsp.toByteArray()
    var bit = 0
    fun readBit(): Int {
        require(bit < data.size * 8) { "bitstream ended" }
        val value = (data[bit / 8].toInt() ushr (7 - bit % 8)) and 1
        bit++
        return value
    }
    return runCatching {
        var zeroCount = 0
        while (readBit() == 0) {
            zeroCount++
            require(zeroCount < 32)
        }
        var value = 1
        repeat(zeroCount) { value = (value shl 1) or readBit() }
        value - 1
    }.getOrNull()
}
