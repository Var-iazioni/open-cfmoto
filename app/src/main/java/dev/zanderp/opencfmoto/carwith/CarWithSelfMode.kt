// SPDX-License-Identifier: AGPL-3.0-or-later
// Adapted from mcarplay (https://github.com/wangxso/mcarplay, MIT License).
// Copyright (C) wangxso / mcarplay contributors, OpenCfMoto contributors.
package dev.zanderp.opencfmoto.carwith

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process

/**
 * Triggers Xiaomi CarWith's local CarLife bridge without root.
 *
 * CarWith 2.3.1+ exports [CARWITH_ENTRY] (`CarlifeAdbActivity`) which starts the seven local
 * loopback CarLife listeners (ports 7240/8240/9240-9242/9340/9440) on 127.0.0.1.
 */
object CarWithSelfMode {
    const val CARWITH_PACKAGE = "com.miui.carlink"
    const val CARLIFE_PACKAGE = "com.baidu.carlife.xiaomi"
    const val CARWITH_ENTRY = "com.miui.carlink.castfwk.usb.CarlifeAdbActivity"
    const val CARWITH_SETTINGS = "com.carwith.launcher.settings.phone.UCarScreenSettingsActivity"

    private const val MIUI_OP_BACKGROUND_START_ACTIVITY = 10021

    enum class EntryResult {
        REQUESTED,
        MISSING,
        DISABLED,
        NOT_EXPORTED,
        PERMISSION_DENIED,
        FAILED,
    }

    fun isAvailable(context: Context): Boolean =
        runCatching {
            context.packageManager.getApplicationInfo(CARWITH_PACKAGE, 0)
            true
        }.getOrDefault(false)

    fun isCarLifeComponentAvailable(context: Context): Boolean =
        runCatching {
            context.packageManager.getApplicationInfo(CARLIFE_PACKAGE, 0)
            true
        }.getOrDefault(false)

    fun trigger(context: Context, log: (String) -> Unit): EntryResult {
        if (!isAvailable(context)) {
            log("[CARWITH] CarWith ($CARWITH_PACKAGE) not installed")
            return EntryResult.MISSING
        }
        if (!isCarLifeComponentAvailable(context)) {
            log("[CARWITH] note: $CARLIFE_PACKAGE not found — proceeding with $CARWITH_PACKAGE")
        }
        val inBg = !isAppInForeground()
        val bgAllowed = backgroundStartAllowed(context)
        if (inBg && bgAllowed == false) {
            log("[CARWITH] WARNING: MIUI 'Display pop-up windows while running in the background' is denied — CarWith entry may be silently dropped")
        }
        val res = launchActivity(context, CARWITH_PACKAGE, CARWITH_ENTRY, log)
        if (res == EntryResult.REQUESTED) {
            log("[CARWITH] launched public entry ($CARWITH_ENTRY) inBg=$inBg")
            return res
        }
        log("[CARWITH] public entry result=$res, trying CarWith settings fallback")
        return launchActivity(context, CARWITH_PACKAGE, CARWITH_SETTINGS, log)
    }

    private fun launchActivity(
        context: Context,
        pkg: String,
        cls: String,
        log: (String) -> Unit,
    ): EntryResult =
        try {
            val component = ComponentName(pkg, cls)
            val info = context.packageManager.getActivityInfo(component, 0)
            when {
                !info.enabled -> EntryResult.DISABLED
                !info.exported -> EntryResult.NOT_EXPORTED
                else -> {
                    context.startActivity(
                        Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    EntryResult.REQUESTED
                }
            }
        } catch (e: Exception) {
            log("[CARWITH] launch $cls failed: ${e.javaClass.simpleName}: ${e.message}")
            when (e) {
                is PackageManager.NameNotFoundException, is ActivityNotFoundException -> EntryResult.MISSING
                is SecurityException -> EntryResult.PERMISSION_DENIED
                else -> EntryResult.FAILED
            }
        }

    private fun isAppInForeground(): Boolean =
        runCatching {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        }.getOrDefault(true)

    private fun backgroundStartAllowed(context: Context): Boolean? =
        runCatching {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return@runCatching null
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            val mode = method.invoke(appOps, MIUI_OP_BACKGROUND_START_ACTIVITY, Process.myUid(), context.packageName) as Int
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> true
                AppOpsManager.MODE_IGNORED -> false
                else -> null
            }
        }.getOrNull()
}
