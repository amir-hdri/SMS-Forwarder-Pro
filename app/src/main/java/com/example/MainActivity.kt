package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.ForwardLog
import com.example.data.model.ForwardStatus
import com.example.ui.components.StatusBadge
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.OtpInquiryDialog
import com.example.ui.screens.PermissionDialog
import com.example.ui.screens.PermissionManagerScreen
import com.example.ui.screens.ServerConfigScreen
import com.example.ui.screens.ServerGuideSheet
import com.example.ui.screens.TestSmsDialog
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.PaletteDarkest
import com.example.ui.theme.PaletteDeep
import com.example.ui.theme.PaletteLight
import com.example.ui.theme.PaletteMedium
import com.example.ui.theme.PalettePale
import com.example.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class NavigationTab(val title: String) {
    DASHBOARD("داشبورد"),
    RULES("قوانین"),
    SERVER("تنظیمات"),
    LOGS("گزارش‌ها")
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MainAppScreen(viewModel = viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(viewModel: MainViewModel) {
    val rules by viewModel.rules.collectAsState()
    val config by viewModel.config.collectAsState()
    val filteredLogs by viewModel.filteredLogs.collectAsState()

    val totalLogsCount by viewModel.totalLogsCount.collectAsState()
    val successCount by viewModel.successCount.collectAsState()
    val failedCount by viewModel.failedCount.collectAsState()
    val rulesCount by viewModel.rulesCount.collectAsState()

    val serverHealthState by viewModel.serverHealthState.collectAsState()
    val endpointTestState by viewModel.endpointTestState.collectAsState()
    val cryptoSandboxState by viewModel.cryptoSandbox.collectAsState()
    val simulatedLogResult by viewModel.simulatedLogResult.collectAsState()
    val otpInquiryState by viewModel.otpInquiryState.collectAsState()
    val isOtpInquiryLoading by viewModel.isOtpInquiryLoading.collectAsState()
    val isOfflineSyncing by viewModel.isOfflineSyncing.collectAsState()
    val offlineSyncMessage by viewModel.offlineSyncMessage.collectAsState()

    val context = LocalContext.current
    val initialPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (!com.example.utils.PermissionHelper.areCriticalPermissionsGranted(context)) {
            initialPermissionLauncher.launch(com.example.utils.PermissionHelper.getRequiredRuntimePermissions())
        }
    }

    LaunchedEffect(offlineSyncMessage) {
        offlineSyncMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.clearOfflineSyncMessage()
        }
    }

    var showSimulateDialog by remember { mutableStateOf(false) }
    var showOtpInquiryDialog by remember { mutableStateOf(false) }
    var showPermissionsDialog by remember { mutableStateOf(false) }
    var showServerSettingsSheet by remember { mutableStateOf(false) }
    var showServerGuideSheet by remember { mutableStateOf(false) }
    var showBackgroundGuideSheet by remember { mutableStateOf(false) }
    var selectedLogForDetail by remember { mutableStateOf<ForwardLog?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = PaletteDarkest,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(PaletteDeep)
                                .border(1.dp, PaletteMedium, RoundedCornerShape(10.dp))
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_barpro_logo),
                                contentDescription = "BarPro Logo",
                                tint = Color.Unspecified,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "BarPro",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Black,
                                    color = PaletteLight
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Forwarder",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = PalettePale
                                )
                            }
                            Text(
                                text = "وضعیت لحظه‌ای اتصال و عملکرد سامانه",
                                style = MaterialTheme.typography.labelSmall,
                                color = PalettePale.copy(alpha = 0.8f)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showPermissionsDialog = true },
                        modifier = Modifier.testTag("top_permissions_action")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "چک‌لیست مجوزها",
                            tint = PalettePale
                        )
                    }
                    IconButton(
                        onClick = { showServerSettingsSheet = true },
                        modifier = Modifier.testTag("top_settings_action")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "تنظیمات",
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            DashboardScreen(
                config = config,
                serverHealthState = serverHealthState,
                totalCount = totalLogsCount,
                successCount = successCount,
                failedCount = failedCount,
                rulesCount = rulesCount,
                recentLogs = filteredLogs,
                onToggleMaster = { viewModel.toggleMasterSwitch(it) },
                onCheckHealthNow = { viewModel.checkServerHealthNow() },
                onOpenSimulate = { showSimulateDialog = true },
                onOpenOtpInquiry = { showOtpInquiryDialog = true },
                onOpenPermissions = { showPermissionsDialog = true },
                onOpenServerGuide = { showServerGuideSheet = true },
                onOpenBackgroundGuide = { showBackgroundGuideSheet = true },
                onNavigateToRules = { showServerSettingsSheet = true },
                onNavigateToServerConfig = { showServerSettingsSheet = true },
                onNavigateToLogs = { /* already shown on dashboard */ },
                onSelectLog = { log -> selectedLogForDetail = log },
                onSyncOfflineLogs = { viewModel.syncOfflineLogsNow() },
                isOfflineSyncing = isOfflineSyncing
            )
        }
    }

    // Modal: OTP Inquiry
    if (showOtpInquiryDialog) {
        OtpInquiryDialog(
            activeRules = rules.filter { it.isEnabled },
            isLoading = isOtpInquiryLoading,
            lastResult = otpInquiryState,
            onExecuteInquiry = { sender, timestamp ->
                viewModel.executeServerOtpInquiry(sender, timestamp)
            },
            onDismiss = {
                viewModel.clearOtpInquiryResult()
                showOtpInquiryDialog = false
            }
        )
    }

    // Modal: Test SMS
    if (showSimulateDialog) {
        TestSmsDialog(
            lastSimulatedLog = simulatedLogResult,
            onSimulate = { sender, msg ->
                viewModel.simulateIncomingSms(sender, msg)
            },
            onDismiss = {
                viewModel.clearSimulatedResult()
                showSimulateDialog = false
            }
        )
    }

    // Modal: Permissions Manager Screen
    if (showPermissionsDialog) {
        Dialog(
            onDismissRequest = { showPermissionsDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            PermissionManagerScreen(
                onBack = { showPermissionsDialog = false },
                onOpenBackgroundGuide = {
                    showPermissionsDialog = false
                    showBackgroundGuideSheet = true
                }
            )
        }
    }

    // Modal: Server Guide
    if (showServerGuideSheet) {
        ServerGuideSheet(
            secretKey = config.secretEncryptionKey,
            onDismiss = { showServerGuideSheet = false }
        )
    }

    // Modal: Background Execution & Battery Optimization Guide
    if (showBackgroundGuideSheet) {
        com.example.ui.screens.BackgroundExecutionGuideSheet(
            onDismiss = { showBackgroundGuideSheet = false }
        )
    }

    // Optional Admin Settings Sheet (تنظیمات اختیاری برای مدیر سامانه)
    if (showServerSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showServerSettingsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = PaletteDarkest,
            dragHandle = null
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "تنظیمات پیشرفته سرور و اتصال",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = PalettePale
                    )
                    IconButton(onClick = { showServerSettingsSheet = false }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "بستن", tint = PalettePale)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                ServerConfigScreen(
                    config = config,
                    serverHealthState = serverHealthState,
                    testState = endpointTestState,
                    cryptoSandboxState = cryptoSandboxState,
                    onSaveConfig = { viewModel.updateConfig(it) },
                    onRunTest = { url, authType, key, value, isEnc, secret ->
                        viewModel.runEndpointTest(url, authType, key, value, isEnc, secret)
                    },
                    onCheckServerHealth = { viewModel.checkServerHealthNow() },
                    onTestDisconnectNotification = { viewModel.triggerTestDisconnectAlert() },
                    onTestCryptoSandbox = { plain, secret ->
                        viewModel.testCryptoSandbox(plain, secret)
                    },
                    onGenerateKey = { viewModel.generateNewKey() },
                    onOpenServerGuide = { showServerGuideSheet = true }
                )
            }
        }
    }

    // Log Detail Dialog
    selectedLogForDetail?.let { log ->
        LogDetailDialog(
            log = log,
            onRetry = {
                viewModel.retryForward(log)
                selectedLogForDetail = null
            },
            onDismiss = { selectedLogForDetail = null }
        )
    }
}

@Composable
fun LogDetailDialog(
    log: ForwardLog,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val formatter = SimpleDateFormat("yyyy/MM/dd - HH:mm:ss", Locale.getDefault())
    val formattedTime = formatter.format(Date(log.receivedTimestamp))

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = PaletteDeep)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, PaletteMedium, RoundedCornerShape(20.dp))
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "جزئیات پیامک دریافتی",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = PalettePale
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "بستن", tint = PalettePale)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "فرستنده:", fontSize = 11.sp, color = PalettePale.copy(alpha = 0.7f))
                        Text(text = log.sender, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PaletteLight)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Type badge
                        val typeLabel = when (log.smsType) {
                            com.example.data.model.SmsType.UTCMS_CONFIRMATION -> "تایید بارنامه"
                            com.example.data.model.SmsType.UTCMS_OTP -> "کد تایید OTP"
                            com.example.data.model.SmsType.UTCMS_WARNING -> "هشدار سامانه"
                            com.example.data.model.SmsType.OTHER -> "پیامک عمومی"
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(PaletteDarkest)
                                .border(1.dp, PaletteMedium, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(text = typeLabel, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PaletteLight)
                        }
                        StatusBadge(status = log.status)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(text = "متن پیامک:", fontSize = 11.sp, color = PalettePale.copy(alpha = 0.7f))
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(PaletteDarkest)
                        .border(1.dp, PaletteMedium, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = log.messageBody,
                        fontSize = 13.sp,
                        color = PalettePale,
                        lineHeight = 20.sp
                    )
                }

                // Extracted Tracking Code
                log.trackingCode?.let { code ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(PaletteDarkest)
                            .border(1.dp, PaletteMedium, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = PaletteLight, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "کد رهگیری بارنامه: $code", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PaletteLight)
                        }
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("TrackingCode", code)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "کد رهگیری کپی شد", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "کپی", tint = PaletteLight, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                // Extracted OTP Code
                val otpToDisplay = log.otpCode ?: com.example.otp.OtpExtractor.extractOtp(log.messageBody)
                otpToDisplay?.let { otp ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(PaletteDarkest)
                            .border(1.dp, PaletteMedium, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Key, contentDescription = null, tint = PaletteLight, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "رمز یکبار مصرف (OTP): $otp", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PaletteLight)
                        }
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("OTP", otp)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "کد OTP کپی شد", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "کپی", tint = PaletteLight, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                if (log.driverId.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "شناسه راننده: ${log.driverId} • سیم‌کارت: ${log.simSlot + 1} • دفعات تلاش: ${log.retryCount}", fontSize = 11.sp, color = PalettePale.copy(alpha = 0.8f))
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(text = "زمان: $formattedTime", fontSize = 11.sp, color = PalettePale.copy(alpha = 0.7f))

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (log.status == ForwardStatus.FAILED) {
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = PaletteLight, contentColor = PaletteDarkest),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تلاش مجدد ارسال", fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = PaletteDarkest, contentColor = PalettePale),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("بستن")
                    }
                }
            }
        }
    }
}
