package com.example.nexuschat.data.network

/**
 * Platform-free framing for an encrypted credential payload: a 4-byte
 * big-endian IV length, the IV, then the ciphertext. [decode] validates the
 * declared length *before* any buffer is allocated for it, so a tampered or
 * truncated payload can never trigger an unbounded allocation.
 */
object CredentialPayload {
    private const val LENGTH_PREFIX_BYTES = 4
    const val MIN_IV_BYTES = 1
    const val MAX_IV_BYTES = 32
    private const val MIN_CIPHERTEXT_BYTES = 1

    fun encode(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size in MIN_IV_BYTES..MAX_IV_BYTES) { "IV length out of bounds" }
        require(ciphertext.size >= MIN_CIPHERTEXT_BYTES) { "Ciphertext must not be empty" }
        val out = ByteArray(LENGTH_PREFIX_BYTES + iv.size + ciphertext.size)
        out[0] = (iv.size ushr 24).toByte()
        out[1] = (iv.size ushr 16).toByte()
        out[2] = (iv.size ushr 8).toByte()
        out[3] = iv.size.toByte()
        iv.copyInto(out, LENGTH_PREFIX_BYTES)
        ciphertext.copyInto(out, LENGTH_PREFIX_BYTES + iv.size)
        return out
    }

    /** Returns null for any malformed payload instead of throwing or allocating blindly. */
    fun decode(payload: ByteArray): Decoded? {
        if (payload.size < LENGTH_PREFIX_BYTES + MIN_IV_BYTES + MIN_CIPHERTEXT_BYTES) return null
        val ivSize = ((payload[0].toInt() and 0xFF) shl 24) or
            ((payload[1].toInt() and 0xFF) shl 16) or
            ((payload[2].toInt() and 0xFF) shl 8) or
            (payload[3].toInt() and 0xFF)
        if (ivSize < MIN_IV_BYTES || ivSize > MAX_IV_BYTES) return null
        if (payload.size < LENGTH_PREFIX_BYTES + ivSize + MIN_CIPHERTEXT_BYTES) return null
        val iv = payload.copyOfRange(LENGTH_PREFIX_BYTES, LENGTH_PREFIX_BYTES + ivSize)
        val ciphertext = payload.copyOfRange(LENGTH_PREFIX_BYTES + ivSize, payload.size)
        return Decoded(iv, ciphertext)
    }

    class Decoded(val iv: ByteArray, val ciphertext: ByteArray)
}
