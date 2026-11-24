package com.example.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

sealed interface EstimatedTimeState {
    data object Hidden : EstimatedTimeState
    data object Calculating : EstimatedTimeState
    data class Ready(val totalMinutes: Int) : EstimatedTimeState
}

data class HardwareBatteryMetrics(
    val systemFullRemainingMs: Long = -1L,
    val chargeCounterRaw: Long = Long.MIN_VALUE,
    val currentAvgRaw: Long = Long.MIN_VALUE,
    val currentNowRaw: Long = Long.MIN_VALUE,
    val maxChargingCurrentUa: Int = -1
)

object ChargeTimeEstimator {

    @Volatile
    var testChargeTimeRemainingProvider: ((Context) -> Long)? = null

    @Volatile
    var testHardwareMetricsProvider: ((Context) -> HardwareBatteryMetrics)? = null

    fun estimate(
        context: Context,
        currentPercentage: Int,
        targetPercentage: Int,
        isChargerConnected: Boolean,
        isAlarmActive: Boolean,
        sessionStartPercentage: Int?,
        sessionStartTimeMs: Long?,
        nowMs: Long = System.currentTimeMillis()
    ): EstimatedTimeState {
        if (!isAlarmActive || !isChargerConnected) {
            return EstimatedTimeState.Hidden
        }
        if (targetPercentage <= currentPercentage) {
            return EstimatedTimeState.Hidden
        }

        val remainingPercent = (targetPercentage - currentPercentage).coerceAtLeast(1)

        // ۱. اگر در همین نشست شارژ حداقل ۲٪ افزایش واقعی مشاهده شده، دقیق‌ترین تخمین از سرعت واقعی خود گوشی است
        if (sessionStartPercentage != null &&
            sessionStartTimeMs != null &&
            currentPercentage - sessionStartPercentage >= 2 &&
            nowMs > sessionStartTimeMs
        ) {
            val gained = currentPercentage - sessionStartPercentage
            val elapsedMs = nowMs - sessionStartTimeMs
            val msPerPct = elapsedMs.toDouble() / gained.toDouble()
            val estMs = (msPerPct * remainingPercent).roundToLong()
            val mins = ceil(estMs / 60_000.0).toInt()
            if (mins in 1..1440) {
                return EstimatedTimeState.Ready(mins)
            }
        }

        val metrics = readHardwareMetrics(context)

        // ۲. تخمین رسمی سیستم‌عامل اندروید ۹ به بالا (BatteryManager.computeChargeTimeRemaining)
        if (metrics.systemFullRemainingMs > 0L && currentPercentage < 100) {
            val totalToFull = (100 - currentPercentage).coerceAtLeast(1)
            val scaledMs =
                ((metrics.systemFullRemainingMs.toDouble() * remainingPercent.toDouble()) / totalToFull.toDouble()).roundToLong()
            val mins = ceil(scaledMs / 60_000.0).toInt().coerceAtLeast(1)
            if (mins in 1..1440) {
                return EstimatedTimeState.Ready(mins)
            }
        }

        // ۳. محاسبه از روی جریان شارژ و ظرفیت باتری (پشتیبانی از هر دو واحد میکروآمپر و میلی‌آمپر روی رام‌های مختلف)
        val effectiveCurrentMa = resolveChargingCurrentMa(metrics)
        if (effectiveCurrentMa != null && currentPercentage in 1..99) {
            val totalCapacityMah = resolveTotalCapacityMah(
                context = context,
                chargeCounterRaw = metrics.chargeCounterRaw,
                currentPercentage = currentPercentage
            ) ?: DEFAULT_NOMINAL_CAPACITY_MAH

            val neededMah = totalCapacityMah * (remainingPercent.toDouble() / 100.0)
            val estHours = neededMah / effectiveCurrentMa
            val mins = ceil(estHours * 60.0).toInt()
            if (mins in 1..1440) {
                return EstimatedTimeState.Ready(mins)
            }
        }

        // ۴. اگر حتی ۱٪ افزایش در این نشست ثبت شده باشد، بر اساس همان نرخ اولیه تخمین بزن
        if (sessionStartPercentage != null &&
            sessionStartTimeMs != null &&
            currentPercentage > sessionStartPercentage &&
            nowMs > sessionStartTimeMs
        ) {
            val gained = currentPercentage - sessionStartPercentage
            val elapsedMs = nowMs - sessionStartTimeMs
            val msPerPct = elapsedMs.toDouble() / gained.toDouble()
            val estMs = (msPerPct * remainingPercent).roundToLong()
            val mins = ceil(estMs / 60_000.0).toInt().coerceAtLeast(1)
            if (mins in 1..1440) {
                return EstimatedTimeState.Ready(mins)
            }
        }

        return EstimatedTimeState.Calculating
    }

    private fun readHardwareMetrics(context: Context): HardwareBatteryMetrics {
        testHardwareMetricsProvider?.let { return it.invoke(context) }

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val systemRemainingMs = testChargeTimeRemainingProvider?.invoke(context) ?: run {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { bm?.computeChargeTimeRemaining() ?: -1L }.getOrDefault(-1L)
            } else {
                -1L
            }
        }

        val chargeCounter = runCatching {
            bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Long.MIN_VALUE
        }.getOrDefault(Long.MIN_VALUE)

        val currentAvg = runCatching {
            bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) ?: Long.MIN_VALUE
        }.getOrDefault(Long.MIN_VALUE)

        val currentNow = runCatching {
            bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Long.MIN_VALUE
        }.getOrDefault(Long.MIN_VALUE)

        val stickyIntent = runCatching {
            context.applicationContext.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
        }.getOrNull()
        val maxChargingCurrent = stickyIntent?.getIntExtra("max_charging_current", -1) ?: -1

        return HardwareBatteryMetrics(
            systemFullRemainingMs = systemRemainingMs,
            chargeCounterRaw = chargeCounter,
            currentAvgRaw = currentAvg,
            currentNowRaw = currentNow,
            maxChargingCurrentUa = maxChargingCurrent
        )
    }

    internal fun resolveChargingCurrentMa(metrics: HardwareBatteryMetrics): Double? {
        normalizeCurrentToMa(metrics.currentAvgRaw)?.let { return it }
        normalizeCurrentToMa(metrics.currentNowRaw)?.let { return it }

        if (metrics.maxChargingCurrentUa > 0) {
            normalizeCurrentToMa(metrics.maxChargingCurrentUa.toLong())?.let { negotiatedMa ->
                // جریان خالص ورودی به باتری معمولاً حدود ۸۰٪ سقف جریان توافق‌شده با شارژر است
                return (negotiatedMa * 0.80).coerceAtLeast(100.0)
            }
        }
        return null
    }

    internal fun normalizeCurrentToMa(raw: Long): Double? {
        if (raw == Long.MIN_VALUE || raw == Int.MIN_VALUE.toLong() || raw == 0L) {
            return null
        }
        val magnitude = abs(raw)
        return when {
            magnitude in 100_000L..25_000_000L -> magnitude / 1000.0 // میکروآمپر -> میلی‌آمپر
            magnitude in 100L..25_000L -> magnitude.toDouble()       // میلی‌آمپر مستقیم (برخی گوشی‌های شیائومی/سامسونگ/مدیاتک)
            else -> null
        }
    }

    internal fun resolveTotalCapacityMah(
        context: Context,
        chargeCounterRaw: Long,
        currentPercentage: Int
    ): Double? {
        if (chargeCounterRaw != Long.MIN_VALUE &&
            chargeCounterRaw != Int.MIN_VALUE.toLong() &&
            chargeCounterRaw > 0L &&
            currentPercentage in 1..100
        ) {
            val currentRemainingMah = when {
                chargeCounterRaw in 100_000L..20_000_000L -> chargeCounterRaw / 1000.0 // میکروآمپرساعت -> میلی‌آمپرساعت
                chargeCounterRaw in 50L..20_000L -> chargeCounterRaw.toDouble()        // میلی‌آمپرساعت مستقیم
                else -> null
            }
            if (currentRemainingMah != null) {
                val estimatedTotal = (currentRemainingMah * 100.0) / currentPercentage.toDouble()
                if (estimatedTotal in 1_000.0..15_000.0) {
                    return estimatedTotal
                }
            }
        }

        return readPowerProfileCapacityMah(context)
    }

    @SuppressLint("PrivateApi")
    private fun readPowerProfileCapacityMah(context: Context): Double? {
        return runCatching {
            val powerProfileClass = Class.forName("com.android.internal.os.PowerProfile")
            val instance = powerProfileClass
                .getConstructor(Context::class.java)
                .newInstance(context.applicationContext)
            val method = powerProfileClass.getMethod("getBatteryCapacity")
            val capacity = (method.invoke(instance) as? Number)?.toDouble()
            if (capacity != null && capacity in 1_000.0..15_000.0) capacity else null
        }.getOrNull()
    }

    private const val DEFAULT_NOMINAL_CAPACITY_MAH = 4500.0
}
