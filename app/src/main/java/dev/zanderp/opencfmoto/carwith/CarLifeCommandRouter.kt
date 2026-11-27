// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from mcarplay (https://github.com/wangxso/mcarplay, MIT License).
// Copyright (C) wangxso / mcarplay contributors, OpenCfMoto contributors.
package dev.zanderp.opencfmoto.carwith

/** Pure protocol routing for CarLife command-channel frames. */
class CarLifeCommandRouter(
    private val stateMachine: CarLifeStateMachine,
) {
    data class Result(
        val actions: List<CarLifeStateMachine.Action> = emptyList(),
        val log: String? = null,
        val failed: Boolean = false,
        val encryptionReady: Boolean = false,
        val returnToCar: Boolean = false,
    )

    private val unknownKindCounts = HashMap<Int, Int>()

    fun route(frame: CarLifeFrame): Result {
        if (frame.channel != CarLifeProtocol.PORT_COMMAND) {
            return Result(failed = true)
        }
        val decoded =
            try {
                val cipher = stateMachine.payloadCipher
                if (cipher == null || frame.payload.isEmpty()) frame else frame.copy(payload = cipher.decrypt(frame.payload))
            } catch (_: Exception) {
                stateMachine.fail()
                return Result(log = "[CARWITH] command channel decrypt failed", failed = true)
            }
        return routeDecoded(decoded)
    }

    private fun routeDecoded(frame: CarLifeFrame): Result =
        when (frame.kind) {
            CarLifeProtocol.MD_PROTOCOL_MATCH ->
                result(stateMachine.onProtocolMatched())

            CarLifeProtocol.MD_FOREGROUND -> {
                val foreground = stateMachine.onForeground()
                Result(
                    actions = foreground.actions,
                    log = if (foreground.changed) "[CARWITH] foreground requested -> GO_TO_FOREGROUND" else null,
                    failed = stateMachine.state == CarLifeStateMachine.State.FAILED,
                )
            }

            CarLifeProtocol.MD_RETURN_TO_CAR ->
                Result(log = "[CARWITH] user tapped return-to-car (0x10021)", returnToCar = true)

            CarLifeProtocol.MD_INFO -> {
                val actions = stateMachine.onInfo(frame.payload)
                Result(
                    actions = actions,
                    log =
                        stateMachine.deviceInfo?.let {
                            "[CARWITH] MD_INFO brand=${it.brand} model=${it.model} sdk=${it.sdk}"
                        },
                    failed = stateMachine.state == CarLifeStateMachine.State.FAILED,
                )
            }

            CarLifeProtocol.MD_AUTH_RESPONSE ->
                result(stateMachine.onAuthResponse(frame.payload))

            CarLifeProtocol.MD_AUTH_RESULT -> {
                val actions = stateMachine.onPeerAuthResult(frame.payload)
                val failed = stateMachine.state == CarLifeStateMachine.State.FAILED
                Result(
                    actions = actions,
                    log = "[CARWITH] peer auth result: ${if (failed) "rejected/invalid" else "accepted"}",
                    failed = failed,
                    encryptionReady = stateMachine.canInitializeVideo,
                )
            }

            CarLifeProtocol.MD_FEATURE_REQUEST ->
                result(stateMachine.onFeatureRequest())

            CarLifeProtocol.MD_RSA_KEY_REQUEST ->
                Result(
                    actions = stateMachine.onRsaKeyRequest(),
                    log = "[CARWITH] RSA public key requested",
                    failed = stateMachine.state == CarLifeStateMachine.State.FAILED,
                )

            CarLifeProtocol.MD_AES_SEED ->
                Result(
                    actions = stateMachine.onEncryptedSeed(frame.payload),
                    log = "[CARWITH] encrypted AES seed received",
                    failed = stateMachine.state == CarLifeStateMachine.State.FAILED,
                )

            CarLifeProtocol.MD_ENCRYPT_READY -> {
                val actions = stateMachine.onEncryptReady(frame.payload)
                Result(
                    actions = actions,
                    log = "[CARWITH] ENCRYPT_READY received",
                    failed = stateMachine.state == CarLifeStateMachine.State.FAILED,
                    encryptionReady = stateMachine.canInitializeVideo,
                )
            }

            CarLifeProtocol.VIDEO_ENCODER_INIT_DONE -> {
                val format = CarLifeProtocol.parseVideoFormat(frame.payload)
                if (format == null) {
                    stateMachine.fail()
                    Result(failed = true)
                } else {
                    Result(
                        actions = stateMachine.onVideoInitDone(frame.payload),
                        log = "[CARWITH] VIDEO_INIT_DONE ${format.width}x${format.height}@${format.fps}",
                        failed = stateMachine.state == CarLifeStateMachine.State.FAILED,
                    )
                }
            }

            else -> Result(log = unknownCommandLog(frame))
        }

    private fun result(actions: List<CarLifeStateMachine.Action>): Result =
        Result(
            actions = actions,
            failed = stateMachine.state == CarLifeStateMachine.State.FAILED,
        )

    private fun unknownCommandLog(frame: CarLifeFrame): String? {
        val count = unknownKindCounts[frame.kind]?.plus(1) ?: if (unknownKindCounts.size < MAX_TRACKED_UNKNOWN_KINDS) 1 else return null
        unknownKindCounts[frame.kind] = count
        if (count > UNKNOWN_LOG_FIRST && count and (count - 1) != 0) return null
        val kind = "0x" + frame.kind.toUInt().toString(16).uppercase()
        return "[CARWITH] unhandled cmd kind=$kind len=${frame.payload.size} (#$count)"
    }

    companion object {
        private const val UNKNOWN_LOG_FIRST = 3
        private const val MAX_TRACKED_UNKNOWN_KINDS = 64
    }
}
