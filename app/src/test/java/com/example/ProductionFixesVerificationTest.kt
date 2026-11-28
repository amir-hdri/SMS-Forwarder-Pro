package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.AesEncryptionUtils
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.model.AuthType
import com.example.data.model.ForwardConfig
import com.example.data.model.ForwardLog
import com.example.data.model.ForwardStatus
import com.example.data.model.SmsType
import com.example.data.repository.SmsForwardRepository
import com.example.enrollment.HubInstallContract
import com.example.network.BarProContract
import com.example.network.ServerClock
import com.example.network.SmsForwarderClient
import com.example.update.ConfigFetch
import com.example.update.RemoteConfig
import com.example.update.RemoteConfigClient
import com.example.update.UpdateStore
import com.example.utils.SmsParser
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProductionFixesVerificationTest {

    @get:Rule
    val secureStorage = SecureStorageTestRule()

    private lateinit var context: Application
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ServerClock.resetForTest(context)
        SmsParser.setRemoteOverlay(emptyList(), emptyList())
        context.getSharedPreferences("barpro_update_state", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        ServerClock.resetForTest(context)
        SmsParser.setRemoteOverlay(emptyList(), emptyList())
        context.getSharedPreferences("barpro_update_state", Context.MODE_PRIVATE).edit().clear().commit()
        db.close()
    }

    @Test
    fun serverClockCalibratesIranDstSkewAndRejectsTrulyExpiredMessages() = runBlocking {
        shadowOf(context).grantPermissions(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)
        val phoneNowMs = System.currentTimeMillis()
        val serverOffsetMs = 3_600_000L // Real server UTC is 1 hour ahead of phone's broken DST clock

        // Uncalibrated arrival carries phone's broken UTC; HTTP 410 Date header calibrates and retries once.
        val rfc1123 = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val calls = AtomicInteger(0)
        val seenTimestamps = mutableListOf<Long>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val bodyJson = JSONObject(okio.Buffer().apply { req.body!!.writeTo(this) }.readUtf8())
            val ts = bodyJson.optLong("timestamp", 0L)
            seenTimestamps.add(ts)
            val simulatedServerNow = System.currentTimeMillis() + serverOffsetMs
            val attempt = calls.incrementAndGet()
            val (code, json) = if (attempt == 1 && ts < simulatedServerNow - 300_000L) {
                410 to """{"detail":"SMS OTP has expired"}"""
            } else {
                200 to """{"success":true,"status":"success","otp_detected":true}"""
            }
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Gone")
                .header("Date", rfc1123.format(Date(simulatedServerNow)))
                .body(json.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

        val cfg = ForwardConfig(
            isMasterEnabled = true,
            userConsentGiven = true,
            setupCompleted = true,
            driverPhone = "09120000001",
            authHeaderValue = "a".repeat(64),
            forwarderSecret = "a".repeat(64),
            endpointUrl = "http://87.107.160.251/api/v1/otp/sms-forwarder/09120000001"
        )
        db.forwardConfigDao().insertOrUpdate(cfg)

        val outboxKey = SecureStorageManager.getInstance(context).getOrCreateOutboxKey()
        val enc = AesEncryptionUtils.encrypt("کد ورود به سامانه بارپرو: 789123", outboxKey)
        val logId = db.forwardLogDao().insertLog(
            ForwardLog(
                sender = "20007777",
                messageBody = "کد ورود به سامانه بارپرو: ***",
                recipientPhone = "09120000001",
                receivedTimestamp = phoneNowMs,
                smsType = SmsType.UTCMS_OTP,
                encryptedBody = "v2:${enc.iv}:${enc.ciphertext}"
            )
        )
        ServerClock.markLogCaptured(logId)

        val repo = SmsForwardRepository(db, client = SmsForwarderClient(http, appContext = context), appContext = context)
        val result = repo.transmitPendingLog(logId)
        assertTrue("HTTP 410 due to 1h phone DST skew should calibrate from Date header and succeed on retry", result.isSuccess)
        assertEquals(2, calls.get())
        assertTrue("Second attempt must carry calibrated real UTC timestamp", seenTimestamps[1] >= phoneNowMs + serverOffsetMs - 15_000L)

        // Truly expired SMS (captured >300s ago monotonically) must never be renewed.
        val serverNowCheck = System.currentTimeMillis() + serverOffsetMs
        ServerClock.markLogCaptured(logId, elapsedRealtimeMs = 1_000L)
        val rejected = ServerClock.correctTimestampAfterClockRejection(
            logId = logId,
            originalTimestampMs = System.currentTimeMillis(),
            serverDateMs = serverNowCheck,
            nowElapsedMs = 310_000L
        )
        assertNull("Actually expired >300s SMS must return null and stay rejected", rejected)

        // After calibration, resolveSmsArrivalTimestamp snaps a 1h-skewed SMSC timestamp to calibrated server time.
        val resolvedArrival = ServerClock.resolveSmsArrivalTimestamp(
            context = context,
            smsTimestampMs = System.currentTimeMillis(),
            systemArrivalMs = System.currentTimeMillis()
        )
        assertTrue(kotlin.math.abs(resolvedArrival - (System.currentTimeMillis() + serverOffsetMs)) < 15_000L)
    }

    @Test
    fun remoteConfigPersistsAcrossRestartAndUnlocksDynamicOtpKeywords() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pubBase64 = Base64.getEncoder().encodeToString(pair.public.encoded)
        val docJson = JSONObject()
            .put("audience", context.packageName)
            .put("version", 7L)
            .put("issuedAt", System.currentTimeMillis() / 1000L)
            .put("extraOtpSenders", JSONArray().put("983000999888"))
            .put("extraUtcmsKeywords", JSONArray().put("حمل بار ویژه"))
            .toString()
        val seg = Base64.getUrlEncoder().withoutPadding().encodeToString(docJson.toByteArray())
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(seg.toByteArray(Charsets.US_ASCII)); sign()
        }
        val signedDoc = seg + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig)

        val fetched = RemoteConfigClient(pubBase64).verify(signedDoc, context.packageName, 0L)
        assertTrue(fetched is ConfigFetch.Applied)
        val applied = fetched as ConfigFetch.Applied
        UpdateStore.getInstance(context).recordConfig(applied.rawDocument, applied.snapshot.version)

        // Simulate fresh process restart: clear in-memory overlay, then restore from persisted signed document.
        SmsParser.setRemoteOverlay(emptyList(), emptyList())
        assertTrue(RemoteConfig.restoreSavedOverlay(context, pubBase64))

        assertTrue(SmsParser.isWhitelistedSender("+98983000999888") || SmsParser.isWhitelistedSender("983000999888"))
        assertNull(SmsParser.extractOtp("سامانه حمل بار ویژه کد: 543210", sender = "09120000099"))
        val extracted = SmsParser.extractOtp("سامانه حمل بار ویژه کد: 543210", sender = "+98983000999888")
        assertEquals("543210", extracted)
    }

    @Test
    fun failedDriverGsmFallbackDoesNotOverwriteRoomWithHttpError() = runBlocking {
        shadowOf(context).grantPermissions(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)
        val failingHttp = OkHttpClient.Builder().addInterceptor { throw IOException("Blackholed data") }.build()
        val cfg = ForwardConfig(
            isMasterEnabled = true,
            userConsentGiven = true,
            setupCompleted = true,
            primarySmsRelayEnabled = true,
            enableSmsFallback = true,
            driverPhone = "09120000001",
            authHeaderValue = "a".repeat(64),
            forwarderSecret = "a".repeat(64),
            endpointUrl = "http://87.107.160.251/api/v1/otp/sms-forwarder/09120000001",
            fallbackServerPhoneNumber = "",
            hubIrancellPhoneNumber = ""
        )
        db.forwardConfigDao().insertOrUpdate(cfg)

        val outboxKey = SecureStorageManager.getInstance(context).getOrCreateOutboxKey()
        val enc = AesEncryptionUtils.encrypt("کد ورود به سامانه بارپرو: 789123", outboxKey)
        val logId = db.forwardLogDao().insertLog(
            ForwardLog(
                sender = "20007777",
                messageBody = "کد ورود به سامانه بارپرو: ***",
                recipientPhone = "09120000001",
                receivedTimestamp = System.currentTimeMillis(),
                smsType = SmsType.UTCMS_OTP,
                encryptedBody = "v2:${enc.iv}:${enc.ciphertext}"
            )
        )

        val repo = SmsForwardRepository(db, client = SmsForwarderClient(failingHttp, appContext = context), appContext = context)
        val result = repo.transmitPendingLog(logId)
        val saved = db.forwardLogDao().getLogById(logId)!!

        if (BuildConfig.APP_ROLE == "DRIVER") {
            assertFalse(result.isSuccess)
            // With zero hub destinations, dispatchGsmRelay runs 0 modem attempts and returns "ارسال GSM انجام نشد",
            // and must NOT overwrite Room with HTTP "Blackholed data" + retryCount = 1.
            assertEquals("ارسال GSM انجام نشد", result.errorMessage)
            assertEquals(0, saved.retryCount)
            assertNotEquals("Blackholed data", saved.errorMessage)
        }
    }

    @Test
    fun hubInstallContractSupportsSingleSimAndClearsStaleDriverPhone() {
        val server = "http://87.107.160.251/".toHttpUrl()
        val id = "hub_" + "a".repeat(32)
        val doc = JSONObject()
            .put("protocol", "barpro-hub-v1")
            .put("device_id", id)
            .put("endpoint_url", "http://87.107.160.251/api/v1/otp/sms-forwarder")
            .put("webhook_token", "HUB1_" + "b".repeat(16) + "_" + "c".repeat(64))
            .put("hub_phone_mci", "09120000001")
            .put("hub_phone_irancell", "")
        val creds = HubInstallContract.parseCredentials(doc.toString(), server, id)
        assertEquals("09120000001", creds.hubMci)
        assertEquals("", creds.hubIrancell)

        val applied = creds.applyTo(ForwardConfig(driverPhone = "09129999999", driverId = "DRV-OLD"))
        assertEquals("", applied.driverPhone)
        assertEquals("", applied.driverId)

        assertThrows(IllegalArgumentException::class.java) {
            HubInstallContract.parseCredentials(
                doc.put("hub_phone_mci", "").put("hub_phone_irancell", "").toString(),
                server,
                id
            )
        }
    }

    @Test
    fun aesKeyDerivationRespectsDifferentSaltsAndDecryptionRoundTrips() {
        val cipher1 = AesEncryptionUtils.encrypt("کد تایید: 12345", "secret-A", salt = "salt-1".toByteArray())
        val cipher2 = AesEncryptionUtils.encrypt("کد تایید: 12345", "secret-A", salt = "salt-2".toByteArray())
        assertEquals("کد تایید: 12345", AesEncryptionUtils.decrypt(cipher1.iv, cipher1.ciphertext, "secret-A", salt = "salt-1".toByteArray()))
        assertEquals("کد تایید: 12345", AesEncryptionUtils.decrypt(cipher2.iv, cipher2.ciphertext, "secret-A", salt = "salt-2".toByteArray()))
    }
}
