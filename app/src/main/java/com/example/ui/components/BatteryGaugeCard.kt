package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AlarmSessionStatus
import com.example.model.LiveBatterySnapshot
import com.example.util.PersianFormatters.toPersianDigits
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun BatteryGaugeCard(
    liveBattery: LiveBatterySnapshot,
    targetPercentage: Int,
    sessionStatus: AlarmSessionStatus,
    lastCompletedTarget: Int?,
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

    val ringColor = when {
        sessionStatus == AlarmSessionStatus.COMPLETED && liveBattery.isChargerConnected -> colorScheme.tertiary
        liveBattery.isChargerConnected -> colorScheme.primary
        else -> colorScheme.secondary
    }
    val trackColor = colorScheme.surfaceVariant
    val targetMarkerColor = colorScheme.tertiary

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
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // نشانگر دایره‌ای تمیز و مینیمال درصد باتری و نقطه هدف
            Box(
                modifier = Modifier.size(176.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokePx = 13.dp.toPx()
                    val diameter = size.minDimension - strokePx * 1.6f
                    val topLeft = Offset(
                        (size.width - diameter) / 2f,
                        (size.height - diameter) / 2f
                    )
                    val arcSize = Size(diameter, diameter)
                    val startAngle = 135f
                    val fullSweep = 270f

                    // پس‌زمینه کمان
                    drawArc(
                        color = trackColor,
                        startAngle = startAngle,
                        sweepAngle = fullSweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )

                    // کمان درصد فعلی باتری
                    val batteryFraction = (liveBattery.percentage.coerceIn(0, 100)) / 100f
                    if (batteryFraction > 0f) {
                        drawArc(
                            color = ringColor,
                            startAngle = startAngle,
                            sweepAngle = fullSweep * batteryFraction,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round)
                        )
                    }

                    // نقطه نشانگر درصد هدف روی حلقه
                    val targetFraction = (targetPercentage.coerceIn(1, 100)) / 100f
                    val targetAngleRad = Math.toRadians((startAngle + fullSweep * targetFraction).toDouble())
                    val radius = diameter / 2f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val markerPos = Offset(
                        x = center.x + (radius * cos(targetAngleRad)).toFloat(),
                        y = center.y + (radius * sin(targetAngleRad)).toFloat()
                    )
                    drawCircle(
                        color = colorScheme.surface,
                        radius = strokePx * 0.68f,
                        center = markerPos
                    )
                    drawCircle(
                        color = targetMarkerColor,
                        radius = strokePx * 0.44f,
                        center = markerPos
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.current_battery_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${liveBattery.percentage.toPersianDigits()}٪",
                        style = MaterialTheme.typography.displayMedium,
                        color = colorScheme.onSurface,
                        modifier = Modifier.testTag("current_battery_text")
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = colorScheme.tertiaryContainer.copy(alpha = 0.65f)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.target_indicator_format,
                                targetPercentage.toPersianDigits()
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // نشان وضعیت اصلی باتری و هشدار
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = badgeBg,
                    modifier = Modifier.testTag("battery_status_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = badgeFg,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = badgeFg
                        )
                    }
                }

                if (sessionStatus == AlarmSessionStatus.COMPLETED && liveBattery.isChargerConnected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = colorScheme.primaryContainer,
                        modifier = Modifier.testTag("alarm_completed_chip")
                    ) {
                        Text(
                            text = stringResource(R.string.status_completed_badge),
                            style = MaterialTheme.typography.labelLarge,
                            color = colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ردیف اطلاع‌رسانی منبع شارژ
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (liveBattery.isChargerConnected) {
                        Icons.Outlined.Power
                    } else {
                        Icons.Outlined.PowerOff
                    },
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(liveBattery.plugTypeLabelRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }

            if (sessionStatus == AlarmSessionStatus.ACTIVE && liveBattery.isChargerConnected) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(colorScheme.primary, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Outlined.DarkMode,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
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
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.last_completed_note,
                        lastCompletedTarget.toPersianDigits()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
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
