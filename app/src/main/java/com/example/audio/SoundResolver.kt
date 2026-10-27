package com.example.audio

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import com.example.R
import com.example.model.AudioStreamPreference

object SoundResolver {

    fun resolveEffectiveUri(savedUriString: String?): Uri? {
        if (!savedUriString.isNullOrBlank()) {
            return runCatching { Uri.parse(savedUriString) }.getOrNull()
        }
        return getDefaultSystemAlarmUri()
    }

    fun getDefaultSystemAlarmUri(): Uri? {
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    }

    /**
     * فهرست اولویت‌بندی‌شده URIهای معتبر صوتی (شامل URI واقعی تنظیم‌شده روی گوشی و اولین زنگ هشدار موجود در سیستم)
     * برای جلوگیری از خطای باز نشدن پیش‌فرض نمادین در برخی گوشی‌های سامسونگ و شیائومی.
     */
    fun buildCandidateUris(context: Context, savedUriString: String?): List<Uri> {
        val candidates = LinkedHashSet<Uri>()

        if (!savedUriString.isNullOrBlank()) {
            runCatching { Uri.parse(savedUriString) }.getOrNull()?.let { candidates.add(it) }
        }

        runCatching {
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
        }.getOrNull()?.let { candidates.add(it) }

        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { candidates.add(it) }

        runCatching {
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
        }.getOrNull()?.let { candidates.add(it) }

        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)?.let { candidates.add(it) }

        runCatching {
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
        }.getOrNull()?.let { candidates.add(it) }

        // جستجوی مستقیم اولین صدای Alarm موجود در سیستم در صورتی که پیش‌فرض گوشی خالی باشد
        if (candidates.size <= 2) {
            runCatching {
                val rm = RingtoneManager(context).apply {
                    setType(RingtoneManager.TYPE_ALARM)
                }
                val cursor = rm.cursor
                if (cursor != null && cursor.moveToFirst()) {
                    rm.getRingtoneUri(0)?.let { candidates.add(it) }
                }
                cursor?.close()
            }
        }

        return candidates.toList()
    }

    fun resolveDisplayName(context: Context, uri: Uri?): String {
        if (uri == null) {
            return context.getString(R.string.default_system_alarm_name)
        }

        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0) {
                            val fileName = cursor.getString(nameIdx)
                            if (!fileName.isNullOrBlank()) {
                                return fileName
                            }
                        }
                    }
                }
            }
        }

        runCatching {
            val ringtone = RingtoneManager.getRingtone(context, uri)
            val title = ringtone?.getTitle(context)
            if (!title.isNullOrBlank()) {
                return title
            }
        }

        return uri.lastPathSegment ?: context.getString(R.string.default_system_alarm_name)
    }

    fun isStreamMutedOrBlockedByDnd(
        context: Context,
        streamPreference: AudioStreamPreference
    ): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

        val streamType = when (streamPreference) {
            AudioStreamPreference.ALARM -> AudioManager.STREAM_ALARM
            AudioStreamPreference.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        }

        val currentVolume = runCatching { audioManager.getStreamVolume(streamType) }.getOrDefault(1)
        if (currentVolume <= 0) {
            return true
        }

        val dndFilter = notificationManager?.currentInterruptionFilter
            ?: NotificationManager.INTERRUPTION_FILTER_ALL

        return when (dndFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> true
            NotificationManager.INTERRUPTION_FILTER_ALARMS ->
                streamPreference == AudioStreamPreference.NOTIFICATION
            else -> false
        }
    }
}
