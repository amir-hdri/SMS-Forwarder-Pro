package com.example.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface UpdateCheck {
    object UpToDate : UpdateCheck
    data class Available(val manifest: UpdateManifest) : UpdateCheck
    data class Failed(val reasonFa: String) : UpdateCheck
}

sealed interface StageResult {
    data class Staged(val versionCode: Int, val apk: File) : StageResult
    data class Failed(val reasonFa: String) : StageResult
}

/**
 * Self-update over a plain HTTP(S) file drop, with no app store involved.
 *
 * Why this is safe without TLS: the APK's SHA-256 is pinned inside an ECDSA-signed manifest, and
 * Android independently refuses to install an update signed by a different certificate than the
 * installed app. See [UpdateManifest] for the full argument.
 *
 * On Android 12+ (API 31) the commit can complete with no dialog at all, because the platform
 * grants silent installs when the session is an app **updating itself** and the installer holds
 * `UPDATE_PACKAGES_WITHOUT_USER_ACTION`. Where that is refused — older releases, or OEM builds such
 * as Xiaomi HyperOS that allowlist silent installers — the platform answers
 * [PackageInstaller.STATUS_PENDING_USER_ACTION] and [InstallResultReceiver] turns it into a
 * one-tap notification. Both paths preserve app data, settings and granted permissions; neither
 * uninstalls anything.
 */
class AppUpdateManager(
    private val context: Context,
    private val publicKeyBase64: String,
    private val store: UpdateStore = UpdateStore.getInstance(context),
    private val httpClient: OkHttpClient = defaultClient()
) {

    /** Fetches and verifies the release manifest, then compares it with the installed build. */
    suspend fun check(manifestUrl: String): UpdateCheck = withContext(Dispatchers.IO) {
        if (publicKeyBase64.isBlank()) {
            return@withContext UpdateCheck.Failed("کلید عمومی امضای آپدیت در این نسخه تنظیم نشده است.")
        }
        val document = try {
            val request = Request.Builder().url(manifestUrl).header("User-Agent", USER_AGENT).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateCheck.Failed("دریافت اطلاعات نسخه ناموفق بود (HTTP ${response.code}).")
                }
                // peekBody reads *at most* the limit, so a short document is fine and an
                // oversized one cannot exhaust memory.
                response.peekBody(MANIFEST_READ_LIMIT).string()
            }
        } catch (error: IOException) {
            return@withContext UpdateCheck.Failed("ارتباط با سرور آپدیت برقرار نشد.")
        }
        if (document.isBlank()) {
            return@withContext UpdateCheck.Failed("پاسخ سرور آپدیت خالی بود.")
        }

        val manifest = try {
            val payload = SignedPayload.verify(document, publicKeyBase64)
            UpdateManifest.parse(
                payload = payload,
                audience = context.packageName,
                knownVersion = store.manifestVersion,
                nowSeconds = System.currentTimeMillis() / 1000L
            )
        } catch (error: SignedPayload.InvalidSignatureException) {
            return@withContext UpdateCheck.Failed("امضای فایل نسخه معتبر نیست؛ آپدیت رد شد.")
        } catch (error: UpdateManifest.Companion.RejectedException) {
            // A non-newer manifest is the normal steady state, not an error worth alarming about.
            return@withContext UpdateCheck.UpToDate
        }

        store.recordManifest(manifest.version)
        if (manifest.versionCode <= installedVersionCode()) UpdateCheck.UpToDate
        else UpdateCheck.Available(manifest)
    }

    /** Downloads the APK into app-private storage, enforcing the manifest's size and digest. */
    suspend fun download(manifest: UpdateManifest): StageResult = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, STAGE_DIR).apply { mkdirs() }
        // Never let old archives accumulate, and never install a leftover from a previous attempt.
        directory.listFiles()?.forEach { it.delete() }
        val target = File(directory, "update-${manifest.versionCode}.apk")

        try {
            val request = Request.Builder().url(manifest.apkUrl).header("User-Agent", USER_AGENT).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext StageResult.Failed("دانلود بسته نصب ناموفق بود (HTTP ${response.code}).")
                }
                val stream = response.body?.byteStream()
                    ?: return@withContext StageResult.Failed("بدنه‌ی پاسخ دانلود خالی بود.")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                stream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            total += read
                            if (total > manifest.apkSizeBytes) {
                                target.delete()
                                return@withContext StageResult.Failed("حجم بسته با مقدار اعلام‌شده مغایرت دارد.")
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (total != manifest.apkSizeBytes) {
                    target.delete()
                    return@withContext StageResult.Failed("دانلود ناقص ماند؛ دوباره تلاش می‌شود.")
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != manifest.apkSha256) {
                    target.delete()
                    return@withContext StageResult.Failed("اثر انگشت بسته نصب با فایل امضاشده مطابقت ندارد؛ نصب انجام نشد.")
                }
            }
        } catch (error: IOException) {
            target.delete()
            return@withContext StageResult.Failed("دانلود بسته نصب قطع شد.")
        }

        store.stagedVersionCode = manifest.versionCode
        StageResult.Staged(manifest.versionCode, target)
    }

    /**
     * Hands the staged APK to the platform installer.
     *
     * Returns true when the session was committed; the actual outcome arrives asynchronously at
     * [InstallResultReceiver], including the pending-user-action case.
     */
    fun install(apk: File, versionCode: Int, versionName: String?): Boolean {
        if (!apk.isFile || apk.length() <= 0L) return false
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Honoured only for a self-update by a holder of UPDATE_PACKAGES_WITHOUT_USER_ACTION;
                // otherwise the platform falls back to STATUS_PENDING_USER_ACTION, which we handle.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        var sessionId = -1
        return try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite(APK_ENTRY_NAME, 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                session.commit(statusIntentSender(versionCode, versionName))
            }
            true
        } catch (error: IOException) {
            if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
            store.lastStatusFa = "آماده‌سازی نصب ناموفق بود."
            false
        } catch (error: SecurityException) {
            if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
            store.lastStatusFa = "سیستم اجازه‌ی نصب به برنامه نداد."
            false
        }
    }

    private fun statusIntentSender(versionCode: Int, versionName: String?): android.content.IntentSender {
        val intent = Intent(context, InstallResultReceiver::class.java).apply {
            action = InstallResultReceiver.ACTION_INSTALL_STATUS
            putExtra(InstallResultReceiver.EXTRA_TARGET_VERSION_CODE, versionCode)
            putExtra(InstallResultReceiver.EXTRA_TARGET_VERSION_NAME, versionName)
        }
        // MUTABLE is required from API 31: the platform fills in status extras on this intent.
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, versionCode, intent, flags).intentSender
    }

    fun installedVersionCode(): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        PackageInfoCompat.getLongVersionCode(info).toInt()
    } catch (error: Exception) {
        0
    }

    /**
     * Whether the per-source "install unknown apps" switch is on for this app. Required from
     * Android 8; granted once by the driver and then permanent.
     */
    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Settings screen where the driver enables installs from this app. */
    fun unknownSourcesSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun stagedApk(versionCode: Int): File? =
        File(File(context.filesDir, STAGE_DIR), "update-$versionCode.apk").takeIf { it.isFile }

    fun clearStaged() {
        File(context.filesDir, STAGE_DIR).listFiles()?.forEach { it.delete() }
        store.stagedVersionCode = 0
    }

    companion object {
        const val USER_AGENT = "BarPro-Forwarder-Android"
        private const val STAGE_DIR = "updates"
        private const val APK_ENTRY_NAME = "barpro-forwarder.apk"
        private const val MANIFEST_READ_LIMIT = SignedPayload.MAX_DOCUMENT_BYTES.toLong()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .followRedirects(true)
            .build()
    }
}
