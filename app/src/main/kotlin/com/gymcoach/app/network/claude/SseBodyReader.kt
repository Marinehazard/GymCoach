package com.gymcoach.app.network.claude

import com.gymcoach.core.coach.wire.SseEvent
import com.gymcoach.core.coach.wire.SseParser
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import okio.Buffer
import okio.BufferedSource

/**
 * Feeds an HTTP response body to :core's incremental [SseParser] in RAW chunks (§6.3): reads up to
 * 8 KiB at a time with `source.read(buffer, 8192)`, decodes UTF-8 with a [CharsetDecoder] that carries
 * partial code points across reads, and calls `parser.feed(text)` for every chunk; at end of body it
 * calls `parser.flush()`. It never uses `readUtf8Line()` (that strips the line terminators the parser
 * dispatches on). Blocking — call it on Dispatchers.IO; cancel by cancelling the OkHttp call (the
 * pending read then throws IOException).
 */
class SseBodyReader(private val parser: SseParser = SseParser()) {

    /** Reads until end of body; [onEvent] is called on the reading thread for each dispatched event. */
    @Throws(IOException::class)
    fun read(source: BufferedSource, onEvent: (SseEvent) -> Unit) {
        val decoder = Utf8ChunkDecoder()
        val buffer = Buffer()
        while (true) {
            val n = source.read(buffer, CHUNK_BYTES)
            if (n == -1L) break
            if (n == 0L) continue
            val text = decoder.decode(buffer.readByteArray(), endOfInput = false)
            if (text.isNotEmpty()) parser.feed(text).forEach(onEvent)
        }
        val tail = decoder.decode(ByteArray(0), endOfInput = true)
        if (tail.isNotEmpty()) parser.feed(tail).forEach(onEvent)
        parser.flush().forEach(onEvent)
    }

    private companion object {
        const val CHUNK_BYTES: Long = 8192L
    }
}

/**
 * Incremental UTF-8 → String decoder: bytes of a code point split across two network reads are held
 * back until the rest arrives. Malformed input is replaced (U+FFFD), never thrown.
 */
class Utf8ChunkDecoder {
    private val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var carry: ByteArray = ByteArray(0)

    fun decode(bytes: ByteArray, endOfInput: Boolean): String {
        val joined = if (carry.isEmpty()) bytes else carry + bytes
        val input = ByteBuffer.wrap(joined)
        // UTF-8 never yields more UTF-16 chars than input bytes; +8 leaves room for replacements/flush.
        val out = CharBuffer.allocate(joined.size + 8)
        decoder.decode(input, out, endOfInput)
        if (endOfInput) {
            decoder.flush(out)
            decoder.reset()
        }
        carry = if (input.hasRemaining()) ByteArray(input.remaining()).also { input.get(it) } else ByteArray(0)
        if (endOfInput) carry = ByteArray(0)
        out.flip()
        return out.toString()
    }
}
