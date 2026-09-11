package com.example.util

import java.security.MessageDigest

/** Stable record identifier for retry deduplication. A hash is not encryption. */
object CryptoUtils {
    fun generatePayloadHash(nip: String, timestamp: Long, jenis: String): String {
        val raw = "$nip:$timestamp:$jenis:SEKRETARIAT_DPRD_BITUNG"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
