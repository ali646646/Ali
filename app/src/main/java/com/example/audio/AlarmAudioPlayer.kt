package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.example.model.AudioStreamPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * پخش‌کننده چندلایه و تضمینی صدای هشدار در پس‌زمینه بدون روشن کردن صفحه‌نمایش.
 * فقط از PARTIAL_WAKE_LOCK (صرفاً پردازنده، بدون هیچ پرچم روشن‌کننده صفحه) در حین پخش صدا استفاده می‌کند.
 */
object AlarmAudioPlayer {

    private const val MAX_PLAYBACK_WAKELOCK_MS = 120_000L
    private const val FALLBACK_RINGTONE_MAX_DURATION_MS = 12_000L

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaPlayer: MediaPlayer? = null
    private var activeRingtone: Ringtone? = null
    private var activeToneGenerator: ToneGenerator? = null
    private var fallbackStopRunnable: Runnable? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioManagerRef: AudioManager? = null

    private val _isTestPlaying = MutableStateFlow(false)
    val isTestPlaying: StateFlow<Boolean> = _isTestPlaying.asStateFlow()

    private val _isAlarmPlaying = MutableStateFlow(false)
    val isAlarmPlaying: StateFlow<Boolean> = _isAlarmPlaying.asStateFlow()

    @Volatile
    var testPlaybackInterceptor: ((Uri?, AudioStreamPreference, Boolean, () -> Unit) -> Boolean)? = null

    fun playTestSound(
        context: Context,
        savedUriString: String?,
        streamPreference: AudioStreamPreference
    ): Boolean {
        return startPlaybackInternal(
            context = context.applicationContext,
            savedUriString = savedUriString,
            streamPreference = streamPreference,
            isTestMode = true,
            onFinished = null
        )
    }

    fun playAlarmOnce(
        context: Context,
        savedUriString: String?,
        streamPreference: AudioStreamPreference,
        onFinished: (() -> Unit)? = null
    ): Boolean {
        return startPlaybackInternal(
            context = context.applicationContext,
            savedUriString = savedUriString,
            streamPreference = streamPreference,
            isTestMode = false,
            onFinished = onFinished
        )
    }

    private fun startPlaybackInternal(
        context: Context,
        savedUriString: String?,
        streamPreference: AudioStreamPreference,
        isTestMode: Boolean,
        onFinished: (() -> Unit)?
    ): Boolean {
        synchronized(lock) {
            stopInternalLocked()

            val primaryUri = SoundResolver.resolveEffectiveUri(savedUriString)
            val interceptor = testPlaybackInterceptor
            if (interceptor != null) {
                if (isTestMode) {
                    _isTestPlaying.value = true
                } else {
                    _isAlarmPlaying.value = true
                }
                return interceptor.invoke(primaryUri, streamPreference, isTestMode) {
                    synchronized(lock) {
                        _isTestPlaying.value = false
                        _isAlarmPlaying.value = false
                    }
                    onFinished?.invoke()
                }
            }

            val candidateUris = SoundResolver.buildCandidateUris(context, savedUriString)

            val usage = when (streamPreference) {
                AudioStreamPreference.ALARM -> AudioAttributes.USAGE_ALARM
                AudioStreamPreference.NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
            }
            val legacyStream = when (streamPreference) {
                AudioStreamPreference.ALARM -> AudioManager.STREAM_ALARM
                AudioStreamPreference.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
            }

            val attributes = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            acquireCpuOnlyWakeLockLocked(context)
            requestAudioFocusLocked(context, attributes, legacyStream)

            // لایه اول: پخش دقیق و یک‌باره با MediaPlayer روی تمام URIهای کاندید
            for (uri in candidateUris) {
                val player = MediaPlayer()
                try {
                    player.setAudioAttributes(attributes)
                    player.setDataSource(context, uri)
                    player.setVolume(1.0f, 1.0f)
                    player.isLooping = false
                    player.prepare()
                    player.isLooping = false

                    player.setOnCompletionListener {
                        synchronized(lock) {
                            stopInternalLocked()
                        }
                        onFinished?.invoke()
                    }
                    player.setOnErrorListener { _, _, _ ->
                        synchronized(lock) {
                            stopInternalLocked()
                        }
                        onFinished?.invoke()
                        true
                    }

                    mediaPlayer = player
                    markPlayingStateLocked(isTestMode)
                    player.start()
                    return true
                } catch (_: Exception) {
                    runCatching { player.release() }
                }
            }

            // لایه دوم: پخش از طریق Ringtone سیستمی (مناسب رام‌های سفارشی دارای قفل داخلی زنگ‌ها)
            for (uri in candidateUris) {
                try {
                    val ringtone = RingtoneManager.getRingtone(context, uri) ?: continue
                    ringtone.audioAttributes = attributes
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ringtone.isLooping = false
                        ringtone.volume = 1.0f
                    }
                    activeRingtone = ringtone
                    markPlayingStateLocked(isTestMode)
                    ringtone.play()

                    scheduleRingtoneCompletionCheckLocked(ringtone, onFinished)
                    return true
                } catch (_: Exception) {
                    // ادامه به کاندید بعدی
                }
            }

            // لایه سوم: بوق هشدار سخت‌افزاری ToneGenerator در صورتی که تمام فایل‌های سیستم غیرقابل دسترس باشند
            try {
                val toneGen = ToneGenerator(legacyStream, 100)
                activeToneGenerator = toneGen
                markPlayingStateLocked(isTestMode)
                toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 4_000)
                val runnable = Runnable {
                    synchronized(lock) {
                        stopInternalLocked()
                    }
                    onFinished?.invoke()
                }
                fallbackStopRunnable = runnable
                mainHandler.postDelayed(runnable, 4_200L)
                return true
            } catch (_: Exception) {
                stopInternalLocked()
                onFinished?.invoke()
                return false
            }
        }
    }

    private fun scheduleRingtoneCompletionCheckLocked(
        ringtone: Ringtone,
        onFinished: (() -> Unit)?
    ) {
        val startTime = System.currentTimeMillis()
        val pollRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                val stillPlaying = runCatching { ringtone.isPlaying }.getOrDefault(false)
                if (!stillPlaying || elapsed >= FALLBACK_RINGTONE_MAX_DURATION_MS) {
                    synchronized(lock) {
                        stopInternalLocked()
                    }
                    onFinished?.invoke()
                } else {
                    mainHandler.postDelayed(this, 400L)
                }
            }
        }
        fallbackStopRunnable = pollRunnable
        mainHandler.postDelayed(pollRunnable, 600L)
    }

    private fun markPlayingStateLocked(isTestMode: Boolean) {
        if (isTestMode) {
            _isTestPlaying.value = true
            _isAlarmPlaying.value = false
        } else {
            _isAlarmPlaying.value = true
            _isTestPlaying.value = false
        }
    }

    fun stopAll() {
        synchronized(lock) {
            stopInternalLocked()
        }
    }

    fun stopTestOnly() {
        synchronized(lock) {
            if (_isTestPlaying.value) {
                stopInternalLocked()
            }
        }
    }

    private fun acquireCpuOnlyWakeLockLocked(context: Context) {
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            // فقط PARTIAL_WAKE_LOCK: صفحه‌نمایش و نور پس‌زمینه کاملاً خاموش باقی می‌مانند
            cpuWakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BatteryChargeAlarm:CpuAudioPlayback"
            )?.apply {
                setReferenceCounted(false)
                acquire(MAX_PLAYBACK_WAKELOCK_MS)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun requestAudioFocusLocked(
        context: Context,
        attributes: AudioAttributes,
        legacyStream: Int
    ) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManagerRef = am
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                am.requestAudioFocus(
                    null,
                    legacyStream,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun abandonAudioFocusLocked() {
        val am = audioManagerRef ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                am.abandonAudioFocus(null)
            }
        }
        audioFocusRequest = null
        audioManagerRef = null
    }

    private fun stopInternalLocked() {
        fallbackStopRunnable?.let { mainHandler.removeCallbacks(it) }
        fallbackStopRunnable = null

        mediaPlayer?.let { player ->
            runCatching {
                if (player.isPlaying) {
                    player.stop()
                }
            }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
        mediaPlayer = null

        activeRingtone?.let { rt ->
            runCatching {
                if (rt.isPlaying) {
                    rt.stop()
                }
            }
        }
        activeRingtone = null

        activeToneGenerator?.let { tg ->
            runCatching { tg.stopTone() }
            runCatching { tg.release() }
        }
        activeToneGenerator = null

        abandonAudioFocusLocked()

        cpuWakeLock?.let { wl ->
            runCatching {
                if (wl.isHeld) {
                    wl.release()
                }
            }
        }
        cpuWakeLock = null

        _isTestPlaying.value = false
        _isAlarmPlaying.value = false
    }
}
