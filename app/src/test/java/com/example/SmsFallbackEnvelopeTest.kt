package com.example

import com.example.utils.SmsFallbackEnvelope
import org.junit.Assert.*
import org.junit.Test

class SmsFallbackEnvelopeTest {
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
}
