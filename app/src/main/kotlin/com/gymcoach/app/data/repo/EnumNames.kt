package com.gymcoach.app.data.repo

import com.gymcoach.core.json.CoreJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Enums are stored by their `@SerialName` string (§7.2), never by `Enum.name` (which would change
 * the stored value whenever a Kotlin constant is renamed). The serial name is obtained through the
 * enum's generated serializer — no reflection.
 */
object EnumNames {
    /** SessionStatus.IN_PROGRESS -> "in_progress". */
    fun <T> name(serializer: KSerializer<T>, value: T): String =
        CoreJson.stored.encodeToJsonElement(serializer, value).jsonPrimitive.content

    /** "in_progress" -> SessionStatus.IN_PROGRESS; throws SerializationException for an unknown name. */
    fun <T> parse(serializer: KSerializer<T>, serialName: String): T =
        CoreJson.stored.decodeFromJsonElement(serializer, JsonPrimitive(serialName))

    /** Like [parse] but returns [fallback] for an unknown name (a value written by a newer build). */
    fun <T> parseOr(serializer: KSerializer<T>, serialName: String, fallback: T): T =
        try {
            parse(serializer, serialName)
        } catch (e: IllegalArgumentException) {   // SerializationException extends IllegalArgumentException
            fallback
        }
}
