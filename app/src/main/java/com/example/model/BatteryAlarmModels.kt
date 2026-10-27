package com.example.model

import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import com.example.R

enum class AlarmSessionStatus {
    IDLE,
    ACTIVE,
    COMPLETED
}

enum class AudioStreamPreference {
    ALARM,
    NOTIFICATION
}

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

data class LiveBatterySnapshot(
    val percentage: Int = 0,
    val isChargerConnected: Boolean = false,
    val plugTypeLabelRes: Int = R.string.plug_type_disconnected
)

data class AlarmPreferencesState(
    val targetPercentage: Int = DEFAULT_TARGET_PERCENTAGE,
    val soundUriString: String? = null,
    val soundDisplayName: String? = null,
    val sessionStatus: AlarmSessionStatus = AlarmSessionStatus.IDLE,
    val audioStream: AudioStreamPreference = AudioStreamPreference.ALARM,
    val lastCompletedTarget: Int? = null,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM
) {
    companion object {
        const val DEFAULT_TARGET_PERCENTAGE = 80
        const val MIN_TARGET_PERCENTAGE = 1
        const val MAX_TARGET_PERCENTAGE = 100
    }
}

object BatteryIntentParser {
    fun parse(intent: Intent?): LiveBatterySnapshot {
        if (intent == null) {
            return LiveBatterySnapshot()
        }

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percentage = if (level >= 0 && scale > 0) {
            ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
        } else {
            0
        }

        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val status = intent.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        )

        val isDockPlugged = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            plugged == BatteryManager.BATTERY_PLUGGED_DOCK

        val isPlugged = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
            plugged == BatteryManager.BATTERY_PLUGGED_USB ||
            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ||
            isDockPlugged

        val isChargerConnected = isPlugged || status == BatteryManager.BATTERY_STATUS_CHARGING

        val plugLabelRes = when {
            plugged == BatteryManager.BATTERY_PLUGGED_AC -> R.string.plug_type_ac
            plugged == BatteryManager.BATTERY_PLUGGED_USB -> R.string.plug_type_usb
            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS -> R.string.plug_type_wireless
            isDockPlugged -> R.string.plug_type_dock
            isChargerConnected -> R.string.status_charging
            else -> R.string.plug_type_disconnected
        }

        return LiveBatterySnapshot(
            percentage = percentage,
            isChargerConnected = isChargerConnected,
            plugTypeLabelRes = plugLabelRes
        )
    }
}
