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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.FilterRule
import com.example.data.repository.OtpInquiryExecutionResult
import com.example.ui.theme.Cyan400
import com.example.ui.theme.Emerald400
import com.example.ui.theme.Rose400
import com.example.ui.theme.Sky400
import com.example.ui.theme.Slate300
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate850
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun OtpInquiryDialog(
    activeRules: List<FilterRule>,
    isLoading: Boolean,
    lastResult: OtpInquiryExecutionResult?,
    onExecuteInquiry: (sender: String, timestamp: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var senderInput by remember { mutableStateOf(activeRules.firstOrNull()?.senderPattern ?: "2000") }
    var selectedTimeOffsetMinutes by remember { mutableStateOf(0) }

    val timeOptions = listOf(
        0 to "هم‌اکنون",
        2 to "۲ دقیقه پیش",
        5 to "۵ دقیقه پیش",
        10 to "۱۰ دقیقه پیش",
        30 to "۳۰ دقیقه پیش"
    )

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .testTag("otp_inquiry_dialog"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Slate800, RoundedCornerShape(24.dp))
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
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
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Brush.linearGradient(listOf(Sky400, Cyan400))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = Slate950,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "دریافت هوشمند کد بارنامه توسط سرور",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "انتقال خودکار کد بارنامه پیامک بر اساس درخواست سرور",
                                fontSize = 11.sp,
                                color = Slate400
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "بستن", tint = Slate400)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Explanatory Banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x1538BDF8))
                        .border(1.dp, Color(0x3338BDF8), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "هر زمان سرور درخواست دریافت کد بارنامه برای یک سامانه/شماره و زمان مشخص را ارسال کند، برنامه نزدیک‌ترین پیامک دریافتی را بررسی کرده، کد بارنامه را خودکار استخراج نموده و به سرور بازمی‌گرداند.",
                        fontSize = 11.sp,
                        color = Slate300,
                        lineHeight = 16.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Sender selection
                Text(
                    text = "شماره یا نام سامانه فرستنده:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))

                if (activeRules.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(activeRules) { rule ->
                            val isSelected = senderInput == rule.senderPattern
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Sky400.copy(alpha = 0.2f) else Slate800)
                                    .border(
                                        1.dp,
                                        if (isSelected) Sky400 else Slate700,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { senderInput = rule.senderPattern }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "${rule.label} (${rule.senderPattern})",
                                    fontSize = 11.sp,
                                    color = if (isSelected) Sky400 else Slate300
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = senderInput,
                    onValueChange = { senderInput = it },
                    label = { Text("شماره یا سرشماره پیامک") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Sky400,
                        unfocusedBorderColor = Slate700,
                        focusedLabelColor = Sky400,
                        unfocusedLabelColor = Slate400,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("otp_inquiry_sender_input")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Time specification
                Text(
                    text = "بازه زمانی درخواست سرور:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    timeOptions.forEach { (minutes, label) ->
                        val isSelected = selectedTimeOffsetMinutes == minutes
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Cyan400.copy(alpha = 0.2f) else Slate800)
                                .border(
                                    1.dp,
                                    if (isSelected) Cyan400 else Slate700,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedTimeOffsetMinutes = minutes }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Cyan400 else Slate300
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Submit button
                Button(
                    onClick = {
                        val requestedTime = System.currentTimeMillis() - (selectedTimeOffsetMinutes * 60 * 1000L)
                        onExecuteInquiry(senderInput, requestedTime)
                    },
                    enabled = senderInput.isNotBlank() && !isLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Sky400,
                        contentColor = Slate950
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("execute_otp_inquiry_button")
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = Slate950,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "اجرای استعلام و ارسال OTP به سرور",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                // Result Box
                if (lastResult != null) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (lastResult.isSuccess) Color(0xFF0F2620) else Color(0xFF261215))
                            .border(
                                1.dp,
                                if (lastResult.isSuccess) Emerald400.copy(alpha = 0.5f) else Rose400.copy(alpha = 0.5f),
                                RoundedCornerShape(16.dp)
                            )
                            .padding(14.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (lastResult.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                        contentDescription = null,
                                        tint = if (lastResult.isSuccess) Emerald400 else Rose400,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (lastResult.isSuccess) "استعلام موفق و ارسال شد" else "خطا در استعلام یا ارسال",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (lastResult.isSuccess) Emerald400 else Rose400
                                    )
                                }

                                if (lastResult.otpCode != null) {
                                    IconButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("OTP Code", lastResult.otpCode)
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(context, "کد OTP کپی شد: ${lastResult.otpCode}", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "کپی کد",
                                            tint = Slate300,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            if (lastResult.otpCode != null) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Slate950)
                                        .border(1.dp, Emerald400.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "کد تایید (OTP) استخراج شده:",
                                            fontSize = 11.sp,
                                            color = Slate400
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = lastResult.otpCode,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = 4.sp,
                                            color = Emerald400
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = lastResult.message,
                                fontSize = 11.sp,
                                color = Slate300,
                                lineHeight = 16.sp
                            )

                            if (lastResult.matchedLog != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                Text(
                                    text = "پیامک منطبق: «${lastResult.matchedLog.messageBody}» (ساعت: ${timeFormat.format(Date(lastResult.matchedLog.receivedTimestamp))})",
                                    fontSize = 11.sp,
                                    color = Slate400,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
