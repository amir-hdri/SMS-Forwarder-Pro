package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.utils.PermissionHelper
import com.example.utils.PermissionItemInfo
import com.example.utils.PermissionType

private val EmeraldGreen = Color(0xFF10B981)
private val CoralRed = Color(0xFFEF4444)
private val AmberGold = Color(0xFFF59E0B)
private val OceanBlue = Color(0xFF0EA5E9)
private val DarkCardBg = Color(0xFF0F172A)
private val DarkItemBg = Color(0xFF020617)
private val BorderMuted = Color(0xFF334155)

@Composable
fun PermissionDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var permissionsList by remember {
        mutableStateOf(PermissionHelper.getAllPermissionsStatus(context))
    }

    // Refresh permissions status when returning from system dialog or settings
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

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        permissionsList = PermissionHelper.getAllPermissionsStatus(context)
    }

    val singleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        permissionsList = PermissionHelper.getAllPermissionsStatus(context)
    }

    val grantedCount = permissionsList.count { it.isGranted }
    val totalCount = permissionsList.size
    val allCriticalGranted = PermissionHelper.areCriticalPermissionsGranted(context)
    val progress = if (totalCount > 0) grantedCount.toFloat() / totalCount.toFloat() else 1f

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .testTag("permissions_checklist_dialog"),
            colors = CardDefaults.cardColors(containerColor = DarkCardBg),
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (allCriticalGranted) EmeraldGreen.copy(alpha = 0.4f) else AmberGold.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (allCriticalGranted) EmeraldGreen.copy(alpha = 0.15f) else AmberGold.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (allCriticalGranted) Icons.Default.CheckCircle else Icons.Default.Security,
                                contentDescription = null,
                                tint = if (allCriticalGranted) EmeraldGreen else AmberGold,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "چک‌لیست مجوزهای سامانه",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                            Text(
                                text = "$grantedCount از $totalCount مجوز تایید شده است",
                                fontSize = 11.sp,
                                color = if (allCriticalGranted) EmeraldGreen else AmberGold,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "بستن", tint = Color(0xFF94A3B8))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Progress Indicator
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (allCriticalGranted) EmeraldGreen else AmberGold,
                    trackColor = DarkItemBg,
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "جهت دریافت پایدار و فوروارد خودکار پیامک‌های بارنامه و کدهای OTP به سرور مرکزی بارپرو، مجوزهای زیر لازم است:",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = Color(0xFFCBD5E1)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Checklist Items
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    permissionsList.forEach { perm ->
                        PermissionChecklistItem(
                            item = perm,
                            onGrantClick = {
                                when (perm.permissionType) {
                                    PermissionType.RUNTIME_PERMISSION -> {
                                        perm.manifestPermission?.let { manifestPerm ->
                                            singleLauncher.launch(manifestPerm)
                                        }
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

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons
                if (!allCriticalGranted) {
                    Button(
                        onClick = {
                            val runtimeToRequest = PermissionHelper.getRequiredRuntimePermissions()
                            launcher.launch(runtimeToRequest)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CoralRed,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("grant_all_permissions_btn")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "تایید و اعطای مستقیم تمامی مجوزها",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { PermissionHelper.openAppSettings(context) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF94A3B8))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تنظیمات برنامه", fontSize = 12.sp, color = Color(0xFFCBD5E1))
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (allCriticalGranted) EmeraldGreen else Color(0xFF334155),
                            contentColor = if (allCriticalGranted) DarkItemBg else Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Text(
                            text = if (allCriticalGranted) "بستن و ادامه" else "متوجه شدم",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionChecklistItem(
    item: PermissionItemInfo,
    onGrantClick: () -> Unit
) {
    val icon = when (item.id) {
        "receive_sms" -> Icons.Default.Sms
        "read_sms" -> Icons.Default.Sms
        "post_notifications" -> Icons.Default.Notifications
        "battery_optimization" -> Icons.Default.BatteryAlert
        "notification_listener" -> Icons.Default.NotificationsActive
        else -> Icons.Default.Security
    }

    val statusText = if (item.isGranted) {
        "تایید شده ✅"
    } else if (item.isRequired) {
        "الزامی ⚠️"
    } else {
        "پیشنهادی ℹ️"
    }

    val statusColor = if (item.isGranted) EmeraldGreen else if (item.isRequired) CoralRed else AmberGold

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DarkItemBg)
            .border(
                1.dp,
                if (item.isGranted) EmeraldGreen.copy(alpha = 0.25f) else BorderMuted,
                RoundedCornerShape(14.dp)
            )
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (item.isGranted) EmeraldGreen.copy(alpha = 0.15f) else CoralRed.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (item.isGranted) Icons.Default.Check else icon,
                    contentDescription = null,
                    tint = if (item.isGranted) EmeraldGreen else CoralRed,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Text Info
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
                        color = Color.White
                    )
                    Text(
                        text = statusText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.description,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = Color(0xFF94A3B8)
                )
            }

            // Direct Grant Button if not granted
            if (!item.isGranted) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onGrantClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (item.isRequired) CoralRed else AmberGold,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        text = if (item.permissionType == PermissionType.RUNTIME_PERMISSION) "اعطا" else "تنظیمات",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
