package com.example.update

import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import org.json.JSONException
import org.json.JSONObject

/**
 * Detached-signature envelope for operator-published documents (remote config, update manifest).
 *
 * Wire format is a JWS-like compact serialization with exactly two segments:
 *
 *     base64url(payload_json) "." base64url(DER ECDSA signature)
 *
 * The signature covers the *literal ASCII bytes of the first segment*, so verification never
 * depends on JSON canonicalization — we verify exactly the bytes we received, and only then
 * parse them. Keys are ECDSA P-256 (SHA256withECDSA); the private half never leaves the
 * operator's offline machine.
 *
 * Every rejection path throws [InvalidSignatureException] so callers need a single catch and
 * cannot accidentally treat a malformed document as an unsigned-but-acceptable one.
 */
object SignedPayload {

    /** Raised for every rejection: bad shape, bad base64, bad key, or a signature mismatch. */
    class InvalidSignatureException(message: String, cause: Throwable? = null) :
        GeneralSecurityException(message, cause)

    private const val B64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    /** Hard ceiling so a hostile endpoint cannot feed us an unbounded body to parse. */
    const val MAX_DOCUMENT_BYTES = 64 * 1024

    /**
     * Verifies [document] against [publicKeyBase64] and returns the parsed payload.
     *
     * @param publicKeyBase64 base64 of an X.509 SubjectPublicKeyInfo EC public key.
     * @throws InvalidSignatureException if the document is malformed or the signature is invalid.
     */
    fun verify(document: String, publicKeyBase64: String): JSONObject {
        if (publicKeyBase64.isBlank()) {
            // Fail closed: no trust anchor configured means we trust nothing.
            throw InvalidSignatureException("No signing public key is configured")
        }
        val trimmed = document.trim()
        if (trimmed.isEmpty()) throw InvalidSignatureException("Empty document")
        if (trimmed.length > MAX_DOCUMENT_BYTES) {
            throw InvalidSignatureException("Document exceeds $MAX_DOCUMENT_BYTES bytes")
        }

        val separator = trimmed.indexOf('.')
        if (separator <= 0 || separator == trimmed.length - 1) {
            throw InvalidSignatureException("Document must be payload.signature")
        }
        if (trimmed.indexOf('.', separator + 1) != -1) {
            throw InvalidSignatureException("Document must contain exactly one separator")
        }

        val signingInput = trimmed.substring(0, separator)
        val signatureSegment = trimmed.substring(separator + 1)

        val signatureBytes = decodeOrThrow(signatureSegment, "signature")
        val publicKey = parsePublicKey(publicKeyBase64)

        val verified = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                // Sign over the exact transmitted bytes, not over re-serialized JSON.
                update(signingInput.toByteArray(Charsets.US_ASCII))
                verify(signatureBytes)
            }
        } catch (error: GeneralSecurityException) {
            // A malformed DER signature surfaces here; treat it as a plain rejection.
            throw InvalidSignatureException("Signature could not be verified", error)
        }
        if (!verified) throw InvalidSignatureException("Signature does not match the payload")

        val payloadBytes = decodeOrThrow(signingInput, "payload")
        return try {
            JSONObject(String(payloadBytes, Charsets.UTF_8))
        } catch (error: JSONException) {
            throw InvalidSignatureException("Signed payload is not a JSON object", error)
        }
    }

    private fun decodeOrThrow(segment: String, label: String): ByteArray = try {
        Base64.decode(segment, B64_FLAGS)
    } catch (error: IllegalArgumentException) {
        throw InvalidSignatureException("Malformed base64url in $label", error)
    }

    private fun parsePublicKey(publicKeyBase64: String): PublicKey {
        val der = try {
            // Operator-supplied keys are commonly pasted with standard base64 and newlines.
            Base64.decode(publicKeyBase64.filterNot { it.isWhitespace() }, Base64.DEFAULT)
        } catch (error: IllegalArgumentException) {
            throw InvalidSignatureException("Configured public key is not valid base64", error)
        }
        return try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        } catch (error: GeneralSecurityException) {
            throw InvalidSignatureException("Configured public key is not an EC public key", error)
        }
    }
}
