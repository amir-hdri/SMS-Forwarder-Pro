package com.example.update

import android.content.Context
import android.content.SharedPreferences

/**
 * Durable state for the config/update channel.
 *
 * The signed documents are kept verbatim rather than as parsed fields so that every load
 * re-verifies the operator signature instead of trusting whatever is sitting in preferences. The
 * monotonic version counters live alongside them to make replay of an older captured document fail
 * closed. (On a rooted device an attacker can edit app-private preferences and lower a counter;
 * nothing stored on the handset can prevent that, and the ECDSA signature remains the real gate.)
 */
class UpdateStore private constructor(private val prefs: SharedPreferences) {

    var configVersion: Long
        get() = prefs.getLong(KEY_CONFIG_VERSION, 0L)
        private set(value) = prefs.edit().putLong(KEY_CONFIG_VERSION, value).apply()

    var manifestVersion: Long
        get() = prefs.getLong(KEY_MANIFEST_VERSION, 0L)
        private set(value) = prefs.edit().putLong(KEY_MANIFEST_VERSION, value).apply()

    /** The last config document that verified, so a failed fetch keeps the fleet on known-good settings. */
    val lastGoodConfigDocument: String?
        get() = prefs.getString(KEY_CONFIG_DOCUMENT, null)

    fun recordConfig(document: String, version: Long) {
        prefs.edit()
            .putString(KEY_CONFIG_DOCUMENT, document)
            .putLong(KEY_CONFIG_VERSION, version)
            .apply()
    }

    fun recordConfig(version: Long) {
        configVersion = version
    }

    fun recordManifest(version: Long) {
        manifestVersion = version
    }

    /** versionCode of an APK already downloaded and verified but not yet installed. */
    var stagedVersionCode: Int
        get() = prefs.getInt(KEY_STAGED_VERSION_CODE, 0)
        set(value) = prefs.edit().putInt(KEY_STAGED_VERSION_CODE, value).apply()

    var lastCheckAtMillis: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK, value).apply()

    var lastStatusFa: String?
        get() = prefs.getString(KEY_LAST_STATUS, null)
        set(value) = prefs.edit().putString(KEY_LAST_STATUS, value).apply()

    /** Set when a mandatory release is pending so the forwarder can refuse to run on a stale build. */
    var blockedByMandatoryUpdate: Boolean
        get() = prefs.getBoolean(KEY_BLOCKED, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCKED, value).apply()

    companion object {
        private const val PREFS_NAME = "barpro_update_state"
        private const val KEY_CONFIG_VERSION = "config_version"
        private const val KEY_CONFIG_DOCUMENT = "config_document"
        private const val KEY_MANIFEST_VERSION = "manifest_version"
        private const val KEY_STAGED_VERSION_CODE = "staged_version_code"
        private const val KEY_LAST_CHECK = "last_check_at"
        private const val KEY_LAST_STATUS = "last_status_fa"
        private const val KEY_BLOCKED = "blocked_by_mandatory_update"

        @Volatile
        private var instance: UpdateStore? = null

        fun getInstance(context: Context): UpdateStore = instance ?: synchronized(this) {
            instance ?: UpdateStore(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}
