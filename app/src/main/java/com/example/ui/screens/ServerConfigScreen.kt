package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Https
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AuthType
import com.example.data.model.ForwardConfig
import com.example.ui.theme.Cyan400
import com.example.ui.theme.Emerald400
import com.example.ui.theme.Indigo400
import com.example.ui.theme.Rose400
import com.example.ui.theme.Sky400
import com.example.ui.theme.Slate300
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950
import com.example.ui.viewmodel.EncryptionSandboxState
import com.example.ui.viewmodel.EndpointTestState

@OptIn(ExperimentalMaterial3Api::class)
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

    var url by remember(config) { mutableStateOf(config.endpointUrl) }
    var authType by remember(config) { mutableStateOf(config.authType) }
    var authHeaderKey by remember(config) { mutableStateOf(config.authHeaderKey) }
    var authHeaderValue by remember(config) { mutableStateOf(config.authHeaderValue) }
    var isEncryptionEnabled by remember(config) { mutableStateOf(config.isEncryptionEnabled) }
    var secretKey by remember(config) { mutableStateOf(config.secretEncryptionKey) }
    var deviceId by remember(config) { mutableStateOf(config.deviceIdentifier) }
    var showForegroundNotification by remember(config) { mutableStateOf(config.showForegroundNotification) }
    var enableHealthAlertNotification by remember(config) { mutableStateOf(config.enableHealthAlertNotification) }
    var healthCheckIntervalMinutes by remember(config) { mutableStateOf(config.healthCheckIntervalMinutes) }
    var healthFailureThreshold by remember(config) { mutableStateOf(config.healthFailureThreshold) }
    var driverId by remember(config) { mutableStateOf(config.driverId) }
    var driverFullName by remember(config) { mutableStateOf(config.driverFullName) }
    var filterUtcmsOnly by remember(config) { mutableStateOf(config.filterUtcmsOnly) }
    var enableWorkManagerSync by remember(config) { mutableStateOf(config.enableWorkManagerSync) }

    var keyVisible by remember { mutableStateOf(false) }
    var authDropdownExpanded by remember { mutableStateOf(false) }
    var isAdvancedExpanded by remember { mutableStateOf(false) }
    var isSandboxExpanded by remember { mutableStateOf(false) }
    var sandboxInput by remember { mutableStateOf("تست ارسال پیامک و استخراج OTP") }

    val isHttps = url.trim().startsWith("https://", ignoreCase = true)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(2.dp))
        }

        // ==========================================
        // 1. MAIN SERVER URL CARD (آدرس سرور)
        // ==========================================
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Slate900)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Slate800, RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x2238BDF8)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Https,
                                    contentDescription = null,
                                    tint = Sky400,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "آدرس سرور مقصد",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isHttps) Color(0x2210B981) else Color(0x22F59E0B))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = if (isHttps) "امن (HTTPS)" else "ساده (HTTP)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isHttps) Emerald400 else Color(0xFFF59E0B)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            onSaveConfig(config.copy(endpointUrl = it))
                        },
                        label = { Text("آدرس وب‌هوک سرور") },
                        placeholder = { Text("https://example.com/api/sms") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Sky400,
                            unfocusedBorderColor = Slate800,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Slate950,
                            unfocusedContainerColor = Slate950
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("server_url_input")
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Buttons for Quick Test
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                onRunTest(
                                    url,
                                    authType,
                                    authHeaderKey,
                                    authHeaderValue,
                                    isEncryptionEnabled,
                                    secretKey
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Sky400, contentColor = Slate950),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("test_endpoint_button")
                        ) {
                            if (testState.isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Slate950,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تست اتصال", fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = onOpenServerGuide,
                            colors = ButtonDefaults.buttonColors(containerColor = Slate800, contentColor = Cyan400),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(0.9f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("راهنما", fontWeight = FontWeight.Bold)
                        }
                    }

                    // Test Result Inline Box
                    testState.result?.let { res ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Slate950)
                                .border(
                                    1.dp,
                                    if (res.isSuccess) Color(0x4410B981) else Color(0x44F43F5E),
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (res.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                    contentDescription = null,
                                    tint = if (res.isSuccess) Emerald400 else Rose400,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (res.isSuccess) "پاسخ موفق (${res.durationMs}ms - کد ${res.httpStatusCode ?: 200})" else "خطا: ${res.errorMessage ?: "عدم دسترسی به سرور"}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (res.isSuccess) Emerald400 else Rose400
                                )
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // 1.5 DRIVER & FLEET PROFILE (اطلاعات راننده و ناوگان بارپرو)
        // ==========================================
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Slate900)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Slate800, RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x2210B981)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = Emerald400,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "شناسه راننده و ناوگان بارپرو",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = driverId,
                        onValueChange = {
                            driverId = it
                            onSaveConfig(config.copy(driverId = it))
                        },
                        label = { Text("کد شناسایی / کد ملی راننده") },
                        placeholder = { Text("DRV-908172 یا 0012345678") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Emerald400,
                            unfocusedBorderColor = Slate800,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Slate950,
                            unfocusedContainerColor = Slate950
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = driverFullName,
                        onValueChange = {
                            driverFullName = it
                            onSaveConfig(config.copy(driverFullName = it))
                        },
                        label = { Text("نام و نام خانوادگی راننده") },
                        placeholder = { Text("علی محمدی") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Emerald400,
                            unfocusedBorderColor = Slate800,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Slate950,
                            unfocusedContainerColor = Slate950
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Switch: Filter UTCMS only
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "فیلتر هوشمند اختصاصی UTCMS و بارنامه",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "فقط پیامک‌های حاوی کد رهگیری، کد بارنامه و رمز OTP فوروارد شوند (کاهش مصرف اینترنت و باتری)",
                                fontSize = 11.sp,
                                color = Slate400,
                                lineHeight = 16.sp
                            )
                        }

                        Switch(
                            checked = filterUtcmsOnly,
                            onCheckedChange = {
                                filterUtcmsOnly = it
                                onSaveConfig(config.copy(filterUtcmsOnly = it))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Slate950,
                                checkedTrackColor = Emerald400,
                                uncheckedThumbColor = Slate400,
                                uncheckedTrackColor = Slate800
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Switch: WorkManager offline guaranteed sync
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ارسال تضمینی در پس‌زمینه (WorkManager)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "در صورت قطع موقت اینترنت در جاده، پیامک‌ها در صف ذخیره و بلافاصله پس از اتصال به سرور بارپرو منتقل شوند",
                                fontSize = 11.sp,
                                color = Slate400,
                                lineHeight = 16.sp
                            )
                        }

                        Switch(
                            checked = enableWorkManagerSync,
                            onCheckedChange = {
                                enableWorkManagerSync = it
                                onSaveConfig(config.copy(enableWorkManagerSync = it))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Slate950,
                                checkedTrackColor = Emerald400,
                                uncheckedThumbColor = Slate400,
                                uncheckedTrackColor = Slate800
                            )
                        )
                    }
                }
            }
        }

        // ==========================================
        // 2. ENCRYPTION CARD (رمزنگاری)
        // ==========================================
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Slate900)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (isEncryptionEnabled) Color(0x3306B6D4) else Slate800,
                            RoundedCornerShape(20.dp)
                        )
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isEncryptionEnabled) Color(0x2206B6D4) else Slate800),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = if (isEncryptionEnabled) Cyan400 else Slate400,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "رمزنگاری اطلاعات (AES-256)",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "ارسال امن محتوا با کلید محرمانه",
                                    fontSize = 11.sp,
                                    color = Slate400
                                )
                            }
                        }

                        Switch(
                            checked = isEncryptionEnabled,
                            onCheckedChange = {
                                isEncryptionEnabled = it
                                onSaveConfig(config.copy(isEncryptionEnabled = it))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Slate950,
                                checkedTrackColor = Cyan400,
                                uncheckedThumbColor = Slate400,
                                uncheckedTrackColor = Slate800
                            )
                        )
                    }

                    if (isEncryptionEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = secretKey,
                            onValueChange = {
                                secretKey = it
                                onSaveConfig(config.copy(secretEncryptionKey = it))
                            },
                            label = { Text("کلید اختصاصی رمزنگاری (Secret Key)") },
                            singleLine = true,
                            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                Row {
                                    IconButton(onClick = { keyVisible = !keyVisible }) {
                                        Icon(
                                            imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = null,
                                            tint = Slate400
                                        )
                                    }
                                    IconButton(onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Encryption Key", secretKey)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "کلید کپی شد", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = null,
                                            tint = Slate400
                                        )
                                    }
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Cyan400,
                                unfocusedBorderColor = Slate800,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Slate950,
                                unfocusedContainerColor = Slate950
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "کلید باید با سرور همسان باشد",
                                fontSize = 11.sp,
                                color = Slate400
                            )

                            Button(
                                onClick = {
                                    val newKey = onGenerateKey()
                                    secretKey = newKey
                                    onSaveConfig(config.copy(secretEncryptionKey = newKey))
                                    Toast.makeText(context, "کلید جدید ایجاد شد", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Slate800, contentColor = Cyan400),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("تولید کلید تصادفی", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // 3. AUTHENTICATION CARD (احراز هویت)
        // ==========================================
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Slate900)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Slate800, RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x226366F1)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = Indigo400,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "احراز هویت و توکن",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    ExposedDropdownMenuBox(
                        expanded = authDropdownExpanded,
                        onExpandedChange = { authDropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = when (authType) {
                                AuthType.BEARER_TOKEN -> "توکن Bearer"
                                AuthType.API_KEY_HEADER -> "کلید اختصاصی API (هدر X-API-KEY)"
                                AuthType.CUSTOM_HEADER -> "هدر سفارشی"
                                AuthType.NONE -> "بدون احراز هویت"
                            },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("نوع احراز هویت") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = authDropdownExpanded) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Indigo400,
                                unfocusedBorderColor = Slate800,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Slate950,
                                unfocusedContainerColor = Slate950
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )

                        ExposedDropdownMenu(
                            expanded = authDropdownExpanded,
                            onDismissRequest = { authDropdownExpanded = false },
                            modifier = Modifier.background(Slate900)
                        ) {
                            DropdownMenuItem(
                                text = { Text("توکن Bearer (پیش‌فرض)", color = Color.White) },
                                onClick = {
                                    authType = AuthType.BEARER_TOKEN
                                    authHeaderKey = "Authorization"
                                    onSaveConfig(config.copy(authType = AuthType.BEARER_TOKEN, authHeaderKey = "Authorization"))
                                    authDropdownExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("کلید اختصاصی API (X-API-KEY)", color = Color.White) },
                                onClick = {
                                    authType = AuthType.API_KEY_HEADER
                                    authHeaderKey = "X-API-KEY"
                                    onSaveConfig(config.copy(authType = AuthType.API_KEY_HEADER, authHeaderKey = "X-API-KEY"))
                                    authDropdownExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("بدون احراز هویت", color = Color.White) },
                                onClick = {
                                    authType = AuthType.NONE
                                    onSaveConfig(config.copy(authType = AuthType.NONE))
                                    authDropdownExpanded = false
                                }
                            )
                        }
                    }

                    if (authType != AuthType.NONE) {
                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = authHeaderValue,
                            onValueChange = {
                                authHeaderValue = it
                                onSaveConfig(config.copy(authHeaderValue = it))
                            },
                            label = { Text("مقدار توکن / API Key") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Indigo400,
                                unfocusedBorderColor = Slate800,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Slate950,
                                unfocusedContainerColor = Slate950
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // ==========================================
        // 4. ADVANCED SETTINGS (تنظیمات پیشرفته - تاشو)
        // ==========================================
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Slate900)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Slate800, RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isAdvancedExpanded = !isAdvancedExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x2210B981)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = Emerald400,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "تنظیمات پیشرفته و پایش",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Icon(
                            imageVector = if (isAdvancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = Slate400
                        )
                    }

                    AnimatedVisibility(visible = isAdvancedExpanded) {
                        Column(modifier = Modifier.padding(top = 14.dp)) {
                            // Device ID
                            OutlinedTextField(
                                value = deviceId,
                                onValueChange = {
                                    deviceId = it
                                    onSaveConfig(config.copy(deviceIdentifier = it))
                                },
                                label = { Text("شناسه اختصاصی این دستگاه") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Sky400,
                                    unfocusedBorderColor = Slate800,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedContainerColor = Slate950,
                                    unfocusedContainerColor = Slate950
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Switch: Background Notification
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "اعلان دائمی سرویس پس‌زمینه",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "جلوگیری از بسته‌شدن برنامه توسط اندروید",
                                        fontSize = 11.sp,
                                        color = Slate400
                                    )
                                }

                                Switch(
                                    checked = showForegroundNotification,
                                    onCheckedChange = {
                                        showForegroundNotification = it
                                        onSaveConfig(config.copy(showForegroundNotification = it))
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Slate950,
                                        checkedTrackColor = Sky400,
                                        uncheckedThumbColor = Slate400,
                                        uncheckedTrackColor = Slate800
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Switch: Disconnect alert
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "هشدار قطعی ارتباط با سرور",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "نمایش نوتیفیکیشن در صورت قطعی اتصال",
                                        fontSize = 11.sp,
                                        color = Slate400
                                    )
                                }

                                Switch(
                                    checked = enableHealthAlertNotification,
                                    onCheckedChange = {
                                        enableHealthAlertNotification = it
                                        onSaveConfig(config.copy(enableHealthAlertNotification = it))
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Slate950,
                                        checkedTrackColor = Emerald400,
                                        uncheckedThumbColor = Slate400,
                                        uncheckedTrackColor = Slate800
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Action buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onCheckServerHealth,
                                    colors = ButtonDefaults.buttonColors(containerColor = Slate800, contentColor = Emerald400),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("بررسی سلامت", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = onTestDisconnectNotification,
                                    colors = ButtonDefaults.buttonColors(containerColor = Slate800, contentColor = Rose400),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(imageVector = Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("تست هشدار", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}
