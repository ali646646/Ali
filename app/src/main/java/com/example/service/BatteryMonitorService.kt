package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.audio.AlarmAudioPlayer
import com.example.data.AlarmPreferencesRepository
import com.example.data.BatteryStateObserver
import com.example.model.AlarmPreferencesState
import com.example.model.AlarmSessionStatus
import com.example.model.BatteryIntentParser
import com.example.model.LiveBatterySnapshot
import com.example.util.PersianFormatters.toPersianDigits
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * سرویس پایش باتری در پس‌زمینه:
 * ۱. دریافت آنی تغییرات باتری از طریق BroadcastReceiver سیستمی.
 * ۲. بررسی پشتیبان سبک هر ۲۵ ثانیه از طریق BatteryManager سخت‌افزاری برای گوشی‌هایی که در حالت صفحه خاموش ارسال Broadcast را محدود می‌کنند.
 * ۳. بدون روشن کردن صفحه‌نمایش، بدون باز کردن هیچ Activity و با پخش دقیقاً یک‌باره صدای هشدار.
 */
class BatteryMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val evaluationMutex = Mutex()
    private val hasTriggeredInThisSession = AtomicBoolean(false)

    private lateinit var repository: AlarmPreferencesRepository
    private var isReceiverRegistered = false
    private var slowSafetyCheckJob: Job? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val parsed = BatteryIntentParser.parse(intent)
                    val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val directCap = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                    val bestPct = if (directCap in 1..100) {
                        maxOf(parsed.percentage, directCap)
                    } else {
                        parsed.percentage
                    }
                    handleBatterySnapshotAsync(parsed.copy(percentage = bestPct))
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    val snapshot = LiveBatterySnapshot(
                        percentage = BatteryStateObserver.readCurrentSnapshot(this@BatteryMonitorService).percentage,
                        isChargerConnected = false
                    )
                    handleBatterySnapshotAsync(snapshot)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = AlarmPreferencesRepository(applicationContext)
        ensureSilentNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MONITORING) {
            AlarmAudioPlayer.stopAll()
            serviceScope.launch {
                repository.deactivateMonitoring()
                stopServiceCleanly()
            }
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START_MONITORING) {
            hasTriggeredInThisSession.set(false)
            AlarmAudioPlayer.stopTestOnly()
        }

        val initialTarget = intent?.getIntExtra(
            EXTRA_TARGET_PERCENTAGE,
            AlarmPreferencesState.DEFAULT_TARGET_PERCENTAGE
        ) ?: AlarmPreferencesState.DEFAULT_TARGET_PERCENTAGE

        promoteToSilentForeground(initialTarget, includeMediaPlayback = false)
        registerBatteryReceiverIfNeeded()
        startSlowHardwareSafetyCheckIfNeeded()

        serviceScope.launch {
            val prefs = repository.readCurrentPreferences()
            if (prefs.sessionStatus != AlarmSessionStatus.ACTIVE && !AlarmAudioPlayer.isAlarmPlaying.value) {
                stopServiceCleanly()
                return@launch
            }
            val currentSnapshot = BatteryStateObserver.readCurrentSnapshot(applicationContext)
            evaluateSnapshotSequential(currentSnapshot)
        }

        return START_STICKY
    }

    /**
     * بررسی پشتیبان بسیار کم‌مصرف (هر ۲۵ ثانیه یک‌بار، بدون Polling سریع و بدون روشن کردن صفحه)
     * مخصوص رابط‌های کاربری سفارشی (مانند MIUI/HyperOS و One UI) که هنگام خاموش بودن صفحه ACTION_BATTERY_CHANGED را به تأخیر می‌اندازند.
     */
    private fun startSlowHardwareSafetyCheckIfNeeded() {
        if (slowSafetyCheckJob?.isActive == true) return
        slowSafetyCheckJob = serviceScope.launch {
            while (isActive) {
                delay(SLOW_SAFETY_CHECK_INTERVAL_MS)
                if (hasTriggeredInThisSession.get()) break
                val liveSnapshot = BatteryStateObserver.readCurrentSnapshot(applicationContext)
                evaluateSnapshotSequential(liveSnapshot)
            }
        }
    }

    private fun handleBatterySnapshotAsync(snapshot: LiveBatterySnapshot) {
        serviceScope.launch {
            evaluateSnapshotSequential(snapshot)
        }
    }

    private suspend fun evaluateSnapshotSequential(snapshot: LiveBatterySnapshot) {
        evaluationMutex.withLock {
            val prefs = repository.readCurrentPreferences()

            if (!snapshot.isChargerConnected) {
                if (AlarmAudioPlayer.isAlarmPlaying.value) {
                    AlarmAudioPlayer.stopAll()
                }
                when (prefs.sessionStatus) {
                    AlarmSessionStatus.ACTIVE -> repository.deactivateMonitoring()
                    AlarmSessionStatus.COMPLETED -> repository.clearCompletedOnUnplug()
                    AlarmSessionStatus.IDLE -> Unit
                }
                stopServiceCleanly()
                return
            }

            if (prefs.sessionStatus != AlarmSessionStatus.ACTIVE) {
                if (!AlarmAudioPlayer.isAlarmPlaying.value) {
                    stopServiceCleanly()
                }
                return
            }

            if (snapshot.percentage >= prefs.targetPercentage) {
                if (hasTriggeredInThisSession.compareAndSet(false, true)) {
                    slowSafetyCheckJob?.cancel()
                    repository.markCompleted(prefs.targetPercentage)

                    // ارتقای موقت نوع سرویس به mediaPlayback در اندروید ۱۴+ تا سیستم صوتی گوشی در پس‌زمینه صدا را مسدود نکند (با همان اعلان بی‌صدا و بدون روشن شدن صفحه)
                    runCatching {
                        promoteToSilentForeground(
                            targetPercentage = prefs.targetPercentage,
                            includeMediaPlayback = true
                        )
                    }

                    val started = AlarmAudioPlayer.playAlarmOnce(
                        context = applicationContext,
                        savedUriString = prefs.soundUriString,
                        streamPreference = prefs.audioStream,
                        onFinished = {
                            serviceScope.launch {
                                stopServiceCleanly()
                            }
                        }
                    )
                    if (!started) {
                        stopServiceCleanly()
                    }
                }
            }
        }
    }

    private fun registerBatteryReceiverIfNeeded() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        isReceiverRegistered = true
    }

    private fun unregisterBatteryReceiverIfNeeded() {
        if (!isReceiverRegistered) return
        runCatching { unregisterReceiver(batteryReceiver) }
        isReceiverRegistered = false
    }

    private fun promoteToSilentForeground(
        targetPercentage: Int,
        includeMediaPlayback: Boolean
    ) {
        val notification = buildSilentOngoingNotification(targetPercentage)
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (includeMediaPlayback) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
    }

    private fun ensureSilentNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildSilentOngoingNotification(targetPercentage: Int): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentPendingIntent = PendingIntent.getActivity(this, 100, openAppIntent, pendingFlags)

        val stopIntent = Intent(this, BatteryMonitorService::class.java).apply {
            action = ACTION_STOP_MONITORING
        }
        val stopPendingIntent = PendingIntent.getService(this, 101, stopIntent, pendingFlags)

        val formattedTarget = targetPercentage.toPersianDigits()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_battery_alarm)
            .setContentTitle(getString(R.string.notif_monitoring_title))
            .setContentText(getString(R.string.notif_monitoring_body, formattedTarget))
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(
                0,
                getString(R.string.notif_action_stop),
                stopPendingIntent
            )
            .build()
    }

    private fun stopServiceCleanly() {
        slowSafetyCheckJob?.cancel()
        slowSafetyCheckJob = null
        unregisterBatteryReceiverIfNeeded()
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    override fun onTimeout(startId: Int) {
        stopServiceCleanly()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopServiceCleanly()
    }

    override fun onDestroy() {
        slowSafetyCheckJob?.cancel()
        unregisterBatteryReceiverIfNeeded()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "battery_monitor_silent_channel"
        const val NOTIFICATION_ID = 4080
        private const val SLOW_SAFETY_CHECK_INTERVAL_MS = 25_000L

        const val ACTION_START_MONITORING = "com.example.action.START_BATTERY_MONITORING"
        const val ACTION_STOP_MONITORING = "com.example.action.STOP_BATTERY_MONITORING"
        const val EXTRA_TARGET_PERCENTAGE = "extra_target_percentage"

        fun start(context: Context, targetPercentage: Int) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, BatteryMonitorService::class.java).apply {
                action = ACTION_START_MONITORING
                putExtra(EXTRA_TARGET_PERCENTAGE, targetPercentage)
            }
            ContextCompat.startForegroundService(appContext, intent)
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            AlarmAudioPlayer.stopAll()
            val intent = Intent(appContext, BatteryMonitorService::class.java).apply {
                action = ACTION_STOP_MONITORING
            }
            runCatching { appContext.startService(intent) }
                .onFailure { appContext.stopService(Intent(appContext, BatteryMonitorService::class.java)) }
        }
    }
}
