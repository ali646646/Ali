package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.AlarmPreferencesRepository
import com.example.data.BatteryStateObserver
import com.example.model.AlarmSessionStatus
import com.example.service.BatteryMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * مدیریت منطقی وضعیت هشدار پس از ری‌استارت شدن گوشی (BOOT_COMPLETED):
 * - اگر هشدار قبل از خاموش شدن گوشی فعال بوده، گوشی همچنان به شارژر متصل است و باتری هنوز زیر درصد هدف است، پایش را بازیابی می‌کند.
 * - در غیر این صورت (مثلاً شارژر جدا شده یا باتری از درصد هدف عبور کرده)، وضعیت فعال را به حالت آماده (IDLE) برمی‌گرداند.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = AlarmPreferencesRepository(appContext)
                val prefs = repository.readCurrentPreferences()

                if (prefs.sessionStatus == AlarmSessionStatus.ACTIVE) {
                    val snapshot = BatteryStateObserver.readCurrentSnapshot(appContext)
                    if (snapshot.isChargerConnected && snapshot.percentage < prefs.targetPercentage) {
                        runCatching {
                            BatteryMonitorService.start(appContext, prefs.targetPercentage)
                        }.onFailure {
                            repository.deactivateMonitoring()
                        }
                    } else {
                        repository.deactivateMonitoring()
                    }
                } else if (prefs.sessionStatus == AlarmSessionStatus.COMPLETED) {
                    val snapshot = BatteryStateObserver.readCurrentSnapshot(appContext)
                    if (!snapshot.isChargerConnected) {
                        repository.clearCompletedOnUnplug()
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
