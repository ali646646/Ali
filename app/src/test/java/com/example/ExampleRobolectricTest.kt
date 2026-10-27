package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AlarmAudioPlayer
import com.example.data.AlarmPreferencesRepository
import com.example.model.AlarmSessionStatus
import com.example.model.AppThemeMode
import com.example.service.BatteryMonitorService
import com.example.ui.FeedbackBanner
import com.example.ui.MainViewModel
import com.example.util.PersianFormatters.normalizeDigitsToAscii
import com.example.util.PersianFormatters.toPersianDigits
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var appContext: Application
    private lateinit var repository: AlarmPreferencesRepository
    private lateinit var dataStoreScope: CoroutineScope

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val testDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tempFolder.newFile("test_alarm_prefs.preferences_pb") }
        )
        AlarmPreferencesRepository.overrideDataStoreForTesting = testDataStore
        repository = AlarmPreferencesRepository(testDataStore)
    }

    @After
    fun tearDown() {
        AlarmAudioPlayer.testPlaybackInterceptor = null
        AlarmAudioPlayer.stopAll()
        AlarmPreferencesRepository.overrideDataStoreForTesting = null
        dataStoreScope.cancel()
    }

    @Test
    fun `app name matches Persian title`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("هشدار شارژ باتری", context.getString(R.string.app_name))
    }

    @Test
    fun `persian digit formatting and normalization work accurately`() {
        assertEquals("۸۰", 80.toPersianDigits())
        assertEquals("85", "۸۵٪".normalizeDigitsToAscii())
        assertEquals("100", "١٠٠".normalizeDigitsToAscii())
    }

    @Test
    fun `activation blocked when charger is not connected`() {
        sendStickyBatteryBroadcast(
            level = 55,
            plugged = 0,
            status = BatteryManager.BATTERY_STATUS_DISCHARGING
        )
        val viewModel = MainViewModel(appContext, repository)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val result = viewModel.validateReadyToActivate()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNull(result)
        assertTrue(viewModel.uiState.value.feedbackBanner is FeedbackBanner.ChargerNotConnected)
    }

    @Test
    fun `activation blocked without false alarm when battery already above target`() {
        sendStickyBatteryBroadcast(
            level = 88,
            plugged = BatteryManager.BATTERY_PLUGGED_AC,
            status = BatteryManager.BATTERY_STATUS_CHARGING
        )
        val viewModel = MainViewModel(appContext, repository)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val result = viewModel.validateReadyToActivate()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNull(result)
        assertTrue(viewModel.uiState.value.feedbackBanner is FeedbackBanner.AlreadyAboveTarget)
    }

    @Test
    fun `background service plays alert exactly once at target without launching any activity`(): Unit = runBlocking {
        val playCount = AtomicInteger(0)
        var completionTrigger: (() -> Unit)? = null

        AlarmAudioPlayer.testPlaybackInterceptor = { _, _, isTest, onDone ->
            if (!isTest) {
                playCount.incrementAndGet()
            }
            completionTrigger = onDone
            true
        }

        sendStickyBatteryBroadcast(
            level = 74,
            plugged = BatteryManager.BATTERY_PLUGGED_AC,
            status = BatteryManager.BATTERY_STATUS_CHARGING
        )
        repository.setTargetPercentage(80)
        repository.activateMonitoring()

        val startIntent = Intent(appContext, BatteryMonitorService::class.java).apply {
            action = BatteryMonitorService.ACTION_START_MONITORING
            putExtra(BatteryMonitorService.EXTRA_TARGET_PERCENTAGE, 80)
        }
        val controller = Robolectric.buildService(BatteryMonitorService::class.java, startIntent)
            .create()
            .startCommand(0, 1)
        idleAndWait(150)

        assertEquals(0, playCount.get())

        // رسیدن به ۷۹٪ -> هنوز نباید هشداری پخش شود
        sendStickyBatteryBroadcast(
            level = 79,
            plugged = BatteryManager.BATTERY_PLUGGED_AC,
            status = BatteryManager.BATTERY_STATUS_CHARGING
        )
        idleAndWait(150)
        assertEquals(0, playCount.get())

        Shadows.shadowOf(appContext).clearNextStartedActivities()

        // رسیدن به ۸۰٪ -> فقط یک‌بار صدا پخش شود و هیچ Activity اجرا نشود
        sendStickyBatteryBroadcast(
            level = 80,
            plugged = BatteryManager.BATTERY_PLUGGED_AC,
            status = BatteryManager.BATTERY_STATUS_CHARGING
        )
        idleAndWait(200)

        assertEquals(1, playCount.get())
        assertEquals(AlarmSessionStatus.COMPLETED, repository.readCurrentPreferences().sessionStatus)
        assertNull(Shadows.shadowOf(appContext).nextStartedActivity)

        // ارسال مجدد ۸۰٪ و ۸۲٪ -> نباید هشدار تکراری پخش شود
        sendStickyBatteryBroadcast(
            level = 80,
            plugged = BatteryManager.BATTERY_PLUGGED_AC,
            status = BatteryManager.BATTERY_STATUS_CHARGING
        )
        sendStickyBatteryBroadcast(
            level = 82,
            plugged = BatteryManager.BATTERY_PLUGGED_AC,
            status = BatteryManager.BATTERY_STATUS_CHARGING
        )
        idleAndWait(150)

        assertEquals(1, playCount.get())

        completionTrigger?.invoke()
        idleAndWait(100)
        controller.destroy()
    }

    @Test
    fun `unplugging charger before target cancels monitoring without playing sound`(): Unit = runBlocking {
        val playCount = AtomicInteger(0)
        AlarmAudioPlayer.testPlaybackInterceptor = { _, _, _, onDone ->
            playCount.incrementAndGet()
            onDone()
            true
        }

        sendStickyBatteryBroadcast(
            level = 72,
            plugged = BatteryManager.BATTERY_PLUGGED_AC,
            status = BatteryManager.BATTERY_STATUS_CHARGING
        )
        repository.setTargetPercentage(80)
        repository.activateMonitoring()

        val startIntent = Intent(appContext, BatteryMonitorService::class.java).apply {
            action = BatteryMonitorService.ACTION_START_MONITORING
            putExtra(BatteryMonitorService.EXTRA_TARGET_PERCENTAGE, 80)
        }
        val controller = Robolectric.buildService(BatteryMonitorService::class.java, startIntent)
            .create()
            .startCommand(0, 1)
        idleAndWait(150)

        // جدا شدن شارژر در ۷۵٪ قبل از رسیدن به ۸۰٪
        sendStickyBatteryBroadcast(
            level = 75,
            plugged = 0,
            status = BatteryManager.BATTERY_STATUS_DISCHARGING
        )
        appContext.sendBroadcast(Intent(Intent.ACTION_POWER_DISCONNECTED))
        idleAndWait(200)

        assertEquals(0, playCount.get())
        assertEquals(AlarmSessionStatus.IDLE, repository.readCurrentPreferences().sessionStatus)
        controller.destroy()
    }

    @Test
    fun `theme mode selection persists light and dark preferences`(): Unit = runBlocking {
        val viewModel = MainViewModel(appContext, repository)
        idleAndWait(100)
        assertEquals(AppThemeMode.SYSTEM, viewModel.uiState.value.themeMode)

        viewModel.onThemeModeSelected(AppThemeMode.DARK)
        idleAndWait(150)
        assertEquals(AppThemeMode.DARK, viewModel.uiState.value.themeMode)
        assertEquals(AppThemeMode.DARK, repository.readCurrentPreferences().themeMode)

        viewModel.onThemeModeSelected(AppThemeMode.LIGHT)
        idleAndWait(150)
        assertEquals(AppThemeMode.LIGHT, viewModel.uiState.value.themeMode)
        assertEquals(AppThemeMode.LIGHT, repository.readCurrentPreferences().themeMode)
    }

    private fun idleAndWait(ms: Long) {
        val steps = 10
        val stepSleep = (ms / steps).coerceAtLeast(15L)
        repeat(steps) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(stepSleep)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun sendStickyBatteryBroadcast(level: Int, plugged: Int, status: Int) {
        val batteryIntent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, level)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
            putExtra(BatteryManager.EXTRA_STATUS, status)
        }
        @Suppress("DEPRECATION")
        appContext.sendStickyBroadcast(batteryIntent)
    }
}
