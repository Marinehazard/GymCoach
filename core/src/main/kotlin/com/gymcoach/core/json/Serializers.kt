// core/src/main/kotlin/com/gymcoach/core/json/Serializers.kt
package com.gymcoach.core.json

import com.gymcoach.core.model.Band
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** ISO-8601 calendar date, e.g. "2026-09-05". */
object LocalDateIsoSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) { encoder.encodeString(value.toString()) }
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

/** ISO-8601 instant in UTC, e.g. "2026-09-05T06:40:00Z". */
object InstantIsoSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) { encoder.encodeString(value.toString()) }
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/** R4 schemas encode integer ranges as the JSON array [lo, hi] ("intRange"). */
object BandSerializer : KSerializer<Band> {
    private val delegate: KSerializer<List<Int>> = ListSerializer(Int.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Band) { delegate.serialize(encoder, listOf(value.lo, value.hi)) }
    override fun deserialize(decoder: Decoder): Band {
        val list = delegate.deserialize(decoder)
        require(list.size == 2) { "Band must have exactly two integers, got $list" }
        return Band(list[0], list[1])
    }
}
