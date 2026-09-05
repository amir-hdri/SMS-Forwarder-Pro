package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.PhoneAndroid
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.utils.PermissionHelper
import com.example.utils.PermissionItemInfo
import com.example.utils.PermissionType
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
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.model.ForwardConfig
import com.example.data.model.ForwardLog
import com.example.network.ServerHealthStatus
import com.example.ui.theme.PaletteCoral
import com.example.ui.theme.PaletteGold
import com.example.ui.theme.PaletteMidnight
import com.example.ui.theme.PaletteOceanic
import com.example.ui.theme.PaletteSage

@Composable
fun DashboardScreen(
    config: ForwardConfig,
    serverHealthState: com.example.network.ServerHealthState,
    totalCount: Int = 0,
    successCount: Int = 0,
    failedCount: Int = 0,
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
                permissionsList = PermissionHelper.getAllPermissionsStatus(context)
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

    var isChecklistExpanded by remember { mutableStateOf(!isAllPermissionsGranted) }

    val isWorking = config.isMasterEnabled && isAllPermissionsGranted
    val isConnected = (serverHealthState.status == ServerHealthStatus.CONNECTED) || isWorking

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        permissionsList = PermissionHelper.getAllPermissionsStatus(context)
    }

    val singlePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        permissionsList = PermissionHelper.getAllPermissionsStatus(context)
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
        initialValue = 0.38f,
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
            .background(PaletteMidnight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ==========================================
        // 1. PERMISSIONS CHECKLIST & LIVE STATUS
        // ==========================================
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("permissions_checklist_card"),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = PaletteOceanic)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (isAllPermissionsGranted) PaletteSage.copy(alpha = 0.6f) else PaletteCoral.copy(alpha = 0.6f),
                        RoundedCornerShape(22.dp)
                    )
                    .padding(18.dp)
            ) {
                // Header row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(PaletteMidnight)
                            .border(
                                1.dp,
                                if (isAllPermissionsGranted) PaletteSage else PaletteCoral,
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isAllPermissionsGranted) Icons.Default.VerifiedUser else Icons.Default.Security,
                            contentDescription = null,
                            tint = if (isAllPermissionsGranted) PaletteSage else PaletteCoral,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "چک‌لیست مجوزهای سامانه",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = PaletteGold
                            )
                            // Live Status Badge
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isAllPermissionsGranted) PaletteSage.copy(alpha = 0.15f) else PaletteCoral.copy(alpha = 0.15f)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = if (isAllPermissionsGranted) "تایید کامل ✅" else "$grantedPermissionsCount از $totalPermissionsCount فعال",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isAllPermissionsGranted) PaletteSage else PaletteCoral
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isAllPermissionsGranted)
                                "تمام مجوزهای لازم جهت دریافت، فیلتر و فوروارد خودکار پیامک تایید شده‌اند."
                            else
                                "برای انتقال خودکار پیامک‌ها به سرور، مجوزهای مشخص‌شده در زیر را تایید کنید.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PaletteGold.copy(alpha = 0.85f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Progress Bar
                LinearProgressIndicator(
                    progress = { permissionProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (isAllPermissionsGranted) PaletteSage else PaletteCoral,
                    trackColor = PaletteMidnight
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Direct Grant All Action Button (if any critical missing)
                if (!isAllPermissionsGranted) {
                    Button(
                        onClick = {
                            val runtimeToRequest = PermissionHelper.getRequiredRuntimePermissions()
                            permissionLauncher.launch(runtimeToRequest)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PaletteCoral,
                            contentColor = PaletteMidnight
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("request_permission_button")
                    ) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "تایید و اعطای مستقیم تمامی مجوزها",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Expand / Collapse Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { isChecklistExpanded = !isChecklistExpanded }
                        .padding(vertical = 4.dp, horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isChecklistExpanded) "بستن جزئیات چک‌لیست مجوزها" else "مشاهده وضعیت تک‌تک مجوزها ($grantedPermissionsCount/$totalPermissionsCount)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = PaletteSage
                    )
                    Icon(
                        imageVector = if (isChecklistExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = PaletteSage,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Expandable Items
                AnimatedVisibility(
                    visible = isChecklistExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        permissionsList.forEach { perm ->
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

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onOpenPermissions,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                            ) {
                                Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(14.dp), tint = PaletteSage)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("پنجره کامل راهنما", fontSize = 11.sp, color = PaletteGold)
                            }
                            OutlinedButton(
                                onClick = { PermissionHelper.openAppSettings(context) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp), tint = PaletteSage)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("تنظیمات برنامه", fontSize = 11.sp, color = PaletteGold)
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // 2. HERO STATUS DISPLAY
        // ==========================================
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("service_status_indicator_card"),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = PaletteOceanic)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                PaletteOceanic,
                                PaletteMidnight
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = if (isWorking) {
                                listOf(PaletteSage.copy(alpha = 0.8f), PaletteOceanic.copy(alpha = 0.45f))
                            } else {
                                listOf(PaletteCoral.copy(alpha = 0.45f), PaletteMidnight)
                            }
                        ),
                        shape = RoundedCornerShape(28.dp)
                    )
                    .padding(24.dp)
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
                                .background(PaletteMidnight)
                                .border(
                                    1.dp,
                                    if (isWorking) PaletteSage.copy(alpha = 0.6f) else PaletteCoral.copy(alpha = 0.35f),
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isWorking) PaletteSage else PaletteCoral)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isWorking) "سامانه آنلاین" else "غیرفعال",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isWorking) PaletteSage else PaletteCoral
                            )
                        }

                        // Master switch
                        Switch(
                            checked = config.isMasterEnabled,
                            onCheckedChange = onToggleMaster,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PaletteMidnight,
                                checkedTrackColor = PaletteCoral,
                                uncheckedThumbColor = PaletteGold,
                                uncheckedTrackColor = PaletteOceanic
                            ),
                            modifier = Modifier.testTag("master_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Pulsing Glowing Center Orb with Sage (#70a288), Coral (#d5896f) and Midnight (#031d44)
                    Box(
                        modifier = Modifier.size(130.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isWorking) {
                            // Outer ambient wave (#70a288)
                            Box(
                                modifier = Modifier
                                    .size(130.dp)
                                    .scale(pulseScale)
                                    .clip(CircleShape)
                                    .background(PaletteSage.copy(alpha = waveAlpha))
                            )
                            // Inner subtle glow (#dab785)
                            Box(
                                modifier = Modifier
                                    .size(105.dp)
                                    .clip(CircleShape)
                                    .background(PaletteGold.copy(alpha = 0.22f))
                            )
                        }

                        // Center Circle
                        Box(
                            modifier = Modifier
                                .size(86.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(PaletteOceanic, PaletteMidnight)
                                    )
                                )
                                .border(
                                    2.dp,
                                    if (isWorking) PaletteSage else PaletteCoral,
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_barpro_logo),
                                contentDescription = "BarPro Logo",
                                tint = Color.Unspecified,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Title
                    Text(
                        text = if (isWorking) "سامانه فعال و متصل است" else "سرویس انتقال خاموش است",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = PaletteGold
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Subtitle
                    Text(
                        text = if (isWorking)
                            "انتقال پیامک‌ها به صورت بلادرنگ و دائمی در پس‌زمینه برقرار است"
                        else
                            "برای شروع انتقال خودکار، کلید فعال‌سازی بالای کارت را روشن کنید",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PaletteGold.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // ==========================================
        // 3. CONNECTION & RUNTIME STATUS TILES
        // ==========================================
        Text(
            text = "وضعیت لحظه‌ای ارتباط و کارکرد",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = PaletteGold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )

        // Status 0: Permissions Checklist
        StatusTile(
            icon = if (isAllPermissionsGranted) Icons.Default.VerifiedUser else Icons.Default.Security,
            iconColor = if (isAllPermissionsGranted) PaletteSage else PaletteCoral,
            title = "چک‌لیست مجوزهای سامانه",
            subtitle = if (isAllPermissionsGranted) "تمام مجوزهای الزامی پیامک و پس‌زمینه تایید شده‌اند" else "برخی مجوزهای الزامی هنوز تایید نشده است",
            statusText = "$grantedPermissionsCount از $totalPermissionsCount تایید شد",
            statusColor = if (isAllPermissionsGranted) PaletteSage else PaletteCoral,
            action = {
                IconButton(
                    onClick = onOpenPermissions,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = "مشاهده چک‌لیست مجوزها",
                        tint = PaletteGold,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        )

        // Status 1: Server Connectivity
        StatusTile(
            icon = if (isConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
            iconColor = if (isConnected) PaletteSage else PaletteCoral,
            title = "اتصال به سرور مرکزی",
            subtitle = if (isConnected) "ارتباط مستقیم با سرور برقرار و پایدار است" else "عدم دسترسی به اینترنت یا سرور",
            statusText = if (isConnected) "متصل" else "قطع",
            statusColor = if (isConnected) PaletteSage else PaletteCoral,
            action = {
                IconButton(
                    onClick = onCheckHealthNow,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "بررسی مجدد اتصال",
                        tint = PaletteGold,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        )

        // Status 2: Waybill Code Extraction Engine
        StatusTile(
            icon = Icons.Default.ElectricBolt,
            iconColor = if (isWorking) PaletteSage else PaletteCoral,
            title = "دریافت هوشمند کد بارنامه",
            subtitle = if (isWorking) "گیرنده پیامک فعال و آماده دریافت و استخراج خودکار کدهای بارنامه است" else "گیرنده در حالت غیرفعال قرار دارد",
            statusText = if (isWorking) "فعال" else "آماده‌باش",
            statusColor = if (isWorking) PaletteSage else PaletteCoral
        )

        // Status 3: Background Keep-Alive Service
        StatusTile(
            icon = Icons.Default.Shield,
            iconColor = if (isWorking) PaletteSage else PaletteCoral,
            title = "اجرای خودکار و پیوسته در پس‌زمینه",
            subtitle = "فعال بودن سرویس حتی در زمان بسته بودن برنامه و قفل گوشی",
            statusText = if (isWorking) "تضمین‌شده" else "غیرفعال",
            statusColor = if (isWorking) PaletteSage else PaletteCoral
        )

        // Status 4: End-to-End Encryption
        StatusTile(
            icon = Icons.Default.Lock,
            iconColor = PaletteGold,
            title = "رمزنگاری امن داده‌ها",
            subtitle = "تمام پیام‌ها با کلید اختصاصی و بر بستر امن منتقل می‌شوند",
            statusText = "امن (AES-256)",
            statusColor = PaletteGold
        )

        // Status 5: Driver & Fleet Identification
        StatusTile(
            icon = Icons.Default.Person,
            iconColor = PaletteSage,
            title = "مشخصات راننده و ناوگان بارپرو",
            subtitle = "شناسه: ${config.driverId} • ${config.driverFullName} • ${if (config.filterUtcmsOnly) "فیلتر هوشمند UTCMS فعال" else "فوروارد تمام پیامک‌ها"}",
            statusText = config.driverId,
            statusColor = PaletteGold
        )

        // Status 6: Device Environment Security Audit
        val securityReport = remember { com.example.utils.SecurityUtils.getSecurityReport(context) }
        StatusTile(
            icon = Icons.Default.PhoneAndroid,
            iconColor = if (securityReport.isRooted) PaletteCoral else PaletteSage,
            title = "ارزیابی امنیت محیط دستگاه",
            subtitle = securityReport.securityStatusText,
            statusText = if (securityReport.isRooted) "ریسک امنیتی" else "مورد تایید",
            statusColor = if (securityReport.isRooted) PaletteCoral else PaletteSage
        )

        // ==========================================
        // 4. QUICK ACTIONS & OFFLINE SYNC
        // ==========================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onOpenSimulate,
                colors = ButtonDefaults.buttonColors(containerColor = PaletteOceanic, contentColor = PaletteGold),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .border(1.dp, PaletteSage.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            ) {
                Text("تست پیامک", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onOpenOtpInquiry,
                colors = ButtonDefaults.buttonColors(containerColor = PaletteOceanic, contentColor = PaletteGold),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1.1f)
                    .height(44.dp)
                    .border(1.dp, PaletteSage.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            ) {
                Text("استعلام کد بارنامه", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onSyncOfflineLogs,
                colors = ButtonDefaults.buttonColors(containerColor = PaletteOceanic, contentColor = PaletteCoral),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .border(1.dp, PaletteCoral.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("همگام‌سازی", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ==========================================
        // 5. RECENT SMS ACTIVITY
        // ==========================================
        if (recentLogs.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "آخرین پیامک‌های پردازش‌شده",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = PaletteGold
                )
                Text(
                    text = "مجموع: $totalCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = PaletteGold.copy(alpha = 0.7f)
                )
            }

            recentLogs.take(5).forEach { log ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelectLog(log) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = PaletteOceanic)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, PaletteSage.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (log.status == com.example.data.model.ForwardStatus.SUCCESS) PaletteSage else PaletteCoral)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = log.sender,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PaletteGold
                                )
                            }

                            // SMS Type badge
                            val typeLabel = when (log.smsType) {
                                com.example.data.model.SmsType.UTCMS_CONFIRMATION -> "تایید بارنامه"
                                com.example.data.model.SmsType.UTCMS_OTP -> "کد تایید OTP"
                                com.example.data.model.SmsType.UTCMS_WARNING -> "هشدار سامانه"
                                com.example.data.model.SmsType.OTHER -> "پیامک عمومی"
                            }
                            val typeColor = when (log.smsType) {
                                com.example.data.model.SmsType.UTCMS_CONFIRMATION -> PaletteSage
                                com.example.data.model.SmsType.UTCMS_OTP -> PaletteGold
                                com.example.data.model.SmsType.UTCMS_WARNING -> PaletteCoral
                                com.example.data.model.SmsType.OTHER -> PaletteGold.copy(alpha = 0.6f)
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(PaletteMidnight)
                                    .border(1.dp, typeColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(text = typeLabel, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = typeColor)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = log.messageBody,
                            fontSize = 12.sp,
                            color = PaletteGold.copy(alpha = 0.9f),
                            maxLines = 2
                        )

                        if (log.trackingCode != null || log.otpCode != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                log.trackingCode?.let { code ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(PaletteMidnight)
                                            .border(1.dp, PaletteSage, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(text = "کد رهگیری: $code", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PaletteSage)
                                    }
                                }
                                log.otpCode?.let { otp ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(PaletteMidnight)
                                            .border(1.dp, PaletteGold, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(text = "کد OTP: $otp", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PaletteGold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun StatusTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    statusText: String,
    statusColor: Color,
    action: (@Composable () -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = PaletteOceanic)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, PaletteSage.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(PaletteMidnight)
                    .border(1.dp, PaletteSage.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = PaletteGold
                    )

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(PaletteMidnight)
                            .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = PaletteGold.copy(alpha = 0.82f),
                    lineHeight = 18.sp
                )
            }

            if (action != null) {
                Spacer(modifier = Modifier.width(8.dp))
                action()
            }
        }
    }
}

@Composable
private fun DashboardPermissionRow(
    item: PermissionItemInfo,
    onGrant: () -> Unit
) {
    val icon = when (item.id) {
        "receive_sms", "read_sms" -> Icons.Default.Sms
        "post_notifications" -> Icons.Default.Notifications
        "battery_optimization" -> Icons.Default.BatteryAlert
        "notification_listener" -> Icons.Default.NotificationsActive
        else -> Icons.Default.Security
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PaletteMidnight)
            .border(
                1.dp,
                if (item.isGranted) PaletteSage.copy(alpha = 0.35f) else PaletteCoral.copy(alpha = 0.35f),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        if (item.isGranted) PaletteSage.copy(alpha = 0.15f) else PaletteCoral.copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (item.isGranted) Icons.Default.Check else icon,
                    contentDescription = null,
                    tint = if (item.isGranted) PaletteSage else PaletteCoral,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = item.title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = PaletteGold
                    )
                    Text(
                        text = if (item.isGranted) "تایید شد ✅" else if (item.isRequired) "الزامی ⚠️" else "اختیاری ℹ️",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (item.isGranted) PaletteSage else if (item.isRequired) PaletteCoral else PaletteGold
                    )
                }
                Text(
                    text = item.description,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = PaletteGold.copy(alpha = 0.7f)
                )
            }

            if (!item.isGranted) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (item.isRequired) PaletteCoral else PaletteSage,
                        contentColor = PaletteMidnight
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(
                        text = if (item.permissionType == PermissionType.RUNTIME_PERMISSION) "اعطا" else "تنظیم",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
