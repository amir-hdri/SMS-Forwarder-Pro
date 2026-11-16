package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.ForwardConfig
import com.example.network.BarProContract
import com.example.ui.theme.BarProAmber
import com.example.ui.theme.BarProAmberBg
import com.example.ui.theme.BarProBg
import com.example.ui.theme.BarProBorder
import com.example.ui.theme.BarProBorderCyan
import com.example.ui.theme.BarProCyan
import com.example.ui.theme.BarProEmerald
import com.example.ui.theme.BarProEmeraldBg
import com.example.ui.theme.BarProRose
import com.example.ui.theme.BarProRoseBg
import com.example.ui.theme.BarProSurface
import com.example.ui.theme.BarProSurfaceElevated
import com.example.ui.theme.BarProSurfaceSubtle
import com.example.ui.theme.BarProTextMuted
import com.example.ui.theme.BarProTextPrimary
import com.example.ui.theme.BarProTextSecondary
import com.example.ui.theme.PaletteDarkest
import com.example.ui.viewmodel.DriverActivationUiState
import com.example.ui.viewmodel.MainViewModel
import com.example.utils.PermissionHelper
import com.example.utils.SmsParser
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Streamlined single-screen onboarding and status dashboard for Forward-BarPro-Driver.
 * Eliminates manual deep-link and QR dependencies by providing:
 * 1. Runtime SMS & notification permission checklist with one-tap system grant launcher.
 * 2. Mobile phone entry (Iranian format: 09...) with real-time digit normalization.
 * 3. Automated endpoint resolution and credential provisioning from BarPro backend.
 * 4. Active listening status command center once enrolled.
 */
@Composable
fun ManagedDriverScreen(
    config: ForwardConfig,
    onToggleMaster: (Boolean) -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenBackgroundGuide: () -> Unit,
    pendingCount: Int = 0,
    viewModel: MainViewModel? = null,
    activationState: DriverActivationUiState? = null,
    onActivateDriver: ((phone: String) -> Unit)? = null,
    onResetActivation: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current

    // Observe permission changes on activity resume
    var permissionRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionRevision++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isReceiveSmsGranted = remember(permissionRevision) { PermissionHelper.isReceiveSmsGranted(context) }
    val isSendSmsGranted = remember(permissionRevision) { PermissionHelper.isSendSmsGranted(context) }
    val isPostNotificationGranted = remember(permissionRevision) { PermissionHelper.isPostNotificationGranted(context) }

    val areCriticalPermissionsGranted = isReceiveSmsGranted && isSendSmsGranted && isPostNotificationGranted

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionRevision++
    }

    // Resolve ViewModel if not directly provided
    val resolvedVm: MainViewModel? = viewModel ?: runCatching {
        (context as? androidx.activity.ComponentActivity)?.let {
            androidx.lifecycle.ViewModelProvider(it)[MainViewModel::class.java]
        }
    }.getOrNull()

    val vmState by (resolvedVm?.driverActivationState ?: MutableStateFlow(DriverActivationUiState())).collectAsState()
    val effectiveActivationState = activationState ?: vmState
    val effectiveOnActivate: (String) -> Unit = onActivateDriver ?: { phone -> resolvedVm?.resolveAndActivateDriver(phone) }
    val effectiveOnReset: () -> Unit = onResetActivation ?: { resolvedVm?.resetDriverActivation() }

    val isEnrolled = config.driverPhone.isNotBlank() && config.setupCompleted

    var phoneInput by remember { mutableStateOf("") }
    val cleanPhone = remember(phoneInput) {
        SmsParser.normalizePhoneNumber(phoneInput).filter { it.isDigit() }
    }
    val isPhoneValid = cleanPhone.matches(Regex("^09[0-9]{9}$"))

    var openedSmsSelection by remember { mutableStateOf(false) }
    LaunchedEffect(effectiveActivationState.needsSmsSelection) {
        if (effectiveActivationState.needsSmsSelection && !openedSmsSelection) {
            openedSmsSelection = true
            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BarProBg)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("managed_driver_screen"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Section
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "بارپرو راننده",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = BarProTextPrimary
                )
                Text(
                    text = if (isEnrolled) "سامانه ارسال خودکار کدهای بارنامه به سرور مرکزی"
                    else "راه‌اندازی سریع و اتصال خودکار به ناوگان بارپرو",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BarProTextSecondary
                )
            }

            if (!isEnrolled) {
                // =================================================================
                // ONBOARDING VIEW (UNCONFIGURED / NEW INSTALL)
                // =================================================================

                // 1. Runtime Permissions Banner Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("permissions_banner"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (areCriticalPermissionsGranted) BarProEmeraldBg else BarProAmberBg
                    ),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(
                            if (areCriticalPermissionsGranted) BarProEmerald.copy(alpha = 0.5f)
                            else BarProAmber.copy(alpha = 0.5f)
                        )
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (areCriticalPermissionsGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (areCriticalPermissionsGranted) BarProEmerald else BarProAmber,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = if (areCriticalPermissionsGranted) "مجوزهای پیامک و سیستم تأیید شده‌اند"
                                else "مجوزهای دسترسی پیامک الزامی است",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (areCriticalPermissionsGranted) BarProEmerald else BarProAmber
                            )
                        }

                        Text(
                            text = if (areCriticalPermissionsGranted)
                                "تمام دسترسی‌های دریافت و ارسال پیامک جهت دریافت کدهای ورود و ارسال پیامک سلامت فعال می‌باشند."
                            else
                                "جهت خواندن خودکار کدهای دریافتی و ارسال به سامانه بارپرو، مجوزهای دریافت و ارسال پیامک مورد نیاز است.",
                            style = MaterialTheme.typography.bodySmall,
                            color = BarProTextSecondary,
                            lineHeight = 18.sp
                        )

                        // Permission Badges
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PermissionStatusPill(
                                label = "دریافت پیامک",
                                isGranted = isReceiveSmsGranted,
                                modifier = Modifier.weight(1f)
                            )
                            PermissionStatusPill(
                                label = "ارسال پیامک",
                                isGranted = isSendSmsGranted,
                                modifier = Modifier.weight(1f)
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                PermissionStatusPill(
                                    label = "اعلان‌ها",
                                    isGranted = isPostNotificationGranted,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        if (!areCriticalPermissionsGranted) {
                            Button(
                                onClick = {
                                    val list = mutableListOf(
                                        Manifest.permission.RECEIVE_SMS,
                                        Manifest.permission.SEND_SMS
                                    )
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        list.add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    permissionsLauncher.launch(list.toTypedArray())
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("btn_request_permissions"),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BarProAmber,
                                    contentColor = PaletteDarkest
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("تأیید و اعطای مجوزهای پیامک", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // 2. Driver Phone Entry & Auto-Configuration Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = BarProSurface),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(BarProBorder)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "ورود مشخصات راننده",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = BarProTextPrimary
                        )

                        Text(
                            text = "شماره موبایل سیم‌کارت راننده را وارد کنید یا از تشخیص خودکار سیم‌کارت استفاده نمایید:",
                            style = MaterialTheme.typography.bodySmall,
                            color = BarProTextSecondary
                        )

                        // Mobile Phone Input Field (Strict LTR input)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                OutlinedTextField(
                                    value = phoneInput,
                                    onValueChange = { raw ->
                                        val filtered = raw.filter { it.isDigit() || it in "۰۱۲۳۴۵۶۷۸۹" || it == '+' }
                                        if (filtered.length <= 14) {
                                            phoneInput = filtered
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("driver_phone_input"),
                                    placeholder = {
                                        Text(
                                            "09123456789",
                                            color = BarProTextMuted,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Phone,
                                            contentDescription = null,
                                            tint = if (isPhoneValid) BarProCyan else BarProTextMuted
                                        )
                                    },
                                    trailingIcon = {
                                        if (isPhoneValid) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = BarProEmerald
                                            )
                                        } else if (phoneInput.isNotEmpty()) {
                                            IconButton(onClick = { phoneInput = "" }) {
                                                Icon(
                                                    imageVector = Icons.Default.Clear,
                                                    contentDescription = "پاک کردن",
                                                    tint = BarProTextMuted
                                                )
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Phone,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            focusManager.clearFocus()
                                            if (isPhoneValid && areCriticalPermissionsGranted) {
                                                effectiveOnActivate(cleanPhone)
                                            }
                                        }
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = BarProSurfaceSubtle,
                                        unfocusedContainerColor = BarProSurfaceSubtle,
                                        focusedBorderColor = BarProCyan,
                                        unfocusedBorderColor = BarProBorder,
                                        focusedTextColor = BarProTextPrimary,
                                        unfocusedTextColor = BarProTextPrimary
                                    )
                                )
                            }

                            // Validation Helper Text
                            Text(
                                text = when {
                                    phoneInput.isEmpty() -> "شماره موبایل ۱۱ رقمی راننده (مثال: 09123456789)"
                                    !cleanPhone.startsWith("09") -> "شماره موبایل باید با ۰۹ شروع شود"
                                    cleanPhone.length < 11 -> "${11 - cleanPhone.length} رقم دیگر وارد کنید"
                                    isPhoneValid -> "✓ فرمت شماره موبایل معتبر است"
                                    else -> "فرمت شماره موبایل نامعتبر است"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isPhoneValid) BarProEmerald else if (phoneInput.isNotEmpty()) BarProAmber else BarProTextMuted
                            )
                        }

                        // Activation Error Card
                        AnimatedVisibility(
                            visible = effectiveActivationState.errorMessage != null,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            effectiveActivationState.errorMessage?.let { errorText ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(BarProRoseBg)
                                        .border(1.dp, BarProRose.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                        .padding(12.dp)
                                        .testTag("activation_error_card")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Error,
                                            contentDescription = null,
                                            tint = BarProRose,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Column {
                                            Text(
                                                text = "خطا در فعال‌سازی",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = BarProRose
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = errorText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = BarProTextPrimary
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                OutlinedButton(
                                                    onClick = {
                                                        if (isPhoneValid) effectiveOnActivate(cleanPhone)
                                                        else effectiveOnActivate("")
                                                    },
                                                    modifier = Modifier.weight(1f).height(36.dp),
                                                    shape = RoundedCornerShape(8.dp),
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BarProRose),
                                                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                                                        brush = androidx.compose.ui.graphics.SolidColor(BarProRose.copy(alpha = 0.5f))
                                                    )
                                                ) {
                                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("تلاش مجدد", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                                OutlinedButton(
                                                    onClick = effectiveOnReset,
                                                    modifier = Modifier.weight(1f).height(36.dp),
                                                    shape = RoundedCornerShape(8.dp),
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BarProTextSecondary),
                                                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                                                        brush = androidx.compose.ui.graphics.SolidColor(BarProBorder)
                                                    )
                                                ) {
                                                    Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("بازنشانی", fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Action Button: Primary Activation
                        val isPhoneEntered = phoneInput.isNotBlank()
                        val isPrimaryButtonEnabled = !effectiveActivationState.isLoading &&
                            (!isPhoneEntered || isPhoneValid || !areCriticalPermissionsGranted)

                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                if (!areCriticalPermissionsGranted) {
                                    val list = mutableListOf(
                                        Manifest.permission.RECEIVE_SMS,
                                        Manifest.permission.SEND_SMS
                                    )
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        list.add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    permissionsLauncher.launch(list.toTypedArray())
                                } else if (effectiveActivationState.needsSmsSelection) {
                                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
                                } else if (isPhoneValid) {
                                    effectiveOnActivate(cleanPhone)
                                } else if (!isPhoneEntered) {
                                    effectiveOnActivate("")
                                }
                            },
                            enabled = isPrimaryButtonEnabled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("btn_auto_activate"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPhoneValid) BarProEmerald else BarProCyan,
                                contentColor = PaletteDarkest,
                                disabledContainerColor = BarProSurfaceElevated,
                                disabledContentColor = BarProTextMuted
                            )
                        ) {
                            if (effectiveActivationState.isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .testTag("activation_loading_indicator"),
                                    color = PaletteDarkest,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = effectiveActivationState.progressMessage ?: "در حال اتصال به بارپرو…",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            } else {
                                Icon(
                                    imageVector = if (isPhoneValid) Icons.Default.CheckCircle else Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = when {
                                        !areCriticalPermissionsGranted -> "تأیید مجوزها و فعال‌سازی"
                                        effectiveActivationState.needsSmsSelection -> "انتخاب سیم‌کارت پیامک در Android"
                                        isPhoneValid -> "استعلام و فعال‌سازی شماره راننده"
                                        isPhoneEntered -> "شماره موبایل را کامل وارد کنید"
                                        else -> "فعال‌سازی با شماره راننده یا سیم‌کارت"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        // Cancel Loading / Reset Button (Shown during loading to prevent UI lockout)
                        if (effectiveActivationState.isLoading) {
                            OutlinedButton(
                                onClick = effectiveOnReset,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .testTag("btn_cancel_loading"),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BarProAmber),
                                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                                    brush = androidx.compose.ui.graphics.SolidColor(BarProAmber.copy(alpha = 0.5f))
                                )
                            ) {
                                Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("لغو فرآیند و بازنشانی وضعیت", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Fallback Auto-Detection Button (available when not loading so driver can fall back to SIM auto-activation)
                        if (!effectiveActivationState.isLoading) {
                            OutlinedButton(
                                onClick = {
                                    focusManager.clearFocus()
                                    if (!areCriticalPermissionsGranted) {
                                        val list = mutableListOf(
                                            Manifest.permission.RECEIVE_SMS,
                                            Manifest.permission.SEND_SMS
                                        )
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            list.add(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                        permissionsLauncher.launch(list.toTypedArray())
                                    } else if (effectiveActivationState.needsSmsSelection) {
                                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
                                    } else {
                                        effectiveOnActivate("")
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .testTag("btn_fallback_auto_activate"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = BarProTextSecondary),
                                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                                    brush = androidx.compose.ui.graphics.SolidColor(BarProBorder)
                                )
                            ) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("فعال‌سازی خودکار از طریق سیم‌کارت (پشتیبان)", fontSize = 12.sp)
                            }
                        }
                    }
                }

            } else {
                // =================================================================
                // OPERATIONAL / ACTIVE DASHBOARD VIEW (ENROLLED DRIVER)
                // =================================================================

                // 1. Driver Profile & Live Liveness Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("active_status_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = BarProSurface),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(BarProBorderCyan)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = if (config.driverFullName.isNotBlank()) config.driverFullName else "راننده فعال بارپرو",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = BarProTextPrimary
                                )
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Text(
                                        text = config.driverPhone,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = BarProCyan,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            // Active Status Badge
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BarProEmeraldBg)
                                    .border(1.dp, BarProEmerald.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(BarProEmerald)
                                    )
                                    Text(
                                        text = "هویت ذخیره شده",
                                        color = BarProEmerald,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }


                    }
                }

                // 2. Master Forwarding Switch Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = BarProSurface),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(BarProBorder)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = if (config.isMasterEnabled && config.userConsentGiven) "ارسال خودکار پیامک فعال است"
                                    else "ارسال پیامک موقتاً خاموش است",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (config.isMasterEnabled && config.userConsentGiven) BarProEmerald else BarProAmber
                                )
                                Text(
                                    text = "ارسال فوری کدهای ورود و بارنامه به وب‌هوک بارپرو",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BarProTextSecondary
                                )
                            }
                            Switch(
                                checked = config.isMasterEnabled && config.userConsentGiven,
                                onCheckedChange = onToggleMaster,
                                enabled = isEnrolled,
                                modifier = Modifier.testTag("driver_forwarding_switch"),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = BarProEmerald,
                                    checkedTrackColor = BarProEmeraldBg,
                                    uncheckedThumbColor = BarProTextMuted,
                                    uncheckedTrackColor = BarProSurfaceElevated
                                )
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "در انتظار ارسال یا تأیید پیامک:",
                                style = MaterialTheme.typography.bodySmall,
                                color = BarProTextSecondary
                            )
                            Text(
                                text = "$pendingCount پیامک",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = if (pendingCount > 0) BarProAmber else BarProEmerald
                            )
                        }
                    }
                }

                // 3. Operational Action Buttons
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onOpenPermissions,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BarProSurfaceElevated,
                            contentColor = BarProTextPrimary
                        )
                    ) {
                        Icon(imageVector = Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("مدیریت و وضعیت مجوزهای پیامک", fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = onOpenBackgroundGuide,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BarProSurfaceElevated,
                            contentColor = BarProTextPrimary
                        )
                    ) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("راهنمای فعالیت دائم در پس‌زمینه (باتری)", fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = effectiveOnReset,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_reset_driver"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BarProTextSecondary),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                            brush = androidx.compose.ui.graphics.SolidColor(BarProBorder)
                        )
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("تغییر شماره / فعال‌سازی مجدد", fontSize = 12.sp)
                    }
                }
            }

            // Footer Notice
            Text(
                text = "برای دریافت و ارسال کدهای بارنامه روی گوشی راننده اینترنت اختیاری است؛ در صورت قطعی اینترنت، پیامک به صورت مستقیم به هاب ارسال می‌گردد. آنتن‌دهی و اعتبار پیامک سیم‌کارت باید برقرار باشد.",
                style = MaterialTheme.typography.labelSmall,
                color = BarProTextMuted,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PermissionStatusPill(
    label: String,
    isGranted: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isGranted) BarProEmeraldBg else BarProAmberBg)
            .border(
                1.dp,
                if (isGranted) BarProEmerald.copy(alpha = 0.3f) else BarProAmber.copy(alpha = 0.3f),
                RoundedCornerShape(8.dp)
            )
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.Check else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isGranted) BarProEmerald else BarProAmber,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isGranted) BarProEmerald else BarProAmber
            )
        }
    }
}

internal fun normalizeActivationLink(raw: String, serverBase: String = "https://87.107.160.251/"): String {
    val trimmed = raw.trim()
    if (trimmed.startsWith("barpro-forwarder://enroll#")) return trimmed
    if (trimmed.contains("ticket=")) {
        val ticket = trimmed.substringAfter("ticket=").substringBefore("&").substringBefore("#")
        val server = if (trimmed.contains("server=")) {
            trimmed.substringAfter("server=").substringBefore("&").substringBefore("#")
        } else serverBase
        return "barpro-forwarder://enroll#ticket=$ticket&server=${java.net.URLEncoder.encode(server, "UTF-8")}"
    }
    if (trimmed.length in 20..1024 && !trimmed.contains(" ") && !trimmed.contains("/")) {
        return "barpro-forwarder://enroll#ticket=$trimmed&server=${java.net.URLEncoder.encode(serverBase, "UTF-8")}"
    }
    return trimmed
}
