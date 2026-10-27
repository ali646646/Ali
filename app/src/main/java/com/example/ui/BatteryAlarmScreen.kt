package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.audio.SoundResolver
import com.example.model.AlarmSessionStatus
import com.example.model.AppThemeMode
import com.example.model.AudioStreamPreference
import com.example.ui.components.BatteryGaugeCard
import com.example.ui.components.BatteryOptBottomSheet
import com.example.util.PersianFormatters.toPersianDigits
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BatteryAlarmScreen(
    viewModel: MainViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showBatteryOptSheet by rememberSaveable { mutableStateOf(false) }
    var showThemeMenu by remember { mutableStateOf(false) }
    var pendingValidatedTarget by remember { mutableIntStateOf(-1) }

    // بروزرسانی وضعیت Battery Optimization و صدای سیستم هنگام بازگشت کاربر به برنامه
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSystemChecks()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // انتخابگر صداهای هشدار/اعلان داخلی سیستم (RingtonePicker)
    val systemRingtoneLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pickedUri = result.data?.let { data ->
                IntentCompat.getParcelableExtra(
                    data,
                    RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                    Uri::class.java
                )
            }
            if (pickedUri != null) {
                viewModel.onSoundSelected(pickedUri)
            }
        }
    }

    // انتخابگر فایل صوتی از حافظه گوشی بدون نیاز به مجوز دسترسی به حافظه (SAF OpenDocument)
    val customAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.onSoundSelected(uri)
        }
    }

    // درخواست مجوز اعلان در اندروید ۱۳ به بالا هنگام فعال‌سازی سرویس پس‌زمینه
    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (pendingValidatedTarget in 1..100) {
            viewModel.activateAlarmConfirmed(
                validatedTarget = pendingValidatedTarget,
                notificationPermissionGranted = granted
            )
            pendingValidatedTarget = -1
        }
    }

    val isAlarmCurrentlyActive =
        uiState.sessionStatus == AlarmSessionStatus.ACTIVE && uiState.liveBattery.isChargerConnected

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // سربرگ ساده و تمیز برنامه
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier
                                    .height(48.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { showThemeMenu = true }
                                    .testTag("theme_selector_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val (themeIcon, themeShortLabel) = when (uiState.themeMode) {
                                        AppThemeMode.LIGHT ->
                                            Icons.Outlined.LightMode to stringResource(R.string.theme_short_light)
                                        AppThemeMode.DARK ->
                                            Icons.Outlined.DarkMode to stringResource(R.string.theme_short_dark)
                                        AppThemeMode.SYSTEM ->
                                            Icons.Outlined.BrightnessAuto to stringResource(R.string.theme_short_system)
                                    }
                                    Icon(
                                        imageVector = themeIcon,
                                        contentDescription = stringResource(R.string.cd_select_theme),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = themeShortLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = showThemeMenu,
                                onDismissRequest = { showThemeMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.theme_option_light)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.LightMode,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        viewModel.onThemeModeSelected(AppThemeMode.LIGHT)
                                        showThemeMenu = false
                                    },
                                    modifier = Modifier.testTag("theme_menu_light")
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.theme_option_dark)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.DarkMode,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        viewModel.onThemeModeSelected(AppThemeMode.DARK)
                                        showThemeMenu = false
                                    },
                                    modifier = Modifier.testTag("theme_menu_dark")
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.theme_option_system)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.BrightnessAuto,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        viewModel.onThemeModeSelected(AppThemeMode.SYSTEM)
                                        showThemeMenu = false
                                    },
                                    modifier = Modifier.testTag("theme_menu_system")
                                )
                            }
                        }

                        IconButton(
                            onClick = { showBatteryOptSheet = true },
                            modifier = Modifier.testTag("battery_optimization_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Shield,
                                contentDescription = stringResource(R.string.battery_opt_sheet_title),
                                tint = if (uiState.isIgnoringBatteryOptimizations) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                }
                            )
                        }
                    }
                }

                // ۱ و ۲. کارت نمایش درصد فعلی باتری و وضعیت باتری/هشدار
                BatteryGaugeCard(
                    liveBattery = uiState.liveBattery,
                    targetPercentage = uiState.targetPercentage,
                    sessionStatus = uiState.sessionStatus,
                    lastCompletedTarget = uiState.lastCompletedTarget
                )

                // دکمه فوری قطع صدای هشدار در صورتی که هم‌اکنون در حال پخش باشد
                if (uiState.isAlarmSoundPlaying) {
                    Button(
                        onClick = { viewModel.stopActiveAlarmSoundOnly() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("stop_ringing_alarm_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.btn_stop_active_alarm_sound),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                // بنر پیام راهنما یا خطا (مانند عدم اتصال شارژر یا بالاتر بودن درصد فعلی باتری)
                uiState.feedbackBanner?.let { banner ->
                    FeedbackBannerCard(
                        banner = banner,
                        onDismiss = viewModel::dismissFeedbackBanner
                    )
                }

                // ۳. کارت تعیین درصد هدف شارژ (پیش‌فرض ۸۰٪، بازه ۱٪ تا ۱۰۰٪)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("target_percentage_card"),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.target_section_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isAlarmCurrentlyActive) {
                                    stringResource(R.string.target_input_locked_hint)
                                } else {
                                    stringResource(R.string.target_section_subtitle)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FilledIconButton(
                                onClick = {
                                    viewModel.setTargetPercentagePresetOrStep(
                                        uiState.targetPercentage - 1
                                    )
                                },
                                enabled = !isAlarmCurrentlyActive && uiState.targetPercentage > 1,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("decrease_target_button"),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Remove,
                                    contentDescription = stringResource(R.string.cd_decrease_target)
                                )
                            }

                            OutlinedTextField(
                                value = uiState.targetInputText,
                                onValueChange = viewModel::onTargetInputTextChanged,
                                enabled = !isAlarmCurrentlyActive,
                                singleLine = true,
                                isError = uiState.isTargetInputInvalid,
                                label = { Text(stringResource(R.string.target_input_label)) },
                                suffix = { Text("٪") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("target_percentage_input"),
                                shape = MaterialTheme.shapes.medium
                            )

                            FilledIconButton(
                                onClick = {
                                    viewModel.setTargetPercentagePresetOrStep(
                                        uiState.targetPercentage + 1
                                    )
                                },
                                enabled = !isAlarmCurrentlyActive && uiState.targetPercentage < 100,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("increase_target_button"),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = stringResource(R.string.cd_increase_target)
                                )
                            }
                        }

                        if (uiState.isTargetInputInvalid) {
                            Text(
                                text = stringResource(R.string.error_invalid_target),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Slider(
                            value = uiState.targetPercentage.toFloat(),
                            onValueChange = { value ->
                                viewModel.setTargetPercentagePresetOrStep(value.roundToInt())
                            },
                            enabled = !isAlarmCurrentlyActive,
                            valueRange = 1f..100f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("target_percentage_slider")
                        )

                        // میانبرهای سریع درصدهای پرکاربرد
                        val presets = listOf(75, 80, 85, 90, 100)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            presets.forEach { preset ->
                                FilterChip(
                                    selected = uiState.targetPercentage == preset && !uiState.isTargetInputInvalid,
                                    onClick = { viewModel.setTargetPercentagePresetOrStep(preset) },
                                    enabled = !isAlarmCurrentlyActive,
                                    label = { Text("${preset.toPersianDigits()}٪") },
                                    modifier = Modifier.testTag("preset_chip_$preset")
                                )
                            }
                        }
                    }
                }

                // ۴، ۵ و ۶. کارت انتخاب صدای هشدار، نمایش نام فایل صوتی و دکمه «تست صدا»
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sound_selection_card"),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.sound_section_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // نمایش نام فایل صوتی انتخاب‌شده
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.selected_sound_label),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = uiState.selectedSoundName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("selected_sound_name")
                                    )
                                }
                            }
                        }

                        // دکمه‌های انتخاب صدای سیستم (شامل Alarm) و فایل صوتی گوشی
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val existingUri = SoundResolver.resolveEffectiveUri(
                                        uiState.selectedSoundUriString
                                    )
                                    val pickerIntent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                        putExtra(
                                            RingtoneManager.EXTRA_RINGTONE_TYPE,
                                            RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_NOTIFICATION
                                        )
                                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                        putExtra(
                                            RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                                        )
                                        putExtra(
                                            RingtoneManager.EXTRA_RINGTONE_TITLE,
                                            context.getString(R.string.ringtone_picker_title)
                                        )
                                        existingUri?.let {
                                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, it)
                                        }
                                    }
                                    runCatching {
                                        systemRingtoneLauncher.launch(pickerIntent)
                                    }.onFailure {
                                        customAudioLauncher.launch(arrayOf("audio/*"))
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("pick_system_sound_button"),
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.LibraryMusic,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.btn_pick_system_sound),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    customAudioLauncher.launch(arrayOf("audio/*"))
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("pick_audio_file_button"),
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AudioFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.btn_pick_custom_file),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // انتخاب کانال صوتی (زنگ هشدار Alarm یا اعلان Notification)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.audio_stream_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilterChip(
                                selected = uiState.audioStream == AudioStreamPreference.ALARM,
                                onClick = {
                                    viewModel.onAudioStreamChanged(AudioStreamPreference.ALARM)
                                },
                                label = { Text(stringResource(R.string.stream_alarm)) },
                                modifier = Modifier.testTag("stream_alarm_chip")
                            )
                            FilterChip(
                                selected = uiState.audioStream == AudioStreamPreference.NOTIFICATION,
                                onClick = {
                                    viewModel.onAudioStreamChanged(AudioStreamPreference.NOTIFICATION)
                                },
                                label = { Text(stringResource(R.string.stream_notification)) },
                                modifier = Modifier.testTag("stream_notification_chip")
                            )
                        }

                        // هشدار واقعی بودن حالت بی‌صدا یا صفر بودن ولوم کانال انتخاب‌شده
                        if (uiState.isStreamMutedWarning) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("muted_stream_warning")
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.VolumeOff,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.warning_stream_muted),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }

                        // ۶. دکمه «تست صدا»
                        FilledTonalButton(
                            onClick = { viewModel.toggleTestSound() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("test_sound_button"),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(
                                imageVector = if (uiState.isTestSoundPlaying) {
                                    Icons.Filled.Stop
                                } else {
                                    Icons.Filled.PlayArrow
                                },
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (uiState.isTestSoundPlaying) {
                                    stringResource(R.string.btn_stop_test_sound)
                                } else {
                                    stringResource(R.string.btn_test_sound)
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }

                // ۷ و ۸. دکمه‌های اصلی «فعال‌سازی هشدار» و «غیرفعال کردن هشدار»
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val validatedTarget = viewModel.validateReadyToActivate()
                            if (validatedTarget != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    pendingValidatedTarget = validatedTarget
                                    notificationPermLauncher.launch(
                                        Manifest.permission.POST_NOTIFICATIONS
                                    )
                                } else {
                                    viewModel.activateAlarmConfirmed(validatedTarget)
                                }
                            }
                        },
                        enabled = !isAlarmCurrentlyActive,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("activate_alarm_button"),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(
                            imageVector = Icons.Filled.NotificationsActive,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.btn_activate_alarm),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    OutlinedButton(
                        onClick = { viewModel.deactivateAlarm() },
                        enabled = uiState.sessionStatus != AlarmSessionStatus.IDLE || uiState.isAlarmSoundPlaying,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("deactivate_alarm_button"),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (uiState.sessionStatus != AlarmSessionStatus.IDLE || uiState.isAlarmSoundPlaying) {
                                MaterialTheme.colorScheme.outline
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                            }
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.NotificationsOff,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.btn_deactivate_alarm),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                // کارت ساده تنظیم Battery Optimization برای پایداری در پس‌زمینه
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { showBatteryOptSheet = true }
                        .testTag("battery_opt_card"),
                    shape = MaterialTheme.shapes.medium,
                    color = if (uiState.isIgnoringBatteryOptimizations) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (uiState.isIgnoringBatteryOptimizations) {
                                Icons.Outlined.CheckCircle
                            } else {
                                Icons.Outlined.WarningAmber
                            },
                            contentDescription = null,
                            tint = if (uiState.isIgnoringBatteryOptimizations) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.tertiary
                            }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (uiState.isIgnoringBatteryOptimizations) {
                                    stringResource(R.string.battery_opt_unrestricted_title)
                                } else {
                                    stringResource(R.string.battery_opt_active_title)
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (uiState.isIgnoringBatteryOptimizations) {
                                    stringResource(R.string.battery_opt_unrestricted_subtitle)
                                } else {
                                    stringResource(R.string.battery_opt_active_subtitle)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    if (showBatteryOptSheet) {
        BatteryOptBottomSheet(
            onDismiss = {
                showBatteryOptSheet = false
                viewModel.refreshSystemChecks()
            }
        )
    }
}

@Composable
private fun FeedbackBannerCard(
    banner: FeedbackBanner,
    onDismiss: () -> Unit
) {
    val isWarning = banner is FeedbackBanner.ChargerNotConnected ||
        banner is FeedbackBanner.AlreadyAboveTarget ||
        banner is FeedbackBanner.AlreadyAtTarget ||
        banner is FeedbackBanner.InvalidTargetInput

    val containerColor = if (isWarning) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = if (isWarning) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    val messageText = when (banner) {
        FeedbackBanner.ChargerNotConnected ->
            stringResource(R.string.msg_connect_charger_first)
        is FeedbackBanner.AlreadyAboveTarget ->
            stringResource(
                R.string.msg_already_above_target,
                banner.current.toPersianDigits(),
                banner.target.toPersianDigits()
            )
        is FeedbackBanner.AlreadyAtTarget ->
            stringResource(
                R.string.msg_already_at_target,
                banner.target.toPersianDigits()
            )
        FeedbackBanner.InvalidTargetInput ->
            stringResource(R.string.error_invalid_target)
        is FeedbackBanner.AlarmActivated ->
            stringResource(
                R.string.msg_alarm_activated,
                banner.target.toPersianDigits()
            )
        FeedbackBanner.AlarmDeactivated ->
            stringResource(R.string.msg_alarm_deactivated)
        FeedbackBanner.NotificationPermissionNote ->
            stringResource(R.string.msg_notification_perm_note)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("feedback_message_card"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isWarning) Icons.Outlined.WarningAmber else Icons.Outlined.Info,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = messageText,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.cd_dismiss_message),
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
