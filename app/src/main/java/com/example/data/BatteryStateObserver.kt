package com.example.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.example.model.BatteryIntentParser
import com.example.model.LiveBatterySnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

object BatteryStateObserver {

    fun readCurrentSnapshot(context: Context): LiveBatterySnapshot {
        val appContext = context.applicationContext
        val stickyIntent = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val parsed = BatteryIntentParser.parse(stickyIntent)

        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val directCapacity = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val isChargingDirect = bm?.isCharging ?: false
        val isConnected = parsed.isChargerConnected || isChargingDirect

        val accuratePercentage = when {
            isConnected && directCapacity in 1..100 -> maxOf(parsed.percentage, directCapacity)
            parsed.percentage > 0 -> parsed.percentage
            directCapacity in 0..100 -> directCapacity
            else -> 0
        }

        return parsed.copy(
            percentage = accuratePercentage,
            isChargerConnected = isConnected
        )
    }

    fun observeBatteryState(context: Context): Flow<LiveBatterySnapshot> = callbackFlow {
        val appContext = context.applicationContext
        trySend(readCurrentSnapshot(appContext))

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val fromIntent = BatteryIntentParser.parse(intent)
                        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                        val directCap = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                        val bestPct = if (directCap in 1..100) {
                            maxOf(fromIntent.percentage, directCap)
                        } else {
                            fromIntent.percentage
                        }
                        trySend(fromIntent.copy(percentage = bestPct))
                    }
                    Intent.ACTION_POWER_CONNECTED,
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        trySend(readCurrentSnapshot(appContext))
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }

        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        awaitClose {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }.distinctUntilChanged()
}
