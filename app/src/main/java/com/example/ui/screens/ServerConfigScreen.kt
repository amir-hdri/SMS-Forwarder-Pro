package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AuthType
import com.example.data.model.ForwardConfig
import com.example.network.ServerHealthStatus
import com.example.ui.theme.BarProAmber
import com.example.ui.theme.BarProAmberBg
import com.example.ui.theme.BarProBg
import com.example.ui.theme.BarProBorder
import com.example.ui.theme.BarProBorderCyan
import com.example.ui.theme.BarProCyan
import com.example.ui.theme.BarProEmerald
import com.example.ui.theme.BarProRose
import com.example.ui.theme.BarProSurface
import com.example.ui.theme.BarProSurfaceElevated
import com.example.ui.theme.BarProSurfaceSubtle
import com.example.ui.theme.BarProTextMuted
import com.example.ui.theme.BarProTextPrimary
import com.example.ui.theme.BarProTextSecondary
import com.example.ui.viewmodel.EncryptionSandboxState
import com.example.ui.viewmodel.EndpointTestState

@Composable
fun ServerConfigScreen(
    config: ForwardConfig,
    serverHealthState: com.example.network.ServerHealthState,
    testState: EndpointTestState,
    cryptoSandboxState: EncryptionSandboxState,
    onSaveConfig: (ForwardConfig) -> Unit,
    onRunTest: (url: String, authType: AuthType, authHeaderKey: String, authHeaderValue: String, isEnc: Boolean, secretKey: String) -> Unit,
    onCheckServerHealth: () -> Unit,
    onTestDisconnectNotification: () -> Unit,
    onTestCryptoSandbox: (plaintext: String, secretKey: String) -> Unit,
    onGenerateKey: () -> String,
    onOpenServerGuide: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val probeScope = rememberCoroutineScope()
    val probeClient = remember { com.example.network.SmsForwarderClient() }
    var probeRunning by remember { mutableStateOf(false) }
    var permissionRevision by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionSnapshot = remember(permissionRevision) {
        Triple(
            com.example.utils.PermissionHelper.isReceiveSmsGranted(context),
            com.example.utils.PermissionHelper.isSendSmsGranted(context),
            com.example.utils.PermissionHelper.isBatteryOptimizationIgnored(context)
        )
    }

    var driverId by remember(config) { mutableStateOf(config.driverId) }
    var driverFullName by remember(config) { mutableStateOf(config.driverFullName) }
    var driverPhone by remember(config) { mutableStateOf(config.driverPhone) }
    var primarySmsRelayEnabled by remember(config) { mutableStateOf(config.primarySmsRelayEnabled) }
    var fallbackServerPhoneNumber by remember(config) { mutableStateOf(config.fallbackServerPhoneNumber) }
    var endpointUrl by remember(config) { mutableStateOf(config.endpointUrl) }
    var authHeaderValue by remember(config) { mutableStateOf(config.authHeaderValue) }
    var allowCleartextTransport by remember(config) { mutableStateOf(config.allowCleartextTransport) }
    var testSmsProbeMessage by remember { mutableStateOf<String?>(null) }
    var showAdvancedSettings by remember {
        mutableStateOf(
            !config.endpointUrl.trim().startsWith("https://", ignoreCase = true) ||
            config.allowCleartextTransport ||
            config.endpointUrl != "https://api.barpro.ir/api/v1/otp/sms-forwarder"
        )
    }

    val isConnected = serverHealthState.status == ServerHealthStatus.CONNECTED

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BarProBg)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
        }

        // ==========================================
        // 1. DRIVER PROFILE CARD (مشخصات راننده)
        // ==========================================
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("driver_profile_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = BarProSurface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BarProBorderCyan, RoundedCornerShape(20.dp))
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(BarProSurfaceSubtle)
                                .border(1.dp, BarProBorderCyan, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = BarProCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "مشخصات راننده و ناوگان بارپرو",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = BarProTextPrimary
                            )
                            Text(
                                text = "اطلاعات هویتی جهت ثبت خودکار بارنامه‌ها در سرور",
                                style = MaterialTheme.typography.labelSmall,
                                color = BarProTextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = driverFullName,
                        onValueChange = {
                            driverFullName = it
                            onSaveConfig(config.copy(driverFullName = it))
                        },
                        label = { Text("نام و نام خانوادگی راننده") },
                        placeholder = { Text("مثال: علی محمدی") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BarProCyan,
                            unfocusedBorderColor = BarProBorder,
                            focusedTextColor = BarProTextPrimary,
                            unfocusedTextColor = BarProTextPrimary,
                            focusedContainerColor = BarProSurfaceSubtle,
                            unfocusedContainerColor = BarProSurfaceSubtle
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = driverId,
                        onValueChange = {
                            driverId = it
                            onSaveConfig(config.copy(driverId = it))
                        },
                        label = { Text("کد ملی / شناسه راننده") },
                        placeholder = { Text("مثال: ۰۰۱۲۳۴۵۶۷۸") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BarProCyan,
                            unfocusedBorderColor = BarProBorder,
                            focusedTextColor = BarProTextPrimary,
                            unfocusedTextColor = BarProTextPrimary,
                            focusedContainerColor = BarProSurfaceSubtle,
                            unfocusedContainerColor = BarProSurfaceSubtle
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = driverPhone,
                        onValueChange = {
                            driverPhone = it
                            onSaveConfig(config.copy(driverPhone = it))
                        },
                        label = { Text("شماره همراه راننده (سیم‌کارت فعال در گوشی)") },
                        placeholder = { Text("مثال: ۰۹۱۲۳۴۵۶۷۸۹") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BarProCyan,
                            unfocusedBorderColor = BarProBorder,
                            focusedTextColor = BarProTextPrimary,
                            unfocusedTextColor = BarProTextPrimary,
                            focusedContainerColor = BarProSurfaceSubtle,
                            unfocusedContainerColor = BarProSurfaceSubtle
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    val normalizedPhone = com.example.utils.SmsParser.normalizePhoneNumber(driverPhone)
                    if (driverPhone.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "فرمت شناسایی سامانه: $normalizedPhone",
                            fontSize = 11.sp,
                            color = if (normalizedPhone.startsWith("09") && normalizedPhone.length == 11) BarProEmerald else BarProRose
                        )
                    }
                }
            }
        }

        // ==========================================
        // 2. SERVER CONNECTION STATUS (وضعیت اتصال به سرور بارپرو)
        // ==========================================
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("server_status_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = BarProSurface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BarProBorder, RoundedCornerShape(20.dp))
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(BarProSurfaceSubtle)
                                    .border(1.dp, if (isConnected) BarProEmerald.copy(alpha = 0.4f) else BarProRose.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isConnected) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                    contentDescription = null,
                                    tint = if (isConnected) BarProEmerald else BarProRose,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "ارتباط با سرور بارپرو",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = BarProTextPrimary
                                )
                                Text(
                                    text = "پذیرش خودکار و آنی کدهای اعتبارسنجی بارنامه",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BarProTextSecondary
                                )
                            }
                        }

                        // Live status pill
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isConnected) BarProEmerald.copy(alpha = 0.15f) else BarProRose.copy(alpha = 0.15f))
                                .border(1.dp, if (isConnected) BarProEmerald.copy(alpha = 0.4f) else BarProRose.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (isConnected) BarProEmerald else BarProRose)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (isConnected) "آنلاین و متصل" else "قطع ارتباط",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isConnected) BarProEmerald else BarProRose
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "تنظیمات وب‌هوک و امنیت به صورت خودکار و از پیش‌تعیین‌شده توسط بارپرو مدیریت می‌شوند و راننده نیازی به تنظیم هیچ آدرس یا کدی ندارد.",
                        fontSize = 12.sp,
                        color = BarProTextSecondary,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Check Health Button
                    Button(
                        onClick = onCheckServerHealth,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BarProSurfaceElevated,
                            contentColor = BarProCyan
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .border(1.dp, BarProBorderCyan, RoundedCornerShape(12.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("بررسی مجدد اتصال به سرور بارپرو", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    testState.result?.let { res ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(BarProSurfaceSubtle)
                                .border(
                                    1.dp,
                                    if (res.isSuccess) BarProEmerald.copy(alpha = 0.5f) else BarProRose.copy(alpha = 0.5f),
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (res.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                    contentDescription = null,
                                    tint = if (res.isSuccess) BarProEmerald else BarProRose,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (res.isSuccess) "اتصال با موفقیت تأیید شد (${res.durationMs} میلی‌ثانیه)" else "خطا در اتصال: ${res.errorMessage ?: "سرور در دسترس نیست"}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (res.isSuccess) BarProEmerald else BarProRose
                                )
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // 2.5 PRIMARY SMS RELAY (ارسال همیشگی با پیامک به درگاه سرور)
        // ==========================================
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("primary_sms_relay_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = BarProSurface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BarProBorderCyan, RoundedCornerShape(20.dp))
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(BarProSurfaceSubtle)
                                    .border(1.dp, BarProBorderCyan, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Phone,
                                    contentDescription = null,
                                    tint = BarProCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "ارسال همیشگی با پیامک (Primary SMS Relay)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = BarProTextPrimary
                                )
                                Text(
                                    text = "ارسال آنی کدهای استخراج‌شده به شماره سیم‌کارت سرور",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BarProTextSecondary
                                )
                            }
                        }

                        Switch(
                            checked = primarySmsRelayEnabled,
                            onCheckedChange = {
                                primarySmsRelayEnabled = it
                                onSaveConfig(config.copy(primarySmsRelayEnabled = it))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = BarProCyan,
                                checkedTrackColor = BarProCyan.copy(alpha = 0.3f),
                                uncheckedThumbColor = BarProTextMuted,
                                uncheckedTrackColor = BarProSurfaceElevated
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "با فعال‌سازی این حالت، به محض دریافت پیامک از سرشماره‌های راهداری (۲۰۰۰۷۷۷۷ و...)، کد OTP با قالب امن BP1#... مستقیماً با پیامک به درگاه سرور فرستاده می‌شود. این مسیر به اینترنت گوشی نیاز ندارد؛ تحویل به آنتن‌دهی، اعتبار سیم‌کارت و دسترسی درگاه بستگی دارد. امضای BP1 اصالت پیام را کنترل می‌کند و متن آن رمزنگاری نشده است.",
                        fontSize = 12.sp,
                        color = BarProTextSecondary,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = fallbackServerPhoneNumber,
                        onValueChange = {
                            fallbackServerPhoneNumber = it
                            onSaveConfig(config.copy(fallbackServerPhoneNumber = it))
                        },
                        label = { Text("شماره سیم‌کارت درگاه سرور (دفتر)") },
                        placeholder = { Text("مثال: ۰۹۱۲۳۴۵۶۷۸۹") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BarProCyan,
                            unfocusedBorderColor = BarProBorder,
                            focusedTextColor = BarProTextPrimary,
                            unfocusedTextColor = BarProTextPrimary,
                            focusedContainerColor = BarProSurfaceSubtle,
                            unfocusedContainerColor = BarProSurfaceSubtle
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // ==========================================
        // 2.6 ON-DEMAND HEALTH & PERMISSIONS AUDIT (بررسی سلامت و مجوزها - تست دستی)
        // ==========================================
        item {
            val hasReceiveSms = permissionSnapshot.first
            val hasSendSms = permissionSnapshot.second
            val hasBatteryOpt = permissionSnapshot.third
            val allReady = hasReceiveSms && hasSendSms && hasBatteryOpt

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("permissions_audit_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = BarProSurface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, if (allReady) BarProEmerald.copy(alpha = 0.4f) else BarProAmber.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(BarProSurfaceSubtle)
                                .border(1.dp, if (allReady) BarProEmerald else BarProAmber, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = if (allReady) BarProEmerald else BarProAmber,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "بررسی سلامت و مجوزها (تست درخواستی)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = BarProTextPrimary
                            )
                            Text(
                                text = "اطمینان از دسترسی‌های کامل و ارسال تست دستی در مواقع لزوم",
                                style = MaterialTheme.typography.labelSmall,
                                color = BarProTextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Permission status items
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(BarProSurfaceSubtle)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("دریافت پیامک (RECEIVE_SMS):", fontSize = 12.sp, color = BarProTextPrimary)
                            Text(
                                text = if (hasReceiveSms) "✅ تأیید شده" else "❌ نیاز به مجوز",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (hasReceiveSms) BarProEmerald else BarProRose
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("ارسال پیامک رله (SEND_SMS):", fontSize = 12.sp, color = BarProTextPrimary)
                            Text(
                                text = if (hasSendSms) "✅ تأیید شده" else "❌ نیاز به مجوز",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (hasSendSms) BarProEmerald else BarProRose
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("استثنای باتری پس‌زمینه (Doze Mode):", fontSize = 12.sp, color = BarProTextPrimary)
                            Text(
                                text = if (hasBatteryOpt) "✅ بدون محدودیت" else "⚠️ بهینه‌سازی فعال",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (hasBatteryOpt) BarProEmerald else BarProAmber
                            )
                        }
                    }

                    if (!allReady) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (!hasReceiveSms || !hasSendSms) {
                                Button(
                                    onClick = { com.example.utils.PermissionHelper.openAppSettings(context) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = BarProSurfaceElevated,
                                        contentColor = BarProCyan
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Text("اعطای مجوز پیامک", fontSize = 11.sp)
                                }
                            }
                            if (!hasBatteryOpt) {
                                Button(
                                    onClick = { com.example.utils.PermissionHelper.requestBatteryOptimization(context) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = BarProSurfaceElevated,
                                        contentColor = BarProAmber
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Text("حذف محدودیت باتری", fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        "تست اینترنت و گزارش مجوزها به فعال بودن کلید اصلی نیاز دارد. تست پیامکی از سیم‌کارت پیش‌فرض ارسال می‌شود؛ شماره آن باید با شماره راننده یکسان باشد. تأیید دریافت سرور به اینترنت نیاز دارد.",
                        fontSize = 11.sp, color = BarProTextSecondary
                    )
                    // Action buttons: Probe Ping & Test SMS
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                permissionRevision++
                                onCheckServerHealth()
                            },
                            enabled = config.isMasterEnabled && config.endpointUrl.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BarProSurfaceElevated,
                                contentColor = BarProEmerald
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .border(1.dp, BarProEmerald.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تست سلامت و اتصال", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            enabled = !probeRunning,
                            onClick = {
                                permissionRevision++
                                if (fallbackServerPhoneNumber.isBlank()) {
                                    testSmsProbeMessage = "ابتدا شماره سیم‌کارت درگاه سرور را در بالا وارد کنید."
                                } else if (!com.example.utils.PermissionHelper.isSendSmsGranted(context)) {
                                    testSmsProbeMessage = "ابتدا مجوز ارسال پیامک (SEND_SMS) را اعطا کنید."
                                } else {
                                    val timestamp = System.currentTimeMillis()
                                    val ok = com.example.utils.SmsRelayHelper.sendFallbackSms(
                                        context = context,
                                        destinationPhone = fallbackServerPhoneNumber,
                                        driverId = config.driverId,
                                        driverPhone = config.driverPhone,
                                        code = "TEST",
                                        smsType = "TEST",
                                        simSlot = -1,
                                        receivedTimestamp = timestamp,
                                        webhookSecret = com.example.network.BarProContract.token(config)
                                    )
                                    testSmsProbeMessage = if (ok) {
                                        "درخواست به مودم تحویل شد؛ در انتظار تأیید دریافت سرور…"
                                    } else {
                                        "خطا در تحویل درخواست پیامک آزمایشی به مودم."
                                    }
                                    if (ok) {
                                        probeRunning = true
                                        probeScope.launch {
                                            try {
                                                val received = withTimeoutOrNull(30_000) {
                                                    repeat(6) {
                                                        delay(2000)
                                                        val receipt = probeClient.checkSmsProbeReceipt(config, timestamp)
                                                        if (receipt.isSuccess) return@withTimeoutOrNull true
                                                        if (receipt.httpStatusCode != 200) return@withTimeoutOrNull false
                                                    }
                                                    false
                                                } ?: false
                                                testSmsProbeMessage = if (received) {
                                                    "✅ سرور دریافت همین پیامک آزمایشی را تأیید کرد."
                                                } else {
                                                    "تحویل درخواست به مودم انجام شد؛ دریافت سرور تأیید نشد. ممکن است اینترنت، درگاه یا ارسال پیامک در دسترس نباشد. پیامک خودکار تکرار نمی‌شود."
                                                }
                                            } finally {
                                                probeRunning = false
                                            }
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BarProSurfaceElevated,
                                contentColor = BarProCyan
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .border(1.dp, BarProBorderCyan, RoundedCornerShape(12.dp))
                        ) {
                            Icon(imageVector = Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (probeRunning) "در انتظار رسید…" else "ارسال پیامک تستی", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    testSmsProbeMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = msg,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (msg.startsWith("✅")) BarProEmerald else BarProAmber
                        )
                    }
                }
            }
        }

        // ==========================================
        // 3. OPERATOR & ADVANCED SERVER SETTINGS (تنظیمات پیشرفته سرور - مخصوص اپراتور)
        // ==========================================
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("advanced_settings_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = BarProSurface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, if (showAdvancedSettings) BarProBorderCyan else BarProBorder, RoundedCornerShape(20.dp))
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(BarProSurfaceSubtle)
                                    .border(1.dp, BarProBorder, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = BarProCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "تنظیمات پیشرفته سرور و اتصال",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = BarProTextPrimary
                                )
                                Text(
                                    text = "تغییر آدرس سرور، هماهنگی توکن و تأیید اتصال HTTP",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BarProTextSecondary
                                )
                            }
                        }

                        IconButton(
                            onClick = { showAdvancedSettings = !showAdvancedSettings }
                        ) {
                            Icon(
                                imageVector = if (showAdvancedSettings) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (showAdvancedSettings) "بستن" else "باز کردن",
                                tint = BarProCyan
                            )
                        }
                    }

                    if (showAdvancedSettings) {
                        Spacer(modifier = Modifier.height(16.dp))

                        // Server URL Field
                        OutlinedTextField(
                            value = endpointUrl,
                            onValueChange = {
                                endpointUrl = it
                                onSaveConfig(config.copy(endpointUrl = it))
                            },
                            label = { Text("آدرس وب‌هوک بارپرو (Endpoint URL)") },
                            placeholder = { Text("https://api.barpro.ir/api/v1/otp/sms-forwarder") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BarProCyan,
                                unfocusedBorderColor = BarProBorder,
                                focusedTextColor = BarProTextPrimary,
                                unfocusedTextColor = BarProTextPrimary,
                                focusedContainerColor = BarProSurfaceSubtle,
                                unfocusedContainerColor = BarProSurfaceSubtle
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("server_url_input")
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // Reset to default button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    val defaultUrl = "https://api.barpro.ir/api/v1/otp/sms-forwarder"
                                    endpointUrl = defaultUrl
                                    onSaveConfig(config.copy(endpointUrl = defaultUrl))
                                }
                            ) {
                                Text(
                                    text = "بازنشانی به آدرس پیش‌فرض بارپرو",
                                    fontSize = 11.sp,
                                    color = BarProCyan
                                )
                            }
                        }

                        // Cleartext / HTTP Warning & Toggle
                        val isHttps = endpointUrl.trim().startsWith("https://", ignoreCase = true)
                        if (!isHttps && endpointUrl.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(BarProAmberBg)
                                    .border(1.dp, BarProAmber.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "تأیید اتصال ناامن HTTP (مخصوص بارپرو فعلی)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BarProAmber
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "سرور بارپرو در حال حاضر بدون HTTPS (روی پورت ۸۰) کار می‌کند؛ برای امکان ارسال به این آدرس باید این گزینه فعال باشد.",
                                        fontSize = 11.sp,
                                        color = BarProTextSecondary,
                                        lineHeight = 16.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Switch(
                                    checked = allowCleartextTransport,
                                    onCheckedChange = {
                                        allowCleartextTransport = it
                                        onSaveConfig(config.copy(allowCleartextTransport = it))
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = BarProAmber,
                                        checkedTrackColor = BarProAmber.copy(alpha = 0.3f),
                                        uncheckedThumbColor = BarProTextMuted,
                                        uncheckedTrackColor = BarProSurfaceElevated
                                    ),
                                    modifier = Modifier.testTag("cleartext_switch")
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Auth Header / Webhook Token Field
                        OutlinedTextField(
                            value = authHeaderValue,
                            onValueChange = {
                                authHeaderValue = it
                                onSaveConfig(config.copy(authHeaderValue = it, forwarderSecret = it))
                            },
                            label = { Text("کلید امنیتی وب‌هوک (X-OTP-Webhook-Token)") },
                            placeholder = { Text("barpro-fleet-secure-token") },
                            singleLine = true,
                            supportingText = {
                                Text(
                                    text = "این مقدار باید دقیقاً با OTP_WEBHOOK_SECRET روی سرور FastAPI یکسان باشد (خطای ۴۰۱ در صورت مغایرت)",
                                    fontSize = 11.sp,
                                    color = BarProTextSecondary
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BarProCyan,
                                unfocusedBorderColor = BarProBorder,
                                focusedTextColor = BarProTextPrimary,
                                unfocusedTextColor = BarProTextPrimary,
                                focusedContainerColor = BarProSurfaceSubtle,
                                unfocusedContainerColor = BarProSurfaceSubtle
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("server_token_input")
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Quick Test & Guide Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    onRunTest(
                                        endpointUrl,
                                        config.authType,
                                        config.authHeaderKey,
                                        authHeaderValue,
                                        config.isEncryptionEnabled,
                                        config.secretEncryptionKey
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BarProCyan,
                                    contentColor = BarProBg
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("quick_test_button")
                            ) {
                                Text("تست ارسال به سرور", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = onOpenServerGuide,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BarProSurfaceElevated,
                                    contentColor = BarProTextPrimary
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .border(1.dp, BarProBorder, RoundedCornerShape(12.dp))
                            ) {
                                Text("راهنمای وب‌هوک", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    } else {
                        // Collapsed summary preview
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "آدرس فعلی: ${endpointUrl.ifBlank { "پیش‌فرض بارپرو" }}",
                            fontSize = 11.sp,
                            color = BarProTextMuted
                        )
                    }
                }
            }
        }

        // ==========================================
        // 4. PRIVACY & SECURITY ASSURANCE (امنیت و حریم خصوصی راننده)
        // ==========================================
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("privacy_security_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = BarProSurface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BarProBorder, RoundedCornerShape(20.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(BarProSurfaceSubtle)
                            .border(1.dp, BarProBorder, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = BarProEmerald,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "حفظ حریم خصوصی و امنیت پیامک‌ها",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = BarProTextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "سیستم به صورت هوشمند تنها کدهای اعتبارسنجی بارنامه را فیلتر کرده و هیچ‌گونه پیامک شخصی یا بانکی خوانده یا ارسال نمی‌شود.",
                            style = MaterialTheme.typography.bodySmall,
                            color = BarProTextSecondary,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
