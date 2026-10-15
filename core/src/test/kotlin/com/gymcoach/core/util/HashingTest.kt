package com.gymcoach.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HashingTest {

    @Test fun sha256OfEmptyString() =
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hashing.sha256Hex(""))

    @Test fun sha256OfAbc() =
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.sha256Hex("abc"))

    @Test fun sha256IsLowercaseHexOf64Chars() {
        val h = Hashing.sha256Hex("GymCoach")
        assertEquals(64, h.length)
        assertTrue(h.all { it in '0'..'9' || it in 'a'..'f' }, h)
    }

    @Test fun sha256IsDeterministic() = assertEquals(Hashing.sha256Hex("x"), Hashing.sha256Hex("x"))

    @Test fun sha256DiffersForDifferentInput() = assertNotEquals(Hashing.sha256Hex("a"), Hashing.sha256Hex("b"))

    @Test fun sha256UsesUtf8() {
        // "é" is 2 bytes in UTF-8 (C3 A9): the hash must be that of the UTF-8 bytes, computed here from the
        // explicit byte array so that a platform-default-charset regression is caught.
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(byteArrayOf(0xC3.toByte(), 0xA9.toByte()))
            .joinToString("") { String.format("%02x", it) }
        assertEquals(expected, Hashing.sha256Hex("é"))
        assertNotEquals(Hashing.sha256Hex("e"), Hashing.sha256Hex("é"))
    }

    @Test fun sha256HighBitBytesAreTwoHexDigits() {
        // Every one of 1,000 hashes must be exactly 64 chars: bytes ≥ 0x80 must not become "ffffff.." or single digits.
        for (i in 0 until 1000) assertEquals(64, Hashing.sha256Hex("seed-$i").length)
    }
}
