package com.example.update

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * A signature-verified description of one APK release.
 *
 * Trust model for the APK itself rests on two independent controls, neither of which depends on
 * the transport being TLS:
 *
 *  1. [apkSha256] arrives inside a document signed with the operator's offline ECDSA key, so an
 *     attacker who controls the network cannot substitute a different archive.
 *  2. Android refuses to install an update whose signing certificate differs from the installed
 *     app's (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). That is a platform guarantee, not app logic,
 *     so even a forged manifest cannot cause foreign code to be installed.
 *
 * The app does not additionally parse the archive's own signing block: the downloaded file lives in
 * private storage that the package manager cannot read, so `getPackageArchiveInfo` is not usable
 * here. The two controls above are what the security of this path actually rests on.
 */
data class UpdateManifest(
    val version: Long,
    val issuedAt: Long,
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val apkSha256: String,
    val apkSizeBytes: Long,
    val releaseNotesFa: String?,
    /** When true the app stops forwarding and shows a blocking prompt until the update lands. */
    val mandatory: Boolean
) {
    companion object {
        class RejectedException(message: String) : IllegalArgumentException(message)

        /** Refuse absurd downloads outright; a forwarder APK is a few tens of megabytes at most. */
        const val MAX_APK_BYTES = 150L * 1024L * 1024L

        private val SHA256_HEX = Regex("[0-9a-f]{64}")
        private const val MAX_FUTURE_SKEW_SECONDS = 48L * 3600L

        /**
         * @param payload JSON already proven authentic by [SignedPayload.verify].
         * @param audience this build's applicationId.
         * @param knownVersion highest manifest version already seen, for anti-rollback.
         */
        fun parse(
            payload: JSONObject,
            audience: String,
            knownVersion: Long,
            nowSeconds: Long
        ): UpdateManifest {
            val documentAudience = payload.optString("audience")
            if (documentAudience != audience) {
                throw RejectedException("Manifest audience '$documentAudience' is not '$audience'")
            }

            val version = payload.optLong("version", 0L)
            if (version <= 0L) throw RejectedException("Manifest is missing a positive version")
            if (version <= knownVersion) {
                throw RejectedException("Manifest version $version is not newer than $knownVersion")
            }

            val issuedAt = payload.optLong("issuedAt", 0L)
            if (issuedAt <= 0L) throw RejectedException("Manifest is missing issuedAt")
            if (issuedAt > nowSeconds + MAX_FUTURE_SKEW_SECONDS) {
                throw RejectedException("Manifest is dated too far in the future")
            }

            val versionCode = payload.optInt("versionCode", 0)
            if (versionCode <= 0) throw RejectedException("Manifest is missing a positive versionCode")

            val versionName = payload.optString("versionName").trim()
            if (versionName.isEmpty() || versionName.length > 64) {
                throw RejectedException("Manifest versionName is missing or too long")
            }

            val apkUrlRaw = payload.optString("apkUrl").trim()
            val apkUrl = apkUrlRaw.toHttpUrlOrNull()
                ?: throw RejectedException("Manifest apkUrl is not a valid URL")
            if (apkUrl.username.isNotEmpty() || apkUrl.password.isNotEmpty() || apkUrl.fragment != null) {
                throw RejectedException("Manifest apkUrl must not carry credentials or a fragment")
            }

            val sha = payload.optString("apkSha256").trim().lowercase()
            if (!SHA256_HEX.matches(sha)) {
                throw RejectedException("Manifest apkSha256 must be 64 lowercase hex characters")
            }

            val size = payload.optLong("apkSizeBytes", 0L)
            if (size <= 0L || size > MAX_APK_BYTES) {
                throw RejectedException("Manifest apkSizeBytes must be between 1 and $MAX_APK_BYTES")
            }

            val notes = payload.optString("releaseNotesFa").trim().take(1000).ifEmpty { null }

            return UpdateManifest(
                version = version,
                issuedAt = issuedAt,
                versionCode = versionCode,
                versionName = versionName,
                apkUrl = apkUrl.toString(),
                apkSha256 = sha,
                apkSizeBytes = size,
                releaseNotesFa = notes,
                mandatory = payload.optBoolean("mandatory", false)
            )
        }
    }
}
