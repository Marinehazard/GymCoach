// core/src/main/kotlin/com/gymcoach/core/util/Hashing.kt
package com.gymcoach.core.util

import java.security.MessageDigest

object Hashing {
    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
