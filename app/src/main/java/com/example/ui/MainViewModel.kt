package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.audio.AlarmAudioPlayer
import com.example.audio.SoundResolver
import com.example.data.AlarmPreferencesRepository
import com.example.data.BatteryStateObserver
import com.example.model.AlarmPreferencesState
import com.example.model.AlarmSessionStatus
import com.example.model.AppThemeMode
import com.example.model.AudioStreamPreference
import com.example.model.LiveBatterySnapshot
import com.example.service.BatteryMonitorService
import com.example.util.PersianFormatters.normalizeDigitsToAscii
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface FeedbackBanner {
    data object ChargerNotConnected : FeedbackBanner
    data class AlreadyAboveTarget(val current: Int, val target: Int) : FeedbackBanner
    data class AlreadyAtTarget(val target: Int) : FeedbackBanner
    data object InvalidTargetInput : FeedbackBanner
    data class AlarmActivated(val target: Int) : FeedbackBanner
    data object AlarmDeactivated : FeedbackBanner
    data object NotificationPermissionNote : FeedbackBanner
}

data class BatteryAlarmUiState(
    val liveBattery: LiveBatterySnapshot = LiveBatterySnapshot(),
    val targetPercentage: Int = AlarmPreferencesState.DEFAULT_TARGET_PERCENTAGE,
    val targetInputText: String = AlarmPreferencesState.DEFAULT_TARGET_PERCENTAGE.toString(),
    val isTargetInputInvalid: Boolean = false,
    val selectedSoundUriString: String? = null,
    val selectedSoundName: String = "",
    val sessionStatus: AlarmSessionStatus = AlarmSessionStatus.IDLE,
    val audioStream: AudioStreamPreference = AudioStreamPreference.ALARM,
    val lastCompletedTarget: Int? = null,
    val isTestSoundPlaying: Boolean = false,
    val isAlarmSoundPlaying: Boolean = false,
    val isStreamMutedWarning: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = true,
    val feedbackBanner: FeedbackBanner? = null,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM
)

class MainViewModel(
    application: Application,
    private val repository: AlarmPreferencesRepository = AlarmPreferencesRepository(application)
) : AndroidViewModel(application) {

    private val appContext: Context
        get() = getApplication<Application>().applicationContext

    private val targetInputDraft = MutableStateFlow<String?>(null)
    private val isInputInvalidFlow = MutableStateFlow(false)
    private val feedbackBannerFlow = MutableStateFlow<FeedbackBanner?>(null)
    private val batteryOptIgnoredFlow = MutableStateFlow(checkIgnoringBatteryOptimizations())
    private val streamMutedFlow = MutableStateFlow(false)

    init {
        refreshSystemChecks()
        // همگام‌سازی منطقی وضعیت ذخیره شده با وضعیت واقعی شارژر هنگام باز شدن برنامه
        viewModelScope.launch {
            combine(
                BatteryStateObserver.observeBatteryState(appContext),
                repository.preferencesFlow
                    .map { it.sessionStatus to it.targetPercentage }
                    .distinctUntilChanged()
            ) { battery, (status, target) ->
                Triple(battery, status, target)
            }.collect { (battery, status, target) ->
                if (!battery.isChargerConnected) {
                    if (status == AlarmSessionStatus.ACTIVE) {
                        repository.deactivateMonitoring()
                    } else if (status == AlarmSessionStatus.COMPLETED) {
                        repository.clearCompletedOnUnplug()
                    }
                } else if (status == AlarmSessionStatus.ACTIVE && battery.percentage < target) {
                    runCatching { BatteryMonitorService.start(appContext, target) }
                }
            }
        }
    }

    private val coreCombinedFlow = combine(
        BatteryStateObserver.observeBatteryState(appContext),
        repository.preferencesFlow,
        AlarmAudioPlayer.isTestPlaying,
        AlarmAudioPlayer.isAlarmPlaying
    ) { liveBattery, prefs, isTestPlaying, isAlarmPlaying ->
        CoreSlice(liveBattery, prefs, isTestPlaying, isAlarmPlaying)
    }

    private val uiExtrasFlow = combine(
        targetInputDraft,
        isInputInvalidFlow,
        feedbackBannerFlow,
        batteryOptIgnoredFlow,
        streamMutedFlow
    ) { draft, isInvalid, banner, isBatteryOptIgnored, isMuted ->
        ExtrasSlice(draft, isInvalid, banner, isBatteryOptIgnored, isMuted)
    }

    val uiState: StateFlow<BatteryAlarmUiState> = combine(
        coreCombinedFlow,
        uiExtrasFlow
    ) { core, extras ->
        val effectiveSoundName = core.prefs.soundDisplayName?.takeIf { it.isNotBlank() }
            ?: run {
                val uri = SoundResolver.resolveEffectiveUri(core.prefs.soundUriString)
                if (core.prefs.soundUriString.isNullOrBlank()) {
                    appContext.getString(R.string.default_system_alarm_name)
                } else {
                    SoundResolver.resolveDisplayName(appContext, uri)
                }
            }

        val mutedNow = SoundResolver.isStreamMutedOrBlockedByDnd(
            appContext,
            core.prefs.audioStream
        )

        BatteryAlarmUiState(
            liveBattery = core.liveBattery,
            targetPercentage = core.prefs.targetPercentage,
            targetInputText = extras.draft ?: core.prefs.targetPercentage.toString(),
            isTargetInputInvalid = extras.isInvalid,
            selectedSoundUriString = core.prefs.soundUriString,
            selectedSoundName = effectiveSoundName,
            sessionStatus = core.prefs.sessionStatus,
            audioStream = core.prefs.audioStream,
            lastCompletedTarget = core.prefs.lastCompletedTarget,
            isTestSoundPlaying = core.isTestPlaying,
            isAlarmSoundPlaying = core.isAlarmPlaying,
            isStreamMutedWarning = extras.isMuted || mutedNow,
            isIgnoringBatteryOptimizations = extras.isBatteryOptIgnored,
            feedbackBanner = extras.banner,
            themeMode = core.prefs.themeMode
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = BatteryAlarmUiState(
            liveBattery = BatteryStateObserver.readCurrentSnapshot(appContext),
            selectedSoundName = appContext.getString(R.string.default_system_alarm_name),
            isIgnoringBatteryOptimizations = checkIgnoringBatteryOptimizations()
        )
    )

    fun refreshSystemChecks() {
        batteryOptIgnoredFlow.value = checkIgnoringBatteryOptimizations()
        viewModelScope.launch {
            val prefs = repository.readCurrentPreferences()
            streamMutedFlow.value = SoundResolver.isStreamMutedOrBlockedByDnd(
                appContext,
                prefs.audioStream
            )
        }
    }

    fun onTargetInputTextChanged(rawInput: String) {
        val asciiDigits = rawInput.normalizeDigitsToAscii().take(3)
        targetInputDraft.value = asciiDigits

        if (asciiDigits.isEmpty()) {
            isInputInvalidFlow.value = true
            return
        }

        val parsed = asciiDigits.toIntOrNull()
        if (parsed == null ||
            parsed < AlarmPreferencesState.MIN_TARGET_PERCENTAGE ||
            parsed > AlarmPreferencesState.MAX_TARGET_PERCENTAGE
        ) {
            isInputInvalidFlow.value = true
            return
        }

        isInputInvalidFlow.value = false
        if (feedbackBannerFlow.value is FeedbackBanner.InvalidTargetInput) {
            feedbackBannerFlow.value = null
        }
        viewModelScope.launch {
            repository.setTargetPercentage(parsed)
        }
    }

    fun setTargetPercentagePresetOrStep(newTarget: Int) {
        val clamped = newTarget.coerceIn(
            AlarmPreferencesState.MIN_TARGET_PERCENTAGE,
            AlarmPreferencesState.MAX_TARGET_PERCENTAGE
        )
        targetInputDraft.value = clamped.toString()
        isInputInvalidFlow.value = false
        if (feedbackBannerFlow.value is FeedbackBanner.InvalidTargetInput) {
            feedbackBannerFlow.value = null
        }
        viewModelScope.launch {
            repository.setTargetPercentage(clamped)
        }
    }

    fun onSoundSelected(uri: Uri?) {
        AlarmAudioPlayer.stopTestOnly()
        viewModelScope.launch {
            if (uri == null) {
                repository.setSelectedSound(
                    uriString = null,
                    displayName = appContext.getString(R.string.default_system_alarm_name)
                )
            } else {
                val resolvedName = SoundResolver.resolveDisplayName(appContext, uri)
                repository.setSelectedSound(
                    uriString = uri.toString(),
                    displayName = resolvedName
                )
            }
        }
    }

    fun onAudioStreamChanged(stream: AudioStreamPreference) {
        AlarmAudioPlayer.stopTestOnly()
        viewModelScope.launch {
            repository.setAudioStream(stream)
            streamMutedFlow.value = SoundResolver.isStreamMutedOrBlockedByDnd(appContext, stream)
        }
    }

    fun onThemeModeSelected(mode: AppThemeMode) {
        viewModelScope.launch {
            repository.setThemeMode(mode)
        }
    }

    fun toggleTestSound() {
        if (AlarmAudioPlayer.isTestPlaying.value) {
            AlarmAudioPlayer.stopTestOnly()
            return
        }
        viewModelScope.launch {
            val prefs = repository.readCurrentPreferences()
            streamMutedFlow.value = SoundResolver.isStreamMutedOrBlockedByDnd(
                appContext,
                prefs.audioStream
            )
            AlarmAudioPlayer.playTestSound(
                context = appContext,
                savedUriString = prefs.soundUriString,
                streamPreference = prefs.audioStream
            )
        }
    }

    fun stopActiveAlarmSoundOnly() {
        AlarmAudioPlayer.stopAll()
    }

    /**
     * بررسی پیش‌نیازها پیش از فعال‌سازی هشدار؛ اگر همه شرایط برقرار بود true برمی‌گرداند.
     */
    fun validateReadyToActivate(): Int? {
        val currentState = uiState.value
        val parsedTarget = currentState.targetInputText.normalizeDigitsToAscii().toIntOrNull()
        if (currentState.isTargetInputInvalid ||
            parsedTarget == null ||
            parsedTarget !in AlarmPreferencesState.MIN_TARGET_PERCENTAGE..AlarmPreferencesState.MAX_TARGET_PERCENTAGE
        ) {
            feedbackBannerFlow.value = FeedbackBanner.InvalidTargetInput
            return null
        }

        val liveSnapshot = BatteryStateObserver.readCurrentSnapshot(appContext)
        if (!liveSnapshot.isChargerConnected) {
            feedbackBannerFlow.value = FeedbackBanner.ChargerNotConnected
            return null
        }

        if (liveSnapshot.percentage > parsedTarget) {
            feedbackBannerFlow.value = FeedbackBanner.AlreadyAboveTarget(
                current = liveSnapshot.percentage,
                target = parsedTarget
            )
            return null
        }

        if (liveSnapshot.percentage == parsedTarget) {
            feedbackBannerFlow.value = FeedbackBanner.AlreadyAtTarget(target = parsedTarget)
            return null
        }

        return parsedTarget
    }

    fun activateAlarmConfirmed(
        validatedTarget: Int,
        notificationPermissionGranted: Boolean = true
    ) {
        AlarmAudioPlayer.stopTestOnly()
        targetInputDraft.value = null
        isInputInvalidFlow.value = false

        viewModelScope.launch {
            repository.setTargetPercentage(validatedTarget)
            repository.activateMonitoring()
            BatteryMonitorService.start(appContext, validatedTarget)
            feedbackBannerFlow.value = if (notificationPermissionGranted) {
                FeedbackBanner.AlarmActivated(validatedTarget)
            } else {
                FeedbackBanner.NotificationPermissionNote
            }
        }
    }

    fun deactivateAlarm() {
        AlarmAudioPlayer.stopAll()
        viewModelScope.launch {
            repository.deactivateMonitoring()
            BatteryMonitorService.stop(appContext)
            feedbackBannerFlow.value = FeedbackBanner.AlarmDeactivated
        }
    }

    fun dismissFeedbackBanner() {
        feedbackBannerFlow.value = null
    }

    private fun checkIgnoringBatteryOptimizations(): Boolean {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return runCatching { pm.isIgnoringBatteryOptimizations(appContext.packageName) }
            .getOrDefault(true)
    }

    private data class CoreSlice(
        val liveBattery: LiveBatterySnapshot,
        val prefs: AlarmPreferencesState,
        val isTestPlaying: Boolean,
        val isAlarmPlaying: Boolean
    )

    private data class ExtrasSlice(
        val draft: String?,
        val isInvalid: Boolean,
        val banner: FeedbackBanner?,
        val isBatteryOptIgnored: Boolean,
        val isMuted: Boolean
    )

    class Factory(
        private val application: Application,
        private val repository: AlarmPreferencesRepository = AlarmPreferencesRepository(application)
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(application, repository) as T
        }
    }
}
