package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.Cyan400
import com.example.ui.theme.Emerald400
import com.example.ui.theme.Sky400
import com.example.ui.theme.Slate300
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

@Composable
fun BackgroundExecutionGuideSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val isIgnoringBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    } else {
        true
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        shape = RoundedCornerShape(24.dp)
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
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x2210B981)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = Emerald400,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "وضعیت اجرای دائمی در پس‌زمینه",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "توضیح فنی و تنظیمات تضمین عدم توقف برنامه",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate400
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "بستن",
                        tint = Slate400
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isIgnoringBatteryOptimizations) Color(0x1810B981) else Color(0x25F59E0B))
                    .border(
                        1.dp,
                        if (isIgnoringBatteryOptimizations) Emerald400.copy(alpha = 0.4f) else Color(0xFFF59E0B).copy(alpha = 0.4f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isIgnoringBatteryOptimizations) Icons.Default.CheckCircle else Icons.Default.BatteryAlert,
                    contentDescription = null,
                    tint = if (isIgnoringBatteryOptimizations) Emerald400 else Color(0xFFF59E0B),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (isIgnoringBatteryOptimizations) "بهینه‌سازی باتری غیرفعال است (حالت ایده‌آل)" else "بهینه‌سازی باتری فعال است (پیشنهاد غیرفعال‌سازی)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = if (isIgnoringBatteryOptimizations) "اندروید مجاز به بستن یا توقف این اپلیکیشن در پس‌زمینه نیست." else "سیستم‌عامل ممکن است در صورت بسته ماندن طولانی، برنامه را موقتاً به خواب ببرد.",
                        fontSize = 11.sp,
                        color = Slate300
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Deep technical explanation
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Slate950)
                    .border(1.dp, Slate800, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = "آیا با روشن کردن «ذخیره انرژی»، برنامه استثنا می‌شود؟",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Sky400
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "بله! در سیستم‌عامل اندروید، اگر برنامه در لیست سفید (Whitelist) باتری قرار گیرد، حتی در صورت فعال شدن حالت ذخیره انرژی (Power Saving Mode یا Doze Mode)، سیستم‌عامل دسترسی شبکه و پردازش پس‌زمینه را برای این برنامه قطع نمی‌کند.\n\n" +
                            "برای این منظور ۳ لایه محافظتی در برنامه پیاده شده است:\n" +
                            "۱. مجوز رسمی استثنای باتری (Request Ignore Battery Optimizations) با دیالوگ سیستمی تک‌کلیک.\n" +
                            "۲. قفل موقت پردازنده (WakeLock ۱۵ ثانیه‌ای) هنگام رسیدن پیامک تا ارسال موفق به سرور.\n" +
                            "۳. صف آفلاین با ارسال خودکار به محض بازگشت اتصال اینترنت.",
                    fontSize = 11.sp,
                    color = Slate300,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "نکات تکمیلی برای برندهای خاص:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "• سامسونگ: تنظیمات > برنامه‌ها > Sms Forwarder > باتری > روی «نامحدود (Unrestricted)» قرار دهید.\n" +
                            "• شیائومی / پوکو: نگه داشتن روی آیکون برنامه > اطلاعات برنامه (App Info) > فعال کردن Autostart و تنظیم Battery Saver روی No Restrictions.\n" +
                            "• هواوی: تنظیمات > باتری > راه‌اندازی برنامه (App Launch) > تنظیم روی مدیریت دستی (Manual).",
                    fontSize = 11.sp,
                    color = Slate400,
                    lineHeight = 17.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action buttons
            if (!isIgnoringBatteryOptimizations) {
                Button(
                    onClick = {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            }
                        } catch (e: Exception) {
                            try {
                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                context.startActivity(intent)
                            } catch (e2: Exception) {
                                e2.printStackTrace()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald400, contentColor = Slate950),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Power, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("غیرفعال کردن بهینه‌سازی باتری (توصیه می‌شود)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Slate800, contentColor = Color.White),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("تنظیمات دسترسی‌ها و مدیریت باتری دستگاه", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
    }
}
