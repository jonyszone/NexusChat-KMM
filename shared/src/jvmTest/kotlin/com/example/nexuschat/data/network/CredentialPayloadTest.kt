package com.example.nexuschat.data.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Bounds/validation for the credential payload framing (no Android APIs). */
class CredentialPayloadTest {

    @Test
    fun encode_then_decode_round_trips() {
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = "super-secret".toByteArray()

        val decoded = CredentialPayload.decode(CredentialPayload.encode(iv, ciphertext))

        assertNotNull(decoded)
        assertContentEquals(iv, decoded.iv)
        assertContentEquals(ciphertext, decoded.ciphertext)
    }

    @Test
    fun decode_rejects_oversized_iv_length_before_allocating() {
        // Header claims a 1 GB IV; only 4 bytes exist. Must be rejected, not allocated.
        val payload = byteArrayOf(0x40, 0x00, 0x00, 0x00, 1, 2, 3)
        assertNull(CredentialPayload.decode(payload))
    }

    @Test
    fun decode_rejects_truncated_payloads() {
        assertNull(CredentialPayload.decode(ByteArray(0)))
        assertNull(CredentialPayload.decode(byteArrayOf(0, 0, 0)))
        // Valid IV length but no ciphertext byte.
        assertNull(CredentialPayload.decode(byteArrayOf(0, 0, 0, 12) + ByteArray(12)))
    }

    @Test
    fun decode_rejects_zero_length_iv() {
        val payload = CredentialPayload.encode(ByteArray(12), byteArrayOf(9))
        payload[3] = 0 // tamper the IV length to zero
        assertNull(CredentialPayload.decode(payload))
    }

    @Test
    fun encode_rejects_out_of_bounds_iv() {
        assertEquals(true, runCatching { CredentialPayload.encode(ByteArray(0), byteArrayOf(1)) }.isFailure)
        assertEquals(
            true,
            runCatching { CredentialPayload.encode(ByteArray(CredentialPayload.MAX_IV_BYTES + 1), byteArrayOf(1)) }.isFailure
        )
    }
}
