package com.example.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.data.model.ForwardConfig
import com.example.data.model.ForwardLog
import com.example.network.ServerHealthStatus
import com.example.service.PermissionNotifier
import com.example.ui.theme.BarProAmber
import com.example.ui.theme.BarProAmberBg
import com.example.ui.theme.BarProBg
import com.example.ui.theme.BarProBorder
import com.example.ui.theme.BarProBorderCyan
import com.example.ui.theme.BarProCyan
import com.example.ui.theme.BarProCyanBright
import com.example.ui.theme.BarProCyanMuted
import com.example.ui.theme.BarProEmerald
import com.example.ui.theme.BarProEmeraldBg
import com.example.ui.theme.BarProRose
import com.example.ui.theme.BarProRoseBg
import com.example.ui.theme.BarProSurface
import com.example.ui.theme.BarProSurfaceElevated
import com.example.ui.theme.BarProSurfaceSubtle
import com.example.ui.theme.BarProTextMuted
import com.example.ui.theme.BarProTextPrimary
import com.example.ui.theme.BarProTextSecondary
import com.example.utils.PermissionHelper
import com.example.utils.PermissionItemInfo
import com.example.utils.PermissionType

@Composable
fun DashboardScreen(
    config: ForwardConfig,
    serverHealthState: com.example.network.ServerHealthState,
    totalCount: Int = 0,
    successCount: Int = 0,
    failedCount: Int = 0,
    pendingCount: Int = 0,
    rulesCount: Int = 0,
    recentLogs: List<ForwardLog> = emptyList(),
    onToggleMaster: (Boolean) -> Unit,
    onCheckHealthNow: () -> Unit = {},
    onOpenSimulate: () -> Unit = {},
    onOpenOtpInquiry: () -> Unit = {},
    onOpenPermissions: () -> Unit = {},
    onOpenServerGuide: () -> Unit = {},
    onOpenBackgroundGuide: () -> Unit = {},
    onNavigateToRules: () -> Unit = {},
    onNavigateToServerConfig: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    onSelectLog: (ForwardLog) -> Unit = {},
    onSyncOfflineLogs: () -> Unit = {},
    isOfflineSyncing: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var permissionsList by remember {
        mutableStateOf(PermissionHelper.getAllPermissionsStatus(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val updated = PermissionHelper.getAllPermissionsStatus(context)
                permissionsList = updated
                val criticalGranted = PermissionHelper.areCriticalPermissionsGranted(context)
                if (!criticalGranted) {
                    val missing = updated.firstOrNull { it.isRequired && !it.isGranted }
                    if (missing != null) {
                        PermissionNotifier.showMissingPermissionAlert(context, missing.title)
                    }
                } else {
                    PermissionNotifier.clearAlert(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val isAllPermissionsGranted = PermissionHelper.areCriticalPermissionsGranted(context)
    val grantedPermissionsCount = permissionsList.count { it.isGranted }
    val totalPermissionsCount = permissionsList.size
    val permissionProgress = if (totalPermissionsCount > 0) grantedPermissionsCount.toFloat() / totalPermissionsCount.toFloat() else 1f

    // Checklist of permissions that need to be granted:
    // Once granted, an item gets a checkmark and is hidden!
    val ungrantedPermissions = permissionsList.filter { !it.isGranted }

    val isWorking = config.isMasterEnabled && isAllPermissionsGranted
    val isConnected = serverHealthState.status == ServerHealthStatus.CONNECTED

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val updated = PermissionHelper.getAllPermissionsStatus(context)
        permissionsList = updated
        if (PermissionHelper.areCriticalPermissionsGranted(context)) {
            PermissionNotifier.clearAlert(context)
        }
    }

    val singlePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        val updated = PermissionHelper.getAllPermissionsStatus(context)
        permissionsList = updated
        if (PermissionHelper.areCriticalPermissionsGranted(context)) {
            PermissionNotifier.clearAlert(context)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulseAnimation")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val waveAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BarProBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ==========================================
        // 1. HERO SERVICE CARD (کارت وضعیت اصلی سامانه)
        // ==========================================
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("service_status_indicator_card"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = BarProSurface)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                BarProSurface,
                                BarProBg
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = if (isWorking) {
                                listOf(BarProCyan.copy(alpha = 0.6f), BarProBorder)
                            } else {
                                listOf(BarProRose.copy(alpha = 0.4f), BarProBorder)
                            }
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(20.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top row: Switch & Live Badge
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Live Status Badge
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(BarProSurfaceSubtle)
                                .border(
                                    1.dp,
                                    if (isWorking) BarProEmerald.copy(alpha = 0.5f) else BarProRose.copy(alpha = 0.4f),
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isWorking) BarProEmerald else BarProRose)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isWorking) "سامانه آنلاین و فعال" else "سامانه غیرفعال",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isWorking) BarProEmerald else BarProRose
                            )
                        }

                        // Master switch
                        Switch(
                            checked = config.isMasterEnabled,
                            onCheckedChange = onToggleMaster,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = BarProBg,
                                checkedTrackColor = BarProCyan,
                                uncheckedThumbColor = BarProTextSecondary,
                                uncheckedTrackColor = BarProSurfaceElevated
                            ),
                            modifier = Modifier.testTag("master_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Pulsing Glowing Center Orb with BarPro Logo
                    Box(
                        modifier = Modifier.size(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isWorking) {
                            // Outer ambient cyan glow
                            Box(
                                modifier = Modifier
                                    .size(120.dp)
                                    .scale(pulseScale)
                                    .clip(CircleShape)
                                    .background(BarProCyan.copy(alpha = waveAlpha))
                            )
                            // Inner subtle glow
                            Box(
                                modifier = Modifier
                                    .size(95.dp)
                                    .clip(CircleShape)
                                    .background(BarProCyan.copy(alpha = 0.15f))
                            )
                        }

                        // Center Circle
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(BarProSurfaceElevated, BarProSurface)
                                    )
                                )
                                .border(
                                    2.dp,
                                    if (isWorking) BarProCyan else BarProTextMuted,
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_barpro_logo),
                                contentDescription = "BarPro Logo",
                                tint = Color.Unspecified,
                                modifier = Modifier.size(54.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Title
                    Text(
                        text = if (isWorking) "سامانه فورواردر فعال و آماده دریافت" else "سامانه دریافت پیامک خاموش است",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = BarProTextPrimary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Subtitle
                    Text(
                        text = if (isWorking)
                            "پیامک‌های حاوی کد بارنامه و رمز اعتبارسنجی بلادرنگ به سرور بارپرو مخابره می‌شوند"
                        else
                            "برای شروع دریافت و ارسال خودکار کدهای بارنامه، کلید بالا را روشن کنید",
                        style = MaterialTheme.typography.bodySmall,
                        color = BarProTextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Driver and Server summary pills
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val driverLabel = if (config.driverFullName.isNotBlank()) config.driverFullName
                        else if (config.driverPhone.isNotBlank()) config.driverPhone
                        else "ثبت‌نشده"

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(BarProSurfaceSubtle)
                                .border(1.dp, BarProBorder, RoundedCornerShape(10.dp))
                                .clickable { onNavigateToServerConfig() }
                                .padding(vertical = 8.dp, horizontal = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = BarProCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text("راننده", fontSize = 10.sp, color = BarProTextMuted)
                                    Text(driverLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = BarProTextPrimary, maxLines = 1)
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(BarProSurfaceSubtle)
                                .border(1.dp, BarProBorder, RoundedCornerShape(10.dp))
                                .clickable { onCheckHealthNow() }
                                .padding(vertical = 8.dp, horizontal = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isConnected) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                    contentDescription = null,
                                    tint = if (isConnected) BarProEmerald else BarProRose,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text("سرور بارپرو", fontSize = 10.sp, color = BarProTextMuted)
                                    Text(if (isConnected) "متصل و آنلاین" else "قطع ارتباط", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isConnected) BarProEmerald else BarProRose)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // 2. PERMISSIONS CHECKLIST (چک‌لیست دسترسی‌ها)
        // ==========================================
        // As requested by user:
        // Shows as a checklist. Once granted, it is checked and NO LONGER SHOWN!
        // If all granted, shows a compact clean banner.
        // If any revoked, it reappears and notifies the driver.
        if (ungrantedPermissions.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("permissions_checklist_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = BarProSurface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BarProRose.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(BarProRoseBg)
                                .border(1.dp, BarProRose.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = BarProRose,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "چک‌لیست دسترسی‌های موردنیاز",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = BarProTextPrimary
                            )
                            Text(
                                text = "برای انتقال خودکار پیامک‌ها، دسترسی‌های زیر را تایید کنید (پس از تایید پنهان می‌شوند):",
                                style = MaterialTheme.typography.labelSmall,
                                color = BarProTextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Render only ungranted permissions
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ungrantedPermissions.forEach { perm ->
                            DashboardPermissionRow(
                                item = perm,
                                onGrant = {
                                    when (perm.permissionType) {
                                        PermissionType.RUNTIME_PERMISSION -> {
                                            perm.manifestPermission?.let { singlePermissionLauncher.launch(it) }
                                        }
                                        PermissionType.BATTERY_OPTIMIZATION -> {
                                            PermissionHelper.requestBatteryOptimization(context)
                                        }
                                        PermissionType.NOTIFICATION_LISTENER -> {
                                            PermissionHelper.openNotificationListenerSettings(context)
                                        }
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Direct "Grant All" Button
                    Button(
                        onClick = {
                            val runtimeToRequest = PermissionHelper.getRequiredRuntimePermissions()
                            permissionLauncher.launch(runtimeToRequest)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BarProCyan,
                            contentColor = BarProBg
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("request_permission_button")
                    ) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "تایید و اعطای مستقیم تمامی مجوزها",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            // All permissions granted - Compact confirmation banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BarProSurface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BarProEmerald.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(BarProEmeraldBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = BarProEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "تمامی مجوزهای سامانه تایید شده‌اند",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = BarProTextPrimary
                        )
                        Text(
                            text = "سیستم آماده دریافت پیامک و ارسال به سرور بارپرو در پس‌زمینه است",
                            style = MaterialTheme.typography.bodySmall,
                            color = BarProTextSecondary
                        )
                    }
                }
            }
        }


        // ==========================================
        // 4. SUMMARY METRICS CARD (آمار کدهای ارسال‌شده)
        // ==========================================
        // As requested by user:
        // No raw message bodies ("پیام ها") and no category tags ("دسته بندی")!
        // Only clean high-level operational counts for the driver.
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = BarProSurface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BarProBorder, RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "آمار کدهای بارنامه مخابره‌شده",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = BarProTextPrimary
                    )
                    if (pendingCount > 0) {
                        Text(
                            text = "$pendingCount در صف ارسال",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = BarProAmber
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricCounterBox(
                        label = "کل کدهای دریافتی",
                        count = totalCount,
                        color = BarProTextPrimary,
                        modifier = Modifier.weight(1f)
                    )

                    MetricCounterBox(
                        label = "ارسال موفق به سرور",
                        count = successCount,
                        color = BarProEmerald,
                        modifier = Modifier.weight(1f)
                    )

                    MetricCounterBox(
                        label = "صف معوقه آفلاین",
                        count = pendingCount,
                        color = if (pendingCount > 0) BarProAmber else BarProTextMuted,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (pendingCount > 0) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onSyncOfflineLogs,
                        enabled = !isOfflineSyncing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BarProAmberBg,
                            contentColor = BarProAmber
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .border(1.dp, BarProAmber.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تلاش مجدد برای ارسال کدهای معوقه آفلاین", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun MetricCounterBox(
    label: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(BarProSurfaceSubtle)
            .border(1.dp, BarProBorder, RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = count.toString(),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                color = BarProTextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun DashboardPermissionRow(
    item: PermissionItemInfo,
    onGrant: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BarProSurfaceSubtle)
            .border(1.dp, if (item.isRequired) BarProRose.copy(alpha = 0.4f) else BarProBorder, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(BarProSurface)
                    .border(1.dp, if (item.isRequired) BarProRose.copy(alpha = 0.5f) else BarProBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (item.id) {
                        "receive_sms" -> Icons.Default.Sms
                        "send_sms" -> Icons.Default.Sms
                        "post_notifications" -> Icons.Default.Notifications
                        "battery_optimization" -> Icons.Default.BatteryAlert
                        else -> Icons.Default.Security
                    },
                    contentDescription = null,
                    tint = if (item.isRequired) BarProRose else BarProCyan,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = BarProTextPrimary
                )
                Text(
                    text = item.description,
                    fontSize = 10.sp,
                    color = BarProTextSecondary,
                    lineHeight = 14.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (item.isRequired) BarProRose else BarProSurfaceElevated,
                    contentColor = if (item.isRequired) BarProBg else BarProCyan
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text("تایید", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
