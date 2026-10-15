// core/src/main/kotlin/com/gymcoach/core/coach/wire/SseParser.kt
package com.gymcoach.core.coach.wire

/**
 * Incremental Server-Sent-Events parser (§6.3, R1 §3.6). [feed] accepts arbitrary chunk boundaries — inside a line,
 * between `\r` and `\n`, anywhere — because it holds the unterminated tail of the input across calls. Line
 * terminators: `\n`, `\r\n` and a lone `\r`. `data:` lines are joined with `\n`; `:` comments and the `id:` / `retry:`
 * fields are ignored; one leading space after the colon is removed; a blank line dispatches the pending event (only
 * when it carries at least one `data:` line). [flush] ends the stream: the held tail is processed as a final line and
 * a still-pending event is dispatched. Not thread-safe (one parser per response body).
 */
class SseParser {
    private val buffer = StringBuilder()
    private var eventName: String? = null
    private val data = StringBuilder()
    private var hasData = false
    private var atStreamStart = true

    fun feed(chunk: String): List<SseEvent> {
        if (chunk.isEmpty()) return emptyList()
        buffer.append(chunk)
        if (atStreamStart && buffer.isNotEmpty()) {
            if (buffer[0] == '﻿') buffer.deleteCharAt(0)
            if (buffer.isNotEmpty()) atStreamStart = false
        }
        return drain(endOfStream = false)
    }

    fun flush(): List<SseEvent> {
        val out = ArrayList<SseEvent>()
        out.addAll(drain(endOfStream = true))
        if (buffer.isNotEmpty()) {
            processLine(buffer.toString(), out)
            buffer.setLength(0)
        }
        dispatch(out)
        return out
    }

    private fun drain(endOfStream: Boolean): List<SseEvent> {
        val out = ArrayList<SseEvent>()
        var start = 0
        var i = 0
        val n = buffer.length
        while (i < n) {
            val ch = buffer[i]
            if (ch == '\n') {
                processLine(buffer.substring(start, i), out)
                i++
                start = i
            } else if (ch == '\r') {
                if (i + 1 < n) {
                    processLine(buffer.substring(start, i), out)
                    i += if (buffer[i + 1] == '\n') 2 else 1
                    start = i
                } else if (endOfStream) {
                    processLine(buffer.substring(start, i), out)
                    i++
                    start = i
                } else {
                    break      // a trailing \r may be the first half of \r\n: wait for the next chunk
                }
            } else {
                i++
            }
        }
        if (start > 0) buffer.delete(0, start)
        return out
    }

    private fun processLine(line: String, out: MutableList<SseEvent>) {
        if (line.isEmpty()) {
            dispatch(out)
            return
        }
        if (line[0] == ':') return
        val colon = line.indexOf(':')
        val field: String
        var value: String
        if (colon < 0) {
            field = line
            value = ""
        } else {
            field = line.substring(0, colon)
            value = line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
        }
        when (field) {
            "event" -> eventName = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            else -> Unit      // id, retry and unknown fields are ignored
        }
    }

    private fun dispatch(out: MutableList<SseEvent>) {
        if (hasData) out.add(SseEvent(eventName?.takeIf { it.isNotEmpty() }, data.toString()))
        eventName = null
        data.setLength(0)
        hasData = false
    }
}
