package com.example.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sms
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.ForwardLog
import com.example.ui.components.StatusBadge
import com.example.ui.theme.Cyan400
import com.example.ui.theme.Emerald400
import com.example.ui.theme.Rose400
import com.example.ui.theme.Sky400
import com.example.ui.theme.Slate300
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

@Composable
fun TestSmsDialog(
    lastSimulatedLog: ForwardLog?,
    onSimulate: (sender: String, message: String) -> Unit,
    onDismiss: () -> Unit
) {
    var senderInput by remember { mutableStateOf("+18005550199") }
    var messageInput by remember { mutableStateOf("Alert: Verification OTP code is 938210. Expires in 5 mins.") }
    var isSimulating by remember { mutableStateOf(false) }

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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x2238BDF8)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sms,
                                contentDescription = null,
                                tint = Sky400,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "شبیه‌سازی و تست پیامک",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "بستن", tint = Slate400)
                    }
                }

                Text(
                    text = "نحوه بررسی قوانین فیلتر، رمزنگاری اطلاعات و ارسال به سرور API را به صورت زنده آزمایش کنید.",
                    fontSize = 12.sp,
                    color = Slate400,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                // Quick Preset Chips
                Text(
                    text = "نمونه‌های آماده تست:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Slate400
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x22F59E0B))
                            .border(1.dp, Color(0x66F59E0B), RoundedCornerShape(8.dp))
                            .clickable {
                                senderInput = "10008545"
                                messageInput = "سامانه بارنامه شهرداری: کد ورود شما ۳۹۱۸۲ می باشد."
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Text("رمز ۵ رقمی UTCMS (۳۹۱۸۲)", fontSize = 10.sp, color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold)
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x2238BDF8))
                            .border(1.dp, Sky400.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .clickable {
                                senderInput = "BAR PRO"
                                messageInput = "کد تایید شما برای بارنامه: 48291"
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Text("بارنامه بارپرو (۴۸۲۹۱)", fontSize = 10.sp, color = Sky400, fontWeight = FontWeight.Bold)
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x2234D399))
                            .border(1.dp, Emerald400.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .clickable {
                                senderInput = "982000100"
                                messageInput = "بارنامه شماره ۷۸۴۹۲ تایید شد. راننده محترم جهت بارگیری به پایانه مراجعه نمایید."
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Text("اعلام بارنامه", fontSize = 10.sp, color = Emerald400, fontWeight = FontWeight.Bold)
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x2222D3EE))
                            .border(1.dp, Cyan400.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .clickable {
                                senderInput = "+989120000000"
                                messageInput = "رمز یکبار مصرف شما: 638102"
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Text("کد تایید OTP", fontSize = 10.sp, color = Cyan400, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = senderInput,
                    onValueChange = { senderInput = it },
                    label = { Text("شماره یا نام فرستنده پیامک") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Sky400,
                        unfocusedBorderColor = Slate800,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = Sky400,
                        unfocusedLabelColor = Slate400,
                        focusedContainerColor = Slate950,
                        unfocusedContainerColor = Slate950
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = messageInput,
                    onValueChange = { messageInput = it },
                    label = { Text("متن پیامک") },
                    minLines = 3,
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Sky400,
                        unfocusedBorderColor = Slate800,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = Sky400,
                        unfocusedLabelColor = Slate400,
                        focusedContainerColor = Slate950,
                        unfocusedContainerColor = Slate950
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (senderInput.isNotBlank() && messageInput.isNotBlank()) {
                            onSimulate(senderInput, messageInput)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Sky400, contentColor = Slate950),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("بررسی قوانین و ارسال تست", fontWeight = FontWeight.Bold)
                }

                if (lastSimulatedLog != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Slate950)
                            .border(1.dp, Slate800, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "نتیجه آزمایش شبیه‌سازی",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Sky400
                                )
                                StatusBadge(status = lastSimulatedLog.status)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "قانون منطبق‌شده: ${lastSimulatedLog.matchedRuleLabel ?: "هیچ‌کدام"}",
                                fontSize = 12.sp,
                                color = Slate300
                            )
                            if (lastSimulatedLog.httpStatusCode != null) {
                                Text(
                                    text = "وضعیت وب‌سرویس: HTTP ${lastSimulatedLog.httpStatusCode} (${lastSimulatedLog.durationMs} میلی‌ثانیه)",
                                    fontSize = 12.sp,
                                    color = Emerald400
                                )
                            }
                            if (lastSimulatedLog.errorMessage != null) {
                                Text(
                                    text = "پیام/وضعیت: ${lastSimulatedLog.errorMessage}",
                                    fontSize = 11.sp,
                                    color = Rose400
                                )
                            }
                            if (!lastSimulatedLog.payloadPreview.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "بسته ارسالی به سرور:",
                                    fontSize = 11.sp,
                                    color = Slate400
                                )
                                Text(
                                    text = lastSimulatedLog.payloadPreview,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = Slate300,
                                    maxLines = 6
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
