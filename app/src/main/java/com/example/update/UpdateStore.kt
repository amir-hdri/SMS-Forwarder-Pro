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
    @Volatile private var policyStorageFailed = false

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

    val lastVerifiedManifestDocument: String?
        get() = prefs.getString("verified_release_document", null)

    fun recordManifest(document: String, version: Long) {
        check(prefs.edit().putString("verified_release_document", document)
            .putLong(KEY_MANIFEST_VERSION, version).commit()) { "Cannot persist verified release" }
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
        get() = policyStorageFailed || prefs.getBoolean(KEY_BLOCKED, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCKED, value).apply()

    /** Accepted policies establish a durable version floor, independent of who installs the APK. */
    fun recordConfigRequirement(required: Int?, installed: Int, revision: Long) =
        recordRequirement(KEY_CONFIG_REQUIRED, required, installed, revision)
    fun recordReleaseRequirement(required: Int, installed: Int, revision: Long) =
        recordRequirement(KEY_RELEASE_REQUIRED, required, installed, revision)

    val needsLegacyPolicy: Boolean get() = prefs.getBoolean(KEY_LEGACY_UNKNOWN,
        blockedByMandatoryUpdate && !prefs.contains(KEY_REQUIRED))

    @Synchronized private fun recordRequirement(source: String, required: Int?, installed: Int, revision: Long) {
        require((required == null || required >= 0) && revision > 0)
        val revisionKey = source + "_policy_revision"
        val previousRevision = prefs.getLong(revisionKey, 0)
        if (revision < previousRevision || (revision == previousRevision && required != null && required != prefs.getInt(source, -1))) return
        val legacyUnknown = prefs.getBoolean(KEY_LEGACY_UNKNOWN,
            blockedByMandatoryUpdate && !prefs.contains(KEY_REQUIRED))
        val floor = required ?: prefs.getInt(source, -1)
        val config = if (source == KEY_CONFIG_REQUIRED) floor else prefs.getInt(KEY_CONFIG_REQUIRED, -1)
        val release = if (source == KEY_RELEASE_REQUIRED) floor else prefs.getInt(KEY_RELEASE_REQUIRED, -1)
        val stillUnknown = legacyUnknown && (config < 0 || release < 0)
        val target = maxOf(config, release, 0)
        val blocked = if (stillUnknown || installed <= 0) blockedByMandatoryUpdate || installed < target else installed < target
        commitPolicy(prefs.edit().putInt(source, floor).putLong(revisionKey, revision).putInt(KEY_REQUIRED, target)
            .putBoolean(KEY_LEGACY_UNKNOWN, stillUnknown).putBoolean(KEY_BLOCKED, blocked))
    }

    @Synchronized fun reconcileInstalledVersion(installed: Int) {
        if (installed <= 0 || prefs.getBoolean(KEY_LEGACY_UNKNOWN,
                blockedByMandatoryUpdate && !prefs.contains(KEY_REQUIRED))) return
        commitPolicy(prefs.edit().putBoolean(KEY_BLOCKED, installed < prefs.getInt(KEY_REQUIRED, 0)))
    }

    private fun commitPolicy(editor: SharedPreferences.Editor) {
        try {
            check(editor.commit()) { "Cannot persist mandatory update policy" }
            policyStorageFailed = false
        } catch (error: Exception) {
            policyStorageFailed = true
            throw error
        }
    }

    companion object {
        private const val PREFS_NAME = "barpro_update_state"
        private const val KEY_CONFIG_VERSION = "config_version"
        private const val KEY_CONFIG_DOCUMENT = "config_document"
        private const val KEY_MANIFEST_VERSION = "manifest_version"
        private const val KEY_STAGED_VERSION_CODE = "staged_version_code"
        private const val KEY_LAST_CHECK = "last_check_at"
        private const val KEY_LAST_STATUS = "last_status_fa"
        private const val KEY_BLOCKED = "blocked_by_mandatory_update"
        private const val KEY_CONFIG_REQUIRED = "config_required_version_code"
        private const val KEY_RELEASE_REQUIRED = "release_required_version_code"
        private const val KEY_REQUIRED = "required_version_code"
        private const val KEY_LEGACY_UNKNOWN = "legacy_update_requirement_unknown"

        @Volatile
        private var instance: UpdateStore? = null

        fun getInstance(context: Context): UpdateStore = instance ?: synchronized(this) {
            instance ?: UpdateStore(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}
