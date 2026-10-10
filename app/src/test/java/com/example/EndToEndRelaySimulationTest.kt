package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.network.BarProContract
import com.example.network.SmsForwarderClient
import com.example.otp.OtpExtractor
import com.example.utils.CarrierDetector
import com.example.utils.MobileCarrier
import com.example.utils.SmsFallbackEnvelope
import com.example.utils.SmsParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Complete End-to-End simulation of the Two-Flavor Driver & Hub SMS Relay topology.
 * Simulates the lifecycle from UTCMS SMS receipt on Driver handset to Hub HMAC verification
 * and BarPro API Gateway URL resolution.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EndToEndRelaySimulationTest {

    private val webhookToken = "secret-otp-token-2026-production"
    private val hubMciNumber = "09129998877"
    private val hubIrancellNumber = "09359998877"

    @Test
    fun `simulate complete Driver to Hub relay lifecycle with Persian digits`() {
        val driverPhone = "09123456789" // MCI driver
        val rawUtcMsSms = "کد ورود به سامانه بارپرو (UTCMS): ۷۹۳۴۵"
        val timestamp = 1728567890000L

        // 1. DRIVER STAGE: Intercept & Extract OTP
        assertTrue(SmsParser.isUtcmsSms("7777000982", rawUtcMsSms))
        val extractedOtp = OtpExtractor.extractOtp(rawUtcMsSms)
        assertEquals("79345", extractedOtp)

        // 2. DRIVER STAGE: Reconcile Hub numbers & Carrier Route
        val hubNumbers = CarrierDetector.resolveHubNumbers(
            buildDefaultMci = "",
            buildDefaultIrancell = "",
            configuredMci = hubMciNumber,
            configuredIrancell = hubIrancellNumber
        )
        assertTrue(hubNumbers.isDualSim)

        val route = CarrierDetector.resolveRoute(
            driverPhone = driverPhone,
            hubMciNumber = hubNumbers.mci,
            hubIrancellNumber = hubNumbers.irancell
        )
        // Since driver is 0912 (MCI), primary destination must be Hub MCI
        assertEquals(MobileCarrier.MCI, route.carrier)
        assertEquals(hubMciNumber, route.primaryNumber)
        assertEquals(hubIrancellNumber, route.failoverNumber)

        // 3. DRIVER STAGE: Encode Signed ASCII Envelope
        val envelope = SmsFallbackEnvelope.encode(
            phone = driverPhone,
            timestamp = timestamp,
            code = extractedOtp!!,
            secret = webhookToken
        )
        assertTrue(envelope.startsWith("BP1#$driverPhone#$timestamp#79345#"))
        assertEquals(5, envelope.split("#").size)
        assertTrue("Envelope must be single standard GSM 7-bit SMS length", envelope.length <= 160)

        // 4. GSM MODEM TRANSMISSION SIMULATION (Driver -> Hub)
        val receivedAtHubSmsText = envelope

        // 5. HUB STAGE: Receive, Parse & Verify Envelope
        val parsedEnvelope = SmsFallbackEnvelope.parse(receivedAtHubSmsText)
        assertNotNull(parsedEnvelope)
        assertEquals(driverPhone, parsedEnvelope!!.phone)
        assertEquals(timestamp, parsedEnvelope.timestamp)
        assertEquals("79345", parsedEnvelope.code)

        val isHmacValid = SmsFallbackEnvelope.verify(parsedEnvelope, webhookToken)
        assertTrue("Hub must verify authentic HMAC signature in constant time", isHmacValid)

        // 6. HUB STAGE: Gateway URL & Payload Assembly
        val client = SmsForwarderClient()
        val gatewayUrl = client.resolveGatewayUrl("http://192.168.1.100:8000")
        assertEquals("http://192.168.1.100:8000/api/v1/otp/sms-gateway", gatewayUrl)

        // 7. HUB STAGE: Verify Path & Headers against BarPro Contract
        assertTrue(BarProContract.isPathValid("/api/v1/otp/sms-gateway"))
        assertTrue(BarProContract.isPathValid("/api/v1/otp/sms-gateway/$driverPhone"))
        assertTrue(BarProContract.isTimestampAcceptable(timestamp, now = timestamp + 10_000L))
    }

    @Test
    fun `simulate forged envelope rejected at Hub intake boundary`() {
        val driverPhone = "09123456789"
        val forgedEnvelope = "BP1#$driverPhone#1728567890000#12345#0123456789abcdef0123456789abcdef"

        val parsed = SmsFallbackEnvelope.parse(forgedEnvelope)
        assertNotNull(parsed)

        val isVerified = SmsFallbackEnvelope.verify(parsed!!, webhookToken)
        assertFalse("Forged envelope must be rejected by Hub verify()", isVerified)
    }

    @Test
    fun `simulate operator field swap auto-correction during routing`() {
        val mciDriver = "09120000001"
        val irancellDriver = "09350000001"

        // Operator mistakenly entered Irancell number in MCI slot and MCI number in Irancell slot
        val swappedRouteMci = CarrierDetector.resolveRoute(
            driverPhone = mciDriver,
            hubMciNumber = hubIrancellNumber, // swapped!
            hubIrancellNumber = hubMciNumber  // swapped!
        )
        // Auto-correction must detect prefixes and route MCI driver to MCI Hub
        assertEquals(hubMciNumber, swappedRouteMci.primaryNumber)
        assertEquals(hubIrancellNumber, swappedRouteMci.failoverNumber)

        val swappedRouteIrancell = CarrierDetector.resolveRoute(
            driverPhone = irancellDriver,
            hubMciNumber = hubIrancellNumber,
            hubIrancellNumber = hubMciNumber
        )
        assertEquals(hubIrancellNumber, swappedRouteIrancell.primaryNumber)
        assertEquals(hubMciNumber, swappedRouteIrancell.failoverNumber)
    }

    @Test
    fun `simulate driver phone blank fail-closed behavior`() {
        val blankDriverPhone = "   "
        val isPhoneValid = SmsParser.normalizePhoneNumber(blankDriverPhone).matches(Regex("09[0-9]{9}"))
        assertFalse(isPhoneValid)

        // Raw sender from gateway shortcode
        val gatewaySender = "7777000982"
        val isSenderValidPhone = SmsParser.normalizePhoneNumber(gatewaySender).matches(Regex("09[0-9]{9}"))
        assertFalse("Gateway shortcode must never be accepted as valid driver mobile", isSenderValidPhone)
    }
}
