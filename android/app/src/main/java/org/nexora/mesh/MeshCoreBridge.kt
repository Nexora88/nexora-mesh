package org.nexora.mesh

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Thin host-side bridge to mesh-core power policy and (later) native relay queue.
 *
 * Current status:
 * - BatteryManager / PowerManager signals are read here and mapped to the same
 *   bit flags used by the Rust `nm_recommended_scan_interval_secs` / `nm_may_forward` C ABI.
 * - Native library is not linked yet. Until the .so is built and loaded, the
 *   pure-Kotlin fallback mirrors the Rust policy so the app can already be tested
 *   for interval selection and UI behaviour.
 *
 * Next step for real FFI:
 * 1. Build mesh-core as a shared library for android targets (cargo-ndk / UniFFI).
 * 2. Load via System.loadLibrary("mesh_core")
 * 3. Replace the fallback functions with external JNI declarations.
 */
object MeshCoreBridge {

    private const val FLAG_CHARGING = 1
    private const val FLAG_LOW_POWER = 2
    private const val FLAG_FOREGROUND = 4
    private const val FLAG_RECENT_CONNECTION = 8
    private const val FLAG_URGENT_PENDING = 16

    data class PowerSnapshot(
        val batteryPercent: Int,
        val isCharging: Boolean,
        val lowPowerMode: Boolean,
        val isForeground: Boolean,
        val recentConnection: Boolean,
        val urgentPending: Boolean,
        val recommendedScanIntervalSecs: Long,
        val mayForward: Boolean
    )

    fun evaluatePower(
        context: Context,
        isForeground: Boolean = true,
        recentConnection: Boolean = false,
        urgentPending: Boolean = false
    ): PowerSnapshot {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        val batteryPercent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .coerceIn(0, 100)

        val batteryStatus: Intent? = ContextCompat.registerReceiver(
            context,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val lowPowerMode = pm.isPowerSaveMode

        var flags = 0
        if (isCharging) flags = flags or FLAG_CHARGING
        if (lowPowerMode) flags = flags or FLAG_LOW_POWER
        if (isForeground) flags = flags or FLAG_FOREGROUND
        if (recentConnection) flags = flags or FLAG_RECENT_CONNECTION
        if (urgentPending) flags = flags or FLAG_URGENT_PENDING

        val interval = recommendedScanIntervalSecsFallback(batteryPercent, flags)
        val mayFwd = mayForwardFallback(batteryPercent, flags)

        return PowerSnapshot(
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            lowPowerMode = lowPowerMode,
            isForeground = isForeground,
            recentConnection = recentConnection,
            urgentPending = urgentPending,
            recommendedScanIntervalSecs = interval,
            mayForward = mayFwd
        )
    }

    private fun recommendedScanIntervalSecsFallback(batteryPercent: Int, flags: Int): Long {
        val charging = flags and FLAG_CHARGING != 0
        val lowPower = flags and FLAG_LOW_POWER != 0
        val foreground = flags and FLAG_FOREGROUND != 0
        val recent = flags and FLAG_RECENT_CONNECTION != 0
        val urgent = flags and FLAG_URGENT_PENDING != 0

        return when {
            urgent && foreground -> 5L
            charging && foreground -> 10L
            recent -> 15L
            lowPower || batteryPercent < 15 -> 120L
            batteryPercent < 30 -> 60L
            foreground -> 20L
            else -> 45L
        }
    }

    private fun mayForwardFallback(batteryPercent: Int, flags: Int): Boolean {
        val charging = flags and FLAG_CHARGING != 0
        val lowPower = flags and FLAG_LOW_POWER != 0
        return !(lowPower && batteryPercent < 10 && !charging)
    }
}
