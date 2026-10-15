package com.gymcoach.core.coach.wire

import com.gymcoach.core.coach.CoachFixtures
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §6.3: splits every fixture at every BYTE offset (and a sample of three-way splits), decodes the parts with a
 * UTF-8 CharsetDecoder that carries partial code points across reads (what SseBodyReader does) and asserts that the
 * event list is identical to the one-shot parse.
 */
class SseParserFuzzTest {
    private fun decoder(): CharsetDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)

    /** Feeds [parts] of raw bytes through one decoder, carrying undecoded tail bytes, like SseBodyReader. */
    private fun parseChunks(parts: List<ByteArray>): List<SseEvent> {
        val parser = SseParser()
        val dec = decoder()
        val out = ArrayList<SseEvent>()
        var carry = ByteArray(0)
        for (part in parts) {
            val input = ByteBuffer.wrap(carry + part)
            val chars = CharBuffer.allocate(input.remaining() + 8)
            dec.decode(input, chars, false)
            carry = ByteArray(input.remaining()).also { input.get(it) }
            chars.flip()
            if (chars.isNotEmpty()) out.addAll(parser.feed(chars.toString()))
        }
        val tail = CharBuffer.allocate(carry.size + 8)
        dec.decode(ByteBuffer.wrap(carry), tail, true)
        dec.flush(tail)
        tail.flip()
        if (tail.isNotEmpty()) out.addAll(parser.feed(tail.toString()))
        out.addAll(parser.flush())
        return out
    }

    @Test
    fun everyFixtureSplitAtEveryByteOffsetParsesIdentically() {
        for (name in SseFixtureNames.ALL) {
            val bytes = CoachFixtures.sse(name).toByteArray(Charsets.UTF_8)
            val expected = parseChunks(listOf(bytes))
            for (k in 0..bytes.size) {
                val got = parseChunks(listOf(bytes.copyOfRange(0, k), bytes.copyOfRange(k, bytes.size)))
                assertEquals(expected, got, "$name split at byte $k")
            }
        }
    }

    @Test
    fun threeWaySplitsAndSingleByteFeedingParseIdentically() {
        for (name in SseFixtureNames.ALL) {
            val bytes = CoachFixtures.sse(name).toByteArray(Charsets.UTF_8)
            val expected = parseChunks(listOf(bytes))
            val step = maxOf(1, bytes.size / 23)
            var i = 0
            while (i <= bytes.size) {
                var j = i
                while (j <= bytes.size) {
                    val got = parseChunks(listOf(bytes.copyOfRange(0, i), bytes.copyOfRange(i, j), bytes.copyOfRange(j, bytes.size)))
                    assertEquals(expected, got, "$name split at $i/$j")
                    j += step
                }
                i += step
            }
            assertEquals(expected, parseChunks(bytes.map { byteArrayOf(it) }), "$name fed byte by byte")
        }
    }

    @Test
    fun everyCharOffsetSplitOfDecodedTextParsesIdentically() {
        for (name in SseFixtureNames.ALL) {
            val text = CoachFixtures.sse(name)
            val one = SseParser().let { it.feed(text) + it.flush() }
            for (k in 0..text.length) {
                val p = SseParser()
                val got = p.feed(text.substring(0, k)) + p.feed(text.substring(k)) + p.flush()
                assertEquals(one, got, "$name split at char $k")
            }
        }
    }
}
