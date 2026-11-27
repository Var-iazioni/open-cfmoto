// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from mcarplay (https://github.com/wangxso/mcarplay, MIT License).
// Copyright (C) wangxso / mcarplay contributors, OpenCfMoto contributors.
package dev.zanderp.opencfmoto.carwith

enum class CarLifeTouchAction {
    DOWN,
    MOVE,
    UP,
}

/**
 * CarLife accepts one strict touch gesture: DOWN, zero or more MOVEs, then UP.
 */
class CarLifeTouchState {
    private var active = false
    private var lastX = 0
    private var lastY = 0

    @Synchronized
    fun onContact(
        x: Int,
        y: Int,
        down: Boolean,
        width: Int,
        height: Int,
    ): CarLifeTouchAction? {
        if (width <= 0 || height <= 0 || x !in 0 until width || y !in 0 until height) return null
        val action =
            when {
                down && !active -> CarLifeTouchAction.DOWN
                down -> CarLifeTouchAction.MOVE
                active -> CarLifeTouchAction.UP
                else -> return null
            }
        lastX = x
        lastY = y
        active = action != CarLifeTouchAction.UP
        return action
    }

    @Synchronized
    fun releasePoint(): Pair<Int, Int>? {
        if (!active) return null
        active = false
        return lastX to lastY
    }

    @Synchronized
    fun reset() {
        active = false
    }
}
