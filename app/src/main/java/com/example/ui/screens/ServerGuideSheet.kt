package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
fun ServerGuideSheet(
    secretKey: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    val pythonCode = """
# Python 3 + Flask + PyCryptodome
# Install: pip install flask pycryptodome

from flask import Flask, request, jsonify
from Crypto.Cipher import AES
import base64
import json
import hashlib

app = Flask(__name__)
SECRET_KEY = "$secretKey"
AES_KEY = hashlib.sha256(SECRET_KEY.encode('utf-8')).digest()

@app.route('/api/sms/forward', methods=['POST'])
def handle_sms():
    # 1. Read quick headers
    sender = request.headers.get('X-Forwarder-Sender')
    otp_code = request.headers.get('X-Forwarder-OTP')
    device = request.headers.get('X-Forwarder-Device')
    req_type = request.headers.get('X-Forwarder-Type', 'SMS_FORWARD')
    
    payload = request.json or {}
    
    # 2. Check if payload is encrypted
    if payload.get('encrypted'):
        try:
            iv = base64.b64decode(payload['iv'])
            ciphertext_with_tag = base64.b64decode(payload['ciphertext'])
            tag = ciphertext_with_tag[-16:]
            actual_ciphertext = ciphertext_with_tag[:-16]
            
            cipher = AES.new(AES_KEY, AES.MODE_GCM, nonce=iv)
            decrypted_bytes = cipher.decrypt_and_verify(actual_ciphertext, tag)
            sms_data = json.loads(decrypted_bytes.decode('utf-8'))
            
            print(f"[+] Decrypted SMS from: {sms_data.get('sender')}")
            print(f"    OTP Code: {sms_data.get('otp_code')}")
            print(f"    Text: {sms_data.get('message')}")
        except Exception as e:
            return jsonify({"status": "error", "message": f"Decryption failed: {e}"}), 400
    else:
        sms_data = payload.get('data', payload)
        print(f"[+] Received Plain SMS from {sender}: OTP={otp_code}")

    # You can return optional commands for the phone in response
    return jsonify({
        "status": "success",
        "has_command": False
    }), 200

# Heartbeat & Command Polling endpoint
@app.route('/api/sms/heartbeat', methods=['POST'])
def handle_heartbeat():
    data = request.json or {}
    print(f"[*] Heartbeat from {data.get('device_id')} - Battery: {data.get('battery_level')}%")
    
    # If your backend needs an OTP on-demand, you can return a command:
    # return jsonify({
    #     "status": "ok",
    #     "has_command": True,
    #     "command": {
    #         "id": "cmd_991",
    #         "type": "GET_LATEST_OTP",
    #         "sender": "2000..."
    #     }
    # }), 200
    
    return jsonify({"status": "ok", "has_command": False}), 200

# Command Reply endpoint
@app.route('/api/sms/commands/reply', methods=['POST'])
def handle_command_reply():
    data = request.json or {}
    print(f"[+] Command reply for {data.get('command_id')}: {data.get('result')}")
    return jsonify({"status": "acknowledged"}), 200

if __name__ == '__main__':
    print("[*] SMS Forwarder API listening on port 5000...")
    app.run(host='0.0.0.0', port=5000)
    """.trimIndent()

    val nodeJsCode = """
// Node.js Express Server with AES-256-GCM Decryption & Command Polling
// Install: npm install express

const express = require('express');
const crypto = require('crypto');

const app = express();
app.use(express.json({ limit: '10mb' }));

const SECRET_KEY_STRING = '$secretKey';
const AES_KEY = crypto.createHash('sha256').update(SECRET_KEY_STRING).digest();

// 1. Realtime SMS Webhook
app.post('/api/sms/forward', (req, res) => {
    const sender = req.headers['x-forwarder-sender'];
    const otp = req.headers['x-forwarder-otp'];
    const device = req.headers['x-forwarder-device'];
    const payload = req.body;

    if (payload.encrypted) {
        try {
            const iv = Buffer.from(payload.iv, 'base64');
            const cipherBuffer = Buffer.from(payload.ciphertext, 'base64');
            const authTag = cipherBuffer.subarray(cipherBuffer.length - 16);
            const actualCiphertext = cipherBuffer.subarray(0, cipherBuffer.length - 16);

            const decipher = crypto.createDecipheriv('aes-256-gcm', AES_KEY, iv);
            decipher.setAuthTag(authTag);
            let decrypted = decipher.update(actualCiphertext, null, 'utf8');
            decrypted += decipher.final('utf8');

            const sms = JSON.parse(decrypted);
            console.log('=== SECURE SMS RECEIVED ===');
            console.log('📱 Sender:', sms.sender || sender);
            console.log('🔑 OTP   :', sms.otp_code || otp);
            console.log('💬 Body  :', sms.message);

            return res.json({ status: 'success', has_command: false });
        } catch (err) {
            console.error('Decryption error:', err.message);
            return res.status(400).json({ status: 'error', error: 'Decryption failed' });
        }
    } else {
        console.log('Plain SMS from:', sender, 'OTP:', otp);
        return res.json({ status: 'success' });
    }
});

// 2. Heartbeat & Command Polling
app.post('/api/sms/heartbeat', (req, res) => {
    const status = req.body;
    console.log('💓 Device Heartbeat [' + (status.device_id || 'dev') + '] - Battery: ' + (status.battery_level || 0) + '%');
    return res.json({ status: 'ok', has_command: false });
});

// 3. Batch Offline Sync
app.post('/api/sms/batch-sync', (req, res) => {
    const { messages, count } = req.body;
    console.log('📦 Batch sync received: ' + (count || 0) + ' offline messages');
    return res.json({ status: 'synced', received_count: count });
});

app.listen(5000, () => console.log('Secure SMS server listening on port 5000'));
    """.trimIndent()

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
                            .background(Color(0x2206B6D4)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Hub,
                            contentDescription = null,
                            tint = Cyan400,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "بررسی و راهکار جامع ارتباط API",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "معماری کامل اتصال پایدار اپ به سرور و بالعکس",
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

            // Tab bar with 5 comprehensive options
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Slate950,
                contentColor = Cyan400,
                edgePadding = 8.dp
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("معماری و راهکار API", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("پایتون (Python)", fontSize = 11.sp) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("نود جی‌اس (Node.js)", fontSize = 11.sp) }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("هدرها و شماره‌ها", fontSize = 11.sp) }
                )
                Tab(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    text = { Text("استعلام OTP و فرامین", fontSize = 11.sp) }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            when (selectedTab) {
                0 -> {
                    // TAB 0: Detailed Architecture Explanation & Solution
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Slate950)
                            .border(1.dp, Slate800, RoundedCornerShape(14.dp))
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "۱. بررسی چالش فنی ارتباط بین گوشی و سرور (Network Analysis):",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Sky400
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "• گوشی‌های موبایل در شبکه اپراتورها (ایرانسل، همراه اول و...) پشت لایه‌های NAT و فایروال قرار دارند و IP استاتیک یا عمومی ندارند.\n" +
                                    "• بنابراین سرور نمی‌تواند به صورت سنتی به عنوان Client مستقیماً به IP گوشی ریکوئست ارسال کند.\n" +
                                    "• استفاده دائمی از سوکت باز (WebSocket) نیز به دلیل سیستم بهینه‌سازی باتری اندروید (Doze Mode) قطع می‌شود.",
                            fontSize = 12.sp,
                            color = Slate300,
                            lineHeight = 19.sp
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "۲. راهکار ۴ گانه پیاده‌سازی شده در این نرم‌افزار:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Emerald400
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // Box 1
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x1806B6D4))
                                .border(1.dp, Color(0x3306B6D4), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "الف) وب‌هوک بلادرنگ (App -> Server Realtime Webhook):",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Cyan400
                            )
                            Text(
                                text = "به محض دریافت پیامک، کدهای OTP به صورت خودکار با رگکس هوشمند استخراج شده و به همراه شماره فرستنده در هدر X-Forwarder-OTP و بدنه رمزگذاری شده به سرور POST می‌شود. تاخیر زیر ۲۰۰ میلی‌ثانیه.",
                                fontSize = 11.sp,
                                color = Slate300,
                                lineHeight = 17.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Box 2
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x1810B981))
                                .border(1.dp, Color(0x3310B981), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "ب) سیستم فرامین معکوس سرور به اپ (Server Commands via Heartbeat):",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Emerald400
                            )
                            Text(
                                text = "اپلیکیشن در پینگ‌های دوره‌ای (Heartbeat)، به سرور متصل می‌شود. سرور می‌تواند در پاسخ، فرمانی مانند GET_LATEST_OTP صادر کند. اپ فوراً پاسخ را پردازش کرده و نتیجه را به سرور تحویل می‌دهد.",
                                fontSize = 11.sp,
                                color = Slate300,
                                lineHeight = 17.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Box 3
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x18F59E0B))
                                .border(1.dp, Color(0x33F59E0B), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "ج) صف آفلاین و تلاش مجدد هوشمند (Offline Resync Queue):",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFBBF24)
                            )
                            Text(
                                text = "در صورت قطعی اینترنت گوشی، پیامک‌ها هرگز گم نمی‌شوند؛ آنها در دیتابیس امن محلی ذخیره شده و به محض وصل شدن نت، یا به صورت تکی یا به صورت پکیج دسته‌ای (Batch Sync) خودکار به سرور منتقل می‌گردند.",
                                fontSize = 11.sp,
                                color = Slate300,
                                lineHeight = 17.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Box 4
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x186366F1))
                                .border(1.dp, Color(0x336366F1), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "د) گزارش سلامت و تلِمتری دستگاه (Device Telemetry):",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFA5B4FC)
                            )
                            Text(
                                text = "ارسال منظم درصد باتری، در حال شارژ بودن و نوع اتصال (Wi-Fi یا دیتای سیم‌کارت) به سرور تا تیم مانیتورینگ متوجه خاموشی یا کاهش شارژ گوشی شود.",
                                fontSize = 11.sp,
                                color = Slate300,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }

                1 -> {
                    // TAB 1: Python Code
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Slate950)
                            .border(1.dp, Slate800, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = pythonCode,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Emerald400,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        )
                    }
                }

                2 -> {
                    // TAB 2: Node.js Code
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Slate950)
                            .border(1.dp, Slate800, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = nodeJsCode,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Emerald400,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        )
                    }
                }

                3 -> {
                    // TAB 3: Headers & Numbers
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Slate950)
                            .border(1.dp, Slate800, RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            text = "هدرهای ارسالی HTTP توسط اپلیکیشن:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Sky400
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "• X-Forwarder-Sender: شماره یا سرشماره فرستنده پیامک\n" +
                                    "• X-Forwarder-OTP: کد عددی اعتبارسنجی استخراج‌شده (بدون نیاز به Regex در سرور)\n" +
                                    "• X-Forwarder-Rule: نام قانون تطبیق داده شده\n" +
                                    "• X-Forwarder-Sim: شماره اسلات سیم‌کارت (SIM 1 یا SIM 2)\n" +
                                    "• X-Forwarder-Device: نام اختصاصی دستگاه\n" +
                                    "• X-Forwarder-Type: نوع درخواست (SMS_FORWARD یا HEARTBEAT یا BATCH_SYNC)",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Slate300,
                            lineHeight = 18.sp
                        )
                    }
                }

                4 -> {
                    // TAB 4: OTP & Commands
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Slate950)
                            .border(1.dp, Slate800, RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            text = "نحوه دریافت کد OTP بر اساس زمان توسط سرور:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Cyan400
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "سرور می‌تواند برای وب‌سایت‌ها یا ربات‌های خودکار، فرمانی مانند زیر در پاسخ پینگ صادر کند تا گوشی نزدیک‌ترین کد دریافتی را برگرداند:\n\n" +
                                    "{\n" +
                                    "  \"status\": \"ok\",\n" +
                                    "  \"has_command\": true,\n" +
                                    "  \"command\": {\n" +
                                    "    \"id\": \"cmd_req_492\",\n" +
                                    "    \"type\": \"GET_LATEST_OTP\",\n" +
                                    "    \"sender\": \"2000...\",\n" +
                                    "    \"target_timestamp\": 1718000125000\n" +
                                    "  }\n" +
                                    "}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Emerald400,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val textToCopy = when (selectedTab) {
                        1 -> pythonCode
                        2 -> nodeJsCode
                        else -> "X-Forwarder-Sender, X-Forwarder-OTP, X-Forwarder-Rule, X-Forwarder-Sim, X-Forwarder-Device"
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Server Details", textToCopy)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "اطلاعات در حافظه کپی شد!", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan400, contentColor = Slate950)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (selectedTab == 1 || selectedTab == 2) "کپی کد سرور" else "کپی مشخصات فنی",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
