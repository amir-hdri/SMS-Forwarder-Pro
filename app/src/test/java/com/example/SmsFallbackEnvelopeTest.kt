package com.example

import com.example.utils.SmsFallbackEnvelope
import org.junit.Assert.*
import org.junit.Test

class SmsFallbackEnvelopeTest {
    @Test fun driverScopedKeyHasStableVectorAndAcceptsBothRelayGenerations() {
        val shared = "test-webhook-token"
        val scoped = SmsFallbackEnvelope.deriveDriverKey(shared, "+989120000001")
        assertEquals("1ab25da5190e1ff5d5b8d4650deae8c4ee3a5f28f088bf05d6a72e403bb705a8", scoped)
        for (key in listOf(shared, scoped)) {
            val parsed = SmsFallbackEnvelope.parse(SmsFallbackEnvelope.encode("09120000001", 1800000000000, "54321", key))!!
            assertTrue(SmsFallbackEnvelope.verifyForHub(parsed, shared))
            assertFalse(SmsFallbackEnvelope.verifyForHub(parsed.copy(phone = "09120000002"), shared))
        }
        assertFalse(SmsFallbackEnvelope.verifyForHub(
            SmsFallbackEnvelope.parse(SmsFallbackEnvelope.encode("09120000001", 1800000000000, "54321", scoped))!!, ""))
    }

    @Test fun authenticatedHealthFitsOneSmsAndIsNeverAnOtp() {
        val encoded = SmsFallbackEnvelope.encode("09120000001", 1800000000000, "HEALTH_CHECK", "test-key")
        assertTrue(encoded.length < 160)
        val parsed = SmsFallbackEnvelope.parse(encoded)!!
        assertEquals("HEALTH_CHECK", parsed.code)
        assertTrue(SmsFallbackEnvelope.verify(parsed, "test-key"))
        assertNull(com.example.utils.SmsParser.extractOtp(encoded))
    }

    @Test fun compactEnvelopeMatchesServerHmacVector() {
        val encoded = SmsFallbackEnvelope.encode("+989120000001", 1800000000000, "12345", "test-webhook-token")
        assertEquals("BP1#09120000001#1800000000000#12345#5cbfbbba7709a8a98c9e1eb96666416c", encoded)
        assertTrue(encoded.length < 160)
        assertTrue(encoded.all { it.code < 128 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun trackingNumberCannotBeRelayedAsOtp() {
        SmsFallbackEnvelope.encode("09120000001", 1800000000000, "123456789", "test-webhook-token")
    }

    @Test fun testProbeCodeIsAllowedInEnvelope() {
        val encoded = SmsFallbackEnvelope.encode("09120000001", 1800000000000, "TEST", "test-webhook-token")
        assertTrue(encoded.startsWith("BP1#09120000001#1800000000000#TEST#"))
    }

    @Test fun testParseAndVerifyValidEnvelope() {
        val encoded = SmsFallbackEnvelope.encode("09120000001", 1800000000000, "54321", "my-secret-key")
        val parsed = SmsFallbackEnvelope.parse(encoded)
        assertNotNull(parsed)
        assertEquals("09120000001", parsed?.phone)
        assertEquals(1800000000000L, parsed?.timestamp)
        assertEquals("54321", parsed?.code)
        assertTrue(SmsFallbackEnvelope.verify(parsed!!, "my-secret-key"))
        assertFalse(SmsFallbackEnvelope.verify(parsed, "wrong-secret"))
    }

    @Test fun testParseInvalidEnvelopeReturnsNull() {
        assertNull(SmsFallbackEnvelope.parse("Hello world"))
        assertNull(SmsFallbackEnvelope.parse("BP1#09120000001#abc#12345#sig"))
        assertNull(SmsFallbackEnvelope.parse("BP1#123#1800000000000#12345#sig"))
    }

    @Test fun verifyRejectsBlankSecretInsteadOfTrustingTheEnvelope() {
        val encoded = SmsFallbackEnvelope.encode("09120000001", 1800000000000, "54321", "my-secret-key")
        val parsed = SmsFallbackEnvelope.parse(encoded)!!
        assertFalse(SmsFallbackEnvelope.verify(parsed, ""))
        assertFalse(SmsFallbackEnvelope.verify(parsed, "   "))
    }

    @Test fun verifyIsCaseInsensitiveOnTheHexSignature() {
        val encoded = SmsFallbackEnvelope.encode("09120000001", 1800000000000, "54321", "my-secret-key")
        // A handset or gateway that upper-cases the SMS body must still verify, because parse()
        // normalises the hex signature to lower case before comparison.
        val upperCased = SmsFallbackEnvelope.parse(encoded.uppercase())
        assertNotNull(upperCased)
        assertTrue(SmsFallbackEnvelope.verify(upperCased!!, "my-secret-key"))
    }

    @Test fun verifyRejectsForgedSignatureOfCorrectLength() {
        val encoded = SmsFallbackEnvelope.encode("09120000001", 1800000000000, "54321", "my-secret-key")
        val parsed = SmsFallbackEnvelope.parse(encoded)!!
        val forged = parsed.copy(signature = "0".repeat(32))
        assertFalse(SmsFallbackEnvelope.verify(forged, "my-secret-key"))
    }

    // The comparison must not short-circuit on the first differing character, otherwise the number
    // of correct leading hex digits in a forged signature becomes observable.
    @Test fun constantTimeEqualsMatchesEqualityWithoutShortCircuiting() {
        assertTrue(SmsFallbackEnvelope.constantTimeEquals("abc123", "abc123"))
        assertFalse(SmsFallbackEnvelope.constantTimeEquals("abc123", "abc124"))
        assertFalse(SmsFallbackEnvelope.constantTimeEquals("abc123", "xbc123"))
        assertFalse(SmsFallbackEnvelope.constantTimeEquals("abc123", "abc1234"))
        assertFalse(SmsFallbackEnvelope.constantTimeEquals("abc1234", "abc123"))
        assertFalse(SmsFallbackEnvelope.constantTimeEquals("abc", ""))
        assertFalse(SmsFallbackEnvelope.constantTimeEquals("", "abc"))
        assertTrue(SmsFallbackEnvelope.constantTimeEquals("", ""))
    }
}
