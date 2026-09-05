package com.example.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.PaletteCoral
import com.example.ui.theme.PaletteDarkest
import com.example.ui.theme.PaletteGold
import com.example.ui.theme.PaletteMidnight
import com.example.ui.theme.PaletteOceanic
import com.example.ui.theme.PalettePale
import com.example.ui.theme.PaletteSage
import com.example.utils.PermissionCategory
import com.example.utils.PermissionHelper
import com.example.utils.PermissionItemInfo
import com.example.utils.PermissionType

/**
 * Screen component that checks and lists the status of required permissions
 * (SMS, Notification, Background) with clear UI indicators showing whether each
 * is granted or denied, enhanced with Material 3 status icons (checkmarks for granted,
 * alert icons for denied).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionManagerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenBackgroundGuide: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Reactive list of all permission items
    var permissionsList by remember {
        mutableStateOf(PermissionHelper.getAllPermissionsStatus(context))
    }

    // Auto-refresh when user returns to app from system settings or permission dialogs
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

    // Permission launchers
    val multiplePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        permissionsList = PermissionHelper.getAllPermissionsStatus(context)
    }

    val singlePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        permissionsList = PermissionHelper.getAllPermissionsStatus(context)
    }

    // Filter states
    var selectedCategory by remember { mutableStateOf<PermissionCategory?>(null) }
    var selectedStatusFilter by remember { mutableStateOf<String>("ALL") } // "ALL", "GRANTED", "DENIED"

    // Metrics calculations
    val totalCount = permissionsList.size
    val grantedCount = permissionsList.count { it.isGranted }
    val deniedCount = totalCount - grantedCount
    val areAllCriticalGranted = PermissionHelper.areCriticalPermissionsGranted(context)
    val areAllGranted = permissionsList.all { it.isGranted }
    val progress = if (totalCount > 0) grantedCount.toFloat() / totalCount.toFloat() else 1f

    // Filtered items
    val filteredPermissions = permissionsList.filter { item ->
        val matchesCategory = (selectedCategory == null) || (item.category == selectedCategory)
        val matchesStatus = when (selectedStatusFilter) {
            "GRANTED" -> item.isGranted
            "DENIED" -> !item.isGranted
            else -> true
        }
        matchesCategory && matchesStatus
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("permission_manager_screen"),
        containerColor = PaletteMidnight,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "مدیریت مجوزها و دسترسی‌ها",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = PalettePale
                        )
                        Text(
                            text = "بررسی دسترسی‌های پیامک، اعلان و پس‌زمینه",
                            style = MaterialTheme.typography.labelSmall,
                            color = PalettePale.copy(alpha = 0.75f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("permission_manager_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "بازگشت",
                            tint = PalettePale
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            permissionsList = PermissionHelper.getAllPermissionsStatus(context)
                        },
                        modifier = Modifier.testTag("refresh_permissions_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "بروزرسانی وضعیت",
                            tint = PalettePale
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PaletteDarkest
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // ==============================================================
            // 1. OVERVIEW & HEALTH METRICS CARD
            // ==============================================================
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("permission_metrics_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = PaletteOceanic)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                if (areAllCriticalGranted) PaletteSage.copy(alpha = 0.5f) else PaletteCoral.copy(alpha = 0.5f),
                                RoundedCornerShape(20.dp)
                            )
                            .padding(18.dp)
                    ) {
                        // Header Status Banner
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(PaletteMidnight)
                                    .border(
                                        1.5.dp,
                                        if (areAllCriticalGranted) PaletteSage else PaletteCoral,
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (areAllCriticalGranted) Icons.Default.VerifiedUser else Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = if (areAllCriticalGranted) PaletteSage else PaletteCoral,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (areAllGranted) {
                                        "همه مجوزها با موفقیت فعال هستند"
                                    } else if (areAllCriticalGranted) {
                                        "مجوزهای اصلی فعال (برخی اختیاری غیرفعال)"
                                    } else {
                                        "نیازمند تایید مجوزهای حیاتی"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = PaletteGold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (areAllCriticalGranted) {
                                        "سیستم آماده دریافت و هدایت بلادرنگ پیامک‌ها می‌باشد."
                                    } else {
                                        "برای فوروارد خودکار پیامک‌ها باید دسترسی‌های قرمز اعطا شوند."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PaletteGold.copy(alpha = 0.8f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Progress Bar
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "میزان آمادگی مجوزها",
                                    fontSize = 12.sp,
                                    color = PaletteGold.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = "${(progress * 100).toInt()}% ($grantedCount از $totalCount)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (areAllCriticalGranted) PaletteSage else PaletteCoral
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = if (areAllCriticalGranted) PaletteSage else PaletteCoral,
                                trackColor = PaletteMidnight
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 3 Key Indicator Tiles (Total, Granted with Checkmark, Denied with Alert)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Total Tile
                            MetricTile(
                                title = "کل دسترسی‌ها",
                                count = totalCount,
                                icon = Icons.Default.Security,
                                iconColor = PaletteGold,
                                containerColor = PaletteMidnight,
                                modifier = Modifier.weight(1f)
                            )

                            // Granted Tile (Checkmark)
                            MetricTile(
                                title = "تایید شده",
                                count = grantedCount,
                                icon = Icons.Default.CheckCircle,
                                iconColor = PaletteSage,
                                containerColor = PaletteSage.copy(alpha = 0.12f),
                                borderColor = PaletteSage.copy(alpha = 0.35f),
                                modifier = Modifier.weight(1f)
                            )

                            // Denied Tile (Alert)
                            MetricTile(
                                title = "رد شده / متوقف",
                                count = deniedCount,
                                icon = if (deniedCount > 0) Icons.Default.Warning else Icons.Default.Check,
                                iconColor = if (deniedCount > 0) PaletteCoral else PaletteSage,
                                containerColor = if (deniedCount > 0) PaletteCoral.copy(alpha = 0.12f) else PaletteMidnight,
                                borderColor = if (deniedCount > 0) PaletteCoral.copy(alpha = 0.35f) else PaletteSage.copy(alpha = 0.2f),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Quick action button to request all if any missing
                        if (!areAllCriticalGranted) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    val runtimeToRequest = PermissionHelper.getRequiredRuntimePermissions()
                                    multiplePermissionLauncher.launch(runtimeToRequest)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PaletteCoral,
                                    contentColor = PaletteMidnight
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .testTag("grant_all_permissions_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "تایید و اعطای مستقیم تمامی مجوزهای حیاتی",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // ==============================================================
            // 2. CATEGORY & STATUS FILTER CHIPS
            // ==============================================================
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "دسته‌بندی‌های مجوز (SMS، اعلان، پس‌زمینه)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = PaletteGold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            FilterChip(
                                selected = selectedCategory == null,
                                onClick = { selectedCategory = null },
                                label = { Text("همه دسته‌ها ($totalCount)") },
                                leadingIcon = {
                                    Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PaletteSage,
                                    selectedLabelColor = PaletteMidnight,
                                    selectedLeadingIconColor = PaletteMidnight,
                                    containerColor = PaletteOceanic,
                                    labelColor = PaletteGold
                                ),
                                shape = RoundedCornerShape(10.dp)
                            )
                        }

                        item {
                            val smsCount = permissionsList.count { it.category == PermissionCategory.SMS }
                            FilterChip(
                                selected = selectedCategory == PermissionCategory.SMS,
                                onClick = { selectedCategory = PermissionCategory.SMS },
                                label = { Text("پیامک (SMS) ($smsCount)") },
                                leadingIcon = {
                                    Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PaletteSage,
                                    selectedLabelColor = PaletteMidnight,
                                    selectedLeadingIconColor = PaletteMidnight,
                                    containerColor = PaletteOceanic,
                                    labelColor = PaletteGold
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("filter_chip_sms")
                            )
                        }

                        item {
                            val notifCount = permissionsList.count { it.category == PermissionCategory.NOTIFICATION }
                            FilterChip(
                                selected = selectedCategory == PermissionCategory.NOTIFICATION,
                                onClick = { selectedCategory = PermissionCategory.NOTIFICATION },
                                label = { Text("اعلان‌ها (Notification) ($notifCount)") },
                                leadingIcon = {
                                    Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PaletteSage,
                                    selectedLabelColor = PaletteMidnight,
                                    selectedLeadingIconColor = PaletteMidnight,
                                    containerColor = PaletteOceanic,
                                    labelColor = PaletteGold
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("filter_chip_notification")
                            )
                        }

                        item {
                            val bgCount = permissionsList.count { it.category == PermissionCategory.BACKGROUND }
                            FilterChip(
                                selected = selectedCategory == PermissionCategory.BACKGROUND,
                                onClick = { selectedCategory = PermissionCategory.BACKGROUND },
                                label = { Text("پس‌زمینه (Background) ($bgCount)") },
                                leadingIcon = {
                                    Icon(Icons.Default.BatteryAlert, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PaletteSage,
                                    selectedLabelColor = PaletteMidnight,
                                    selectedLeadingIconColor = PaletteMidnight,
                                    containerColor = PaletteOceanic,
                                    labelColor = PaletteGold
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("filter_chip_background")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Secondary status filter tabs: All vs Granted vs Denied
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(PaletteMidnight)
                            .border(1.dp, PaletteOceanic, RoundedCornerShape(10.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        StatusFilterButton(
                            title = "همه",
                            count = permissionsList.size,
                            isSelected = selectedStatusFilter == "ALL",
                            onClick = { selectedStatusFilter = "ALL" },
                            modifier = Modifier.weight(1f)
                        )
                        StatusFilterButton(
                            title = "تایید شده",
                            count = grantedCount,
                            icon = Icons.Default.CheckCircle,
                            iconColor = PaletteSage,
                            isSelected = selectedStatusFilter == "GRANTED",
                            onClick = { selectedStatusFilter = "GRANTED" },
                            modifier = Modifier.weight(1.1f)
                        )
                        StatusFilterButton(
                            title = "رد شده / اقدام",
                            count = deniedCount,
                            icon = Icons.Default.Warning,
                            iconColor = PaletteCoral,
                            isSelected = selectedStatusFilter == "DENIED",
                            onClick = { selectedStatusFilter = "DENIED" },
                            modifier = Modifier.weight(1.2f)
                        )
                    }
                }
            }

            // ==============================================================
            // 3. ENHANCED PERMISSIONS STATUS LIST WITH MATERIAL 3 ICONS
            // ==============================================================
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "فهرست وضعیت مجوزها (${filteredPermissions.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = PaletteGold
                    )
                    Text(
                        text = "به‌روزرسانی خودکار با بازگشت به برنامه",
                        fontSize = 11.sp,
                        color = PaletteSage
                    )
                }
            }

            if (filteredPermissions.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = PaletteOceanic)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "موردی با فیلتر انتخاب‌شده یافت نشد.",
                                color = PaletteGold.copy(alpha = 0.7f),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            } else {
                items(filteredPermissions, key = { it.id }) { item ->
                    PermissionStatusCard(
                        item = item,
                        onGrant = {
                            when (item.permissionType) {
                                PermissionType.RUNTIME_PERMISSION -> {
                                    item.manifestPermission?.let { singlePermissionLauncher.launch(it) }
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

            // ==============================================================
            // 4. FOOTER SYSTEM SETTINGS SHORTCUTS
            // ==============================================================
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = PaletteOceanic)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, PaletteSage.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = PaletteSage,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "دسترسی مستقیم به تنظیمات پیشرفته اندروید",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = PaletteGold
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { PermissionHelper.openAppSettings(context) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .testTag("open_app_settings_button"),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = PaletteGold
                                )
                            ) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp), tint = PaletteSage)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("تنظیمات برنامه در اندروید", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }

                            if (onOpenBackgroundGuide != null) {
                                OutlinedButton(
                                    onClick = onOpenBackgroundGuide,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                        .testTag("open_background_guide_button"),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = PaletteGold
                                    )
                                ) {
                                    Icon(Icons.Default.ElectricBolt, contentDescription = null, modifier = Modifier.size(15.dp), tint = PaletteSage)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("راهنمای پس‌زمینه", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Metric summary tile showing count, label, and status icon.
 */
@Composable
private fun MetricTile(
    title: String,
    count: Int,
    icon: ImageVector,
    iconColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier,
    borderColor: Color? = null
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .then(
                if (borderColor != null) Modifier.border(1.dp, borderColor, RoundedCornerShape(14.dp))
                else Modifier
            )
            .padding(vertical = 12.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = count.toString(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = iconColor
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = PaletteGold.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Filter tab button for status.
 */
@Composable
private fun StatusFilterButton(
    title: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconColor: Color = PaletteGold
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) PaletteOceanic else Color.Transparent,
        animationSpec = tween(200),
        label = "tabBg"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) iconColor else iconColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
            }
            Text(
                text = "$title ($count)",
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) PaletteGold else PaletteGold.copy(alpha = 0.65f)
            )
        }
    }
}

/**
 * Individual Permission Item Card with UI indicators showing whether it is granted or denied,
 * enhanced with Material 3 status icons (checkmarks for granted, alert icons for denied).
 */
@Composable
private fun PermissionStatusCard(
    item: PermissionItemInfo,
    onGrant: () -> Unit
) {
    val categoryIcon = when (item.category) {
        PermissionCategory.SMS -> Icons.Default.Sms
        PermissionCategory.NOTIFICATION -> if (item.id == "notification_listener") Icons.Default.NotificationsActive else Icons.Default.Notifications
        PermissionCategory.BACKGROUND -> Icons.Default.BatteryAlert
    }

    val categoryLabel = when (item.category) {
        PermissionCategory.SMS -> "پیامک"
        PermissionCategory.NOTIFICATION -> "اعلان"
        PermissionCategory.BACKGROUND -> "پس‌زمینه"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("permission_item_${item.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = PaletteOceanic
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = if (item.isGranted) {
                        PaletteSage.copy(alpha = 0.45f)
                    } else if (item.isRequired) {
                        PaletteCoral.copy(alpha = 0.7f)
                    } else {
                        PaletteGold.copy(alpha = 0.35f)
                    },
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(14.dp)
        ) {
            // Top row: Leading capability icon, title, and Granted/Denied status indicator badge
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Capability Icon
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(PaletteMidnight)
                        .border(
                            1.dp,
                            if (item.isGranted) PaletteSage.copy(alpha = 0.6f) else PaletteCoral.copy(alpha = 0.6f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = categoryIcon,
                        contentDescription = null,
                        tint = if (item.isGranted) PaletteSage else PaletteCoral,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // Category & Requirement Chips
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Category Badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(PaletteMidnight)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = categoryLabel,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PaletteGold
                            )
                        }

                        // Requirement Badge
                        if (item.isRequired) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(PaletteCoral.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "الزامی",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PaletteCoral
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(PaletteSage.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "اختیاری / مکمل",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = PaletteSage
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Title
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = PaletteGold
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // ==============================================================
                // MATERIAL 3 STATUS INDICATOR BADGE (Checkmark vs Alert Icon)
                // ==============================================================
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (item.isGranted) PaletteSage.copy(alpha = 0.18f)
                            else PaletteCoral.copy(alpha = 0.18f)
                        )
                        .border(
                            1.dp,
                            if (item.isGranted) PaletteSage.copy(alpha = 0.6f)
                            else PaletteCoral.copy(alpha = 0.6f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (item.isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = if (item.isGranted) "تایید شده" else "رد شده یا غیرفعال",
                            tint = if (item.isGranted) PaletteSage else PaletteCoral,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (item.isGranted) "تایید شده" else "رد شده",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (item.isGranted) PaletteSage else PaletteCoral
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Permission Description
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodySmall,
                color = PaletteGold.copy(alpha = 0.85f),
                lineHeight = 18.sp
            )

            // Technical manifest identifier if available
            if (item.manifestPermission != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = item.manifestPermission,
                    fontSize = 10.sp,
                    color = PaletteGold.copy(alpha = 0.5f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action row: Status details & Grant Button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (item.isGranted) Icons.Default.Check else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (item.isGranted) PaletteSage else PaletteCoral,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (item.isGranted) "دسترسی سیستمی برقرار است"
                        else if (item.isRequired) "سیستم بدون این مجوز کار نخواهد کرد"
                        else "فعال‌سازی برای عملکرد پایدار توصیه می‌شود",
                        fontSize = 11.sp,
                        color = if (item.isGranted) PaletteSage else if (item.isRequired) PaletteCoral else PaletteGold.copy(alpha = 0.7f)
                    )
                }

                if (!item.isGranted) {
                    Button(
                        onClick = onGrant,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (item.isRequired) PaletteCoral else PaletteSage,
                            contentColor = PaletteMidnight
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("grant_button_${item.id}")
                    ) {
                        Icon(
                            imageVector = when (item.permissionType) {
                                PermissionType.RUNTIME_PERMISSION -> Icons.Default.Check
                                PermissionType.BATTERY_OPTIMIZATION -> Icons.Default.BatteryAlert
                                PermissionType.NOTIFICATION_LISTENER -> Icons.Default.NotificationsActive
                            },
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = when (item.permissionType) {
                                PermissionType.RUNTIME_PERMISSION -> "اعطای مجوز"
                                PermissionType.BATTERY_OPTIMIZATION -> "استثنا از باتری"
                                PermissionType.NOTIFICATION_LISTENER -> "تنظیم شنود"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
