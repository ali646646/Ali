package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.PowerOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.AlarmSessionStatus
import com.example.model.LiveBatterySnapshot
import com.example.ui.theme.BatteryAlarmTheme
import com.example.util.EstimatedTimeState
import com.example.util.PersianFormatters.toPersianDigits

@Composable
fun BatteryGaugeCard(
    liveBattery: LiveBatterySnapshot,
    targetPercentage: Int,
    sessionStatus: AlarmSessionStatus,
    lastCompletedTarget: Int?,
    estimatedTimeState: EstimatedTimeState,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    val (statusText, statusIcon, badgeBg, badgeFg) = when {
        sessionStatus == AlarmSessionStatus.ACTIVE && liveBattery.isChargerConnected -> StatusVisual(
            text = stringResource(R.string.status_alarm_active),
            icon = Icons.Filled.NotificationsActive,
            bgColor = colorScheme.primaryContainer,
            fgColor = colorScheme.onPrimaryContainer
        )
        sessionStatus == AlarmSessionStatus.COMPLETED && liveBattery.isChargerConnected -> StatusVisual(
            text = stringResource(R.string.status_target_reached),
            icon = Icons.Filled.CheckCircle,
            bgColor = colorScheme.tertiaryContainer,
            fgColor = colorScheme.onTertiaryContainer
        )
        liveBattery.isChargerConnected -> StatusVisual(
            text = stringResource(R.string.status_charging),
            icon = Icons.Filled.BatteryChargingFull,
            bgColor = colorScheme.primaryContainer.copy(alpha = 0.7f),
            fgColor = colorScheme.onPrimaryContainer
        )
        else -> StatusVisual(
            text = stringResource(R.string.status_not_charging),
            icon = Icons.Outlined.BatteryStd,
            bgColor = colorScheme.surfaceVariant,
            fgColor = colorScheme.onSurfaceVariant
        )
    }

    val currentNumberColor = when {
        sessionStatus == AlarmSessionStatus.COMPLETED && liveBattery.isChargerConnected -> colorScheme.tertiary
        liveBattery.isChargerConnected -> colorScheme.primary
        else -> colorScheme.onSurface
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("battery_status_card"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ردیف اصلی: درصد فعلی باتری و درصد هدف با فونت بزرگ‌تر و بدون نیم‌دایره
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // ستون درصد فعلی باتری
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.current_battery_short),
                        style = MaterialTheme.typography.labelLarge,
                        color = colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${liveBattery.percentage.toPersianDigits()}٪",
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = 46.sp,
                            lineHeight = 52.sp
                        ),
                        fontWeight = FontWeight.Bold,
                        color = currentNumberColor,
                        modifier = Modifier.testTag("current_battery_text")
                    )
                }

                VerticalDivider(
                    modifier = Modifier
                        .height(64.dp)
                        .padding(top = 6.dp),
                    thickness = 1.dp,
                    color = colorScheme.outline.copy(alpha = 0.35f)
                )

                // ستون درصد هدف و زمان تخمینی رسیدن به شارژ هدف در زیر آن
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.target_battery_short),
                        style = MaterialTheme.typography.labelLarge,
                        color = colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${targetPercentage.toPersianDigits()}٪",
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = 46.sp,
                            lineHeight = 52.sp
                        ),
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.tertiary,
                        modifier = Modifier.testTag("target_battery_text")
                    )

                    if (estimatedTimeState !is EstimatedTimeState.Hidden) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val estimateLabel = formatEstimatedTime(estimatedTimeState)
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.testTag("estimated_time_text")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Schedule,
                                    contentDescription = null,
                                    tint = colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = estimateLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colorScheme.onTertiaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(
                thickness = 1.dp,
                color = colorScheme.outline.copy(alpha = 0.2f)
            )

            // ردیف فشرده وضعیت باتری + نوع شارژر در یک خط
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = badgeBg,
                        modifier = Modifier.testTag("battery_status_badge")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = null,
                                tint = badgeFg,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = badgeFg
                            )
                        }
                    }

                    if (sessionStatus == AlarmSessionStatus.COMPLETED && liveBattery.isChargerConnected) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = colorScheme.primaryContainer,
                            modifier = Modifier.testTag("alarm_completed_chip")
                        ) {
                            Text(
                                text = stringResource(R.string.status_completed_badge),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (liveBattery.isChargerConnected) {
                            Icons.Outlined.Power
                        } else {
                            Icons.Outlined.PowerOff
                        },
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = stringResource(liveBattery.plugTypeLabelRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (sessionStatus == AlarmSessionStatus.ACTIVE && liveBattery.isChargerConnected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(colorScheme.primary, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Outlined.DarkMode,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.screen_off_assurance),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else if (!liveBattery.isChargerConnected && lastCompletedTarget != null) {
                Text(
                    text = stringResource(
                        R.string.last_completed_note,
                        lastCompletedTarget.toPersianDigits()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
private fun formatEstimatedTime(state: EstimatedTimeState): String {
    return when (state) {
        EstimatedTimeState.Hidden -> ""
        EstimatedTimeState.Calculating -> stringResource(R.string.estimate_calculating)
        is EstimatedTimeState.Ready -> {
            val hours = state.totalMinutes / 60
            val minutes = state.totalMinutes % 60
            when {
                hours <= 0 -> stringResource(R.string.estimate_minutes, minutes.toPersianDigits())
                minutes == 0 -> stringResource(R.string.estimate_hours, hours.toPersianDigits())
                else -> stringResource(
                    R.string.estimate_hours_minutes,
                    hours.toPersianDigits(),
                    minutes.toPersianDigits()
                )
            }
        }
    }
}

private data class StatusVisual(
    val text: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val bgColor: androidx.compose.ui.graphics.Color,
    val fgColor: androidx.compose.ui.graphics.Color
)

@Preview(showBackground = true)
@Composable
private fun BatteryGaugeCardEstimatePreview() {
    BatteryAlarmTheme {
        BatteryGaugeCard(
            liveBattery = LiveBatterySnapshot(
                percentage = 64,
                isChargerConnected = true,
                plugTypeLabelRes = R.string.plug_type_ac
            ),
            targetPercentage = 80,
            sessionStatus = AlarmSessionStatus.ACTIVE,
            lastCompletedTarget = null,
            estimatedTimeState = EstimatedTimeState.Ready(24),
            modifier = Modifier.padding(16.dp)
        )
    }
}
