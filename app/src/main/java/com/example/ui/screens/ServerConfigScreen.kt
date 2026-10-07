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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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

    var driverId by remember(config) { mutableStateOf(config.driverId) }
    var driverFullName by remember(config) { mutableStateOf(config.driverFullName) }
    var driverPhone by remember(config) { mutableStateOf(config.driverPhone) }

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
        // 3. PRIVACY & SECURITY ASSURANCE (امنیت و حریم خصوصی راننده)
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
