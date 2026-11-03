package com.example.enrollment

import android.content.Context
import com.example.BuildConfig
import com.example.crypto.SecureStorageManager
import com.example.data.model.ForwardConfig
import com.example.data.repository.SmsForwardRepository
import com.example.utils.PermissionHelper

class HubInstallRuntime private constructor(private val context: Context) {
    private val repository = SmsForwardRepository.getInstance(context)
    private val storage = SecureStorageManager.getInstance(context)
    private val key = DeviceBootstrapKey()
    private val server = EnrollmentContract.trustedServer(BuildConfig.DEFAULT_ENDPOINT_URL.ifBlank { ForwardConfig().endpointUrl })
    val manager = HubInstallManager(BuildConfig.APP_ROLE, key, repository, profile = {
        if (!BuildConfig.HAS_PRIVATE_HUB_PROFILE) null else {
            val document = context.assets.open(HubInstallContract.ASSET_NAME).bufferedReader().use { it.readText() }
            val hash = HubInstallContract.capabilityHash(org.json.JSONObject(document).getString("capability"))
            if (hash == storage.getCompletedHubInstallHash()) null
            else HubInstallContract.parseProfile(document, BuildConfig.APP_ROLE, server, System.currentTimeMillis() / 1000L)
        }
    }, redeem = { profile, device, deviceKey -> BootstrapClient(profile.server).enrollHub(profile.capability, device, deviceKey) },
        loadStage = storage::getPendingHubInstall, saveStage = storage::savePendingHubInstall,
        permissions = { PermissionHelper.areForwardingPermissionsGranted(context) }, activated = {
            val latest = repository.getConfig()
            if (latest.isMasterEnabled && latest.userConsentGiven && PermissionHelper.areForwardingPermissionsGranted(context)) {
                com.example.service.HeartbeatAlarmManager.scheduleNext(context, latest.healthCheckIntervalMinutes)
                runCatching { com.example.service.MaintenanceWorker.schedule(context) }
                runCatching { com.example.service.SmsForwarderService.startService(context) }
                repository.performHeartbeatAndCommandPoll()
            }
        }, completedHash = storage::getCompletedHubInstallHash, saveCompletedHash = storage::saveCompletedHubInstallHash)

    companion object {
        @Volatile private var instance: HubInstallRuntime? = null
        fun get(context: Context): HubInstallRuntime = synchronized(this) {
            val app = context.applicationContext
            instance?.takeIf { it.context === app } ?: HubInstallRuntime(app).also { instance = it }
        }
    }
}
