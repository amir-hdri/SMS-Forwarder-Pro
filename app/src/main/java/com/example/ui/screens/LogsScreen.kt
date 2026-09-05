package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.ForwardLog
import com.example.data.model.ForwardStatus
import com.example.ui.components.StatusBadge
import com.example.ui.theme.Cyan400
import com.example.ui.theme.Emerald400
import com.example.ui.theme.Rose400
import com.example.ui.theme.Sky400
import com.example.ui.theme.Slate300
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate600
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(
    logs: List<ForwardLog>,
    selectedFilter: ForwardStatus?,
    searchQuery: String,
    onFilterChanged: (ForwardStatus?) -> Unit,
    onSearchChanged: (String) -> Unit,
    onClearLogs: () -> Unit,
    onDeleteLog: (ForwardLog) -> Unit,
    onRetryLog: (ForwardLog) -> Unit
) {
    var selectedLogForDetail by remember { mutableStateOf<ForwardLog?>(null) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    val tabs = listOf("همه", "موفق", "ناموفق", "رد شده")
    val currentTabIndex = when (selectedFilter) {
        null -> 0
        ForwardStatus.SUCCESS -> 1
        ForwardStatus.FAILED -> 2
        ForwardStatus.SKIPPED -> 3
        ForwardStatus.PENDING -> 0
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        // Search and Actions Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "گزارش‌های انتقال پیامک (${logs.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            if (logs.isNotEmpty()) {
                IconButton(
                    onClick = { showClearConfirmDialog = true },
                    modifier = Modifier.testTag("clear_logs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "پاک‌سازی تمام گزارش‌ها",
                        tint = Slate400
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Status Tabs
        TabRow(
            selectedTabIndex = currentTabIndex,
            containerColor = Slate900,
            contentColor = Sky400,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Slate800, RoundedCornerShape(12.dp))
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = currentTabIndex == index,
                    onClick = {
                        val filter = when (index) {
                            0 -> null
                            1 -> ForwardStatus.SUCCESS
                            2 -> ForwardStatus.FAILED
                            3 -> ForwardStatus.SKIPPED
                            else -> null
                        }
                        onFilterChanged(filter)
                    },
                    text = {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = if (currentTabIndex == index) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Search Input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChanged,
            placeholder = { Text("جستجو در فرستنده یا متن پیامک...", color = Slate400) },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = Slate400)
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Sky400,
                unfocusedBorderColor = Slate800,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = Slate900,
                unfocusedContainerColor = Slate900
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Sms,
                        contentDescription = null,
                        tint = Slate600,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "هیچ گزارشی با فیلتر انتخابی یافت نشد",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Slate400
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "رویدادهای فوروارد و بررسی پیامک‌ها اینجا ثبت می‌شوند.",
                        fontSize = 12.sp,
                        color = Slate600
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(logs, key = { it.id }) { log ->
                    LogCardItem(
                        log = log,
                        onClick = { selectedLogForDetail = log },
                        onRetry = { onRetryLog(log) }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }

    // Detail Dialog
    selectedLogForDetail?.let { log ->
        LogDetailDialog(
            log = log,
            onRetry = {
                onRetryLog(log)
                selectedLogForDetail = null
            },
            onDelete = {
                onDeleteLog(log)
                selectedLogForDetail = null
            },
            onDismiss = { selectedLogForDetail = null }
        )
    }

    // Clear Confirmation Dialog
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("پاک‌سازی تمام گزارش‌ها؟", color = Color.White) },
            text = { Text("تمام سوابق ارسال پیامک‌ها از حافظه محلی دستگاه حذف خواهند شد.", color = Slate300) },
            confirmButton = {
                Button(
                    onClick = {
                        onClearLogs()
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Rose400, contentColor = Slate950)
                ) {
                    Text("حذف همه", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearConfirmDialog = false }) {
                    Text("انصراف", color = Slate300)
                }
            },
            containerColor = Slate900
        )
    }
}

@Composable
private fun LogCardItem(
    log: ForwardLog,
    onClick: () -> Unit,
    onRetry: () -> Unit
) {
    val formatter = SimpleDateFormat("HH:mm:ss • MMM dd, yyyy", Locale.getDefault())
    val formattedTime = formatter.format(Date(log.receivedTimestamp))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Slate800, RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when (log.status) {
                                    ForwardStatus.SUCCESS -> Emerald400
                                    ForwardStatus.FAILED -> Rose400
                                    ForwardStatus.SKIPPED -> Slate400
                                    ForwardStatus.PENDING -> Sky400
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = log.sender,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val typeLabel = when (log.smsType) {
                        com.example.data.model.SmsType.UTCMS_CONFIRMATION -> "تایید بارنامه"
                        com.example.data.model.SmsType.UTCMS_OTP -> "کد OTP"
                        com.example.data.model.SmsType.UTCMS_WARNING -> "هشدار"
                        com.example.data.model.SmsType.OTHER -> "عمومی"
                    }
                    val typeColor = when (log.smsType) {
                        com.example.data.model.SmsType.UTCMS_CONFIRMATION -> Emerald400
                        com.example.data.model.SmsType.UTCMS_OTP -> Color(0xFFDAB785)
                        com.example.data.model.SmsType.UTCMS_WARNING -> Rose400
                        com.example.data.model.SmsType.OTHER -> Slate400
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Slate950)
                            .border(1.dp, typeColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(typeLabel, fontSize = 10.sp, color = typeColor, fontWeight = FontWeight.Bold)
                    }
                    StatusBadge(status = log.status)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = log.messageBody,
                fontSize = 12.sp,
                color = Slate300,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (log.trackingCode != null || log.otpCode != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    log.trackingCode?.let { code ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0x2210B981))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("بارنامه: $code", fontSize = 10.sp, color = Emerald400, fontWeight = FontWeight.Bold)
                        }
                    }
                    log.otpCode?.let { otp ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0x22DAB785))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("رمز: $otp", fontSize = 10.sp, color = Color(0xFFDAB785), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formattedTime,
                    fontSize = 10.sp,
                    color = Slate400
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (log.retryCount > 0) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0x22F59E0B))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text("تلاش: ${log.retryCount}", fontSize = 9.sp, color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold)
                        }
                    }

                    if (log.isEncrypted) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0x2206B6D4))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text("AES-256", fontSize = 9.sp, color = Cyan400, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (log.httpStatusCode != null) {
                        Text(
                            text = "HTTP ${log.httpStatusCode}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (log.status == ForwardStatus.SUCCESS) Emerald400 else Rose400
                        )
                    }

                    if (log.status == ForwardStatus.FAILED) {
                        IconButton(
                            onClick = onRetry,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "تلاش مجدد برای ارسال",
                                tint = Sky400,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogDetailDialog(
    log: ForwardLog,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "جزئیات انتقال پیامک",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "بستن", tint = Slate400)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Sender & Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("شماره فرستنده", fontSize = 11.sp, color = Slate400)
                        Text(log.sender, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    StatusBadge(status = log.status)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Raw SMS message
                Text("متن پیامک دریافتی", fontSize = 11.sp, color = Slate400)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Slate950)
                        .border(1.dp, Slate800, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(log.messageBody, fontSize = 12.sp, color = Slate300)
                }

                // Extracted BarPro Codes
                if (log.trackingCode != null || log.otpCode != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        log.trackingCode?.let { code ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x2210B981))
                                    .border(1.dp, Emerald400.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("کد رهگیری: $code", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Emerald400)
                            }
                        }
                        log.otpCode?.let { otp ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x22DAB785))
                                    .border(1.dp, Color(0xFFDAB785).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("رمز یکبار مصرف: $otp", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDAB785))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Metadata row
                Text("نوع پیام: ${when (log.smsType) {
                    com.example.data.model.SmsType.UTCMS_CONFIRMATION -> "تاییدیه بارنامه UTCMS"
                    com.example.data.model.SmsType.UTCMS_OTP -> "رمز یکبار مصرف (OTP)"
                    com.example.data.model.SmsType.UTCMS_WARNING -> "هشدار سیستمی"
                    com.example.data.model.SmsType.OTHER -> "پیامک عمومی"
                }}", fontSize = 11.sp, color = Color(0xFFDAB785), fontWeight = FontWeight.Bold)
                if (log.driverId.isNotBlank()) {
                    Text("شناسه راننده ناوگان: ${log.driverId}", fontSize = 11.sp, color = Emerald400)
                }
                if (log.retryCount > 0) {
                    Text("تعداد تلاش‌های مجدد: ${log.retryCount}", fontSize = 11.sp, color = Color(0xFFF59E0B))
                }
                Text("زمان ثبت: ${formatter.format(Date(log.receivedTimestamp))}", fontSize = 11.sp, color = Slate400)
                if (log.matchedRuleLabel != null) {
                    Text("قانون منطبق‌شده: ${log.matchedRuleLabel}", fontSize = 11.sp, color = Cyan400)
                }
                if (log.endpointUrl.isNotBlank()) {
                    Text("آدرس سرور: ${log.endpointUrl}", fontSize = 11.sp, color = Sky400)
                }
                if (log.durationMs > 0) {
                    Text("زمان پاسخ‌دهی (Latency): ${log.durationMs} میلی‌ثانیه", fontSize = 11.sp, color = Slate400)
                }

                if (log.errorMessage != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("خطا / اطلاعیه", fontSize = 11.sp, color = Rose400, fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x22F43F5E))
                            .padding(10.dp)
                    ) {
                        Text(log.errorMessage, fontSize = 11.sp, color = Rose400)
                    }
                }

                if (!log.payloadPreview.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("بسته ارسالی به سرور (JSON / رمزنگاری‌شده)", fontSize = 11.sp, color = Slate400)
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Payload", log.payloadPreview)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "بسته ارسالی کپی شد", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = Slate400,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Slate950)
                            .border(1.dp, Slate800, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = log.payloadPreview,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = if (log.isEncrypted) Cyan400 else Emerald400
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (log.status == ForwardStatus.FAILED) {
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = Sky400, contentColor = Slate950),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ارسال مجدد", fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Rose400),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("حذف")
                    }
                }
            }
        }
    }
}
