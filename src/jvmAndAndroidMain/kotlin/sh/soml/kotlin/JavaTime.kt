package sh.soml.kotlin

import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule

internal actual val defaultSerializersModule: SerializersModule = SerializersModule {
	contextual(java.time.LocalDate::class, JavaDateSerializer)
	contextual(java.time.Instant::class, JavaInstantSerializer)
	contextual(java.time.Duration::class, JavaDurationSerializer)
}

/**
A date of a `java.time.LocalDate`, preserving its Gregorian components.

@throws IllegalArgumentException When the year is outside 0001 to 9999.
*/
public fun SomlDate(value: java.time.LocalDate): SomlDate = SomlDate(value.year, value.monthValue, value.dayOfMonth)

/**
The date as a `java.time.LocalDate`, with the same year, month, and day.
*/
public fun SomlDate.toJavaLocalDate(): java.time.LocalDate = java.time.LocalDate.of(year, month, day)

/**
An instant of a `java.time.Instant`.

@throws IllegalArgumentException When `value` is outside the years 0001 to 9999 in UTC.
*/
public fun SomlInstant(value: java.time.Instant): SomlInstant = SomlInstant(value.toKotlinInstant())

/**
The instant as a `java.time.Instant`.
*/
public fun SomlInstant.toJavaInstant(): java.time.Instant = value.toJavaInstant()

/**
The duration of `value`.

@throws IllegalArgumentException When `value` does not fit in 64 bits of nanoseconds.
*/
public fun SomlDuration.Companion.of(value: java.time.Duration): SomlDuration = SomlDuration(
	try {
		// Note: Not using toKotlinDuration().inWholeNanoseconds because that conversion can round valid nanoseconds or saturate instead of reporting overflow.
		// Note: Not using Duration.dividedBy(Duration) because it uses BigDecimal arithmetic instead of this exact primitive conversion.
		// Note: Not using Duration.toNanos() because Android overflows its intermediate seconds product for valid durations near Long.MIN_VALUE.
		// Android runtimes overflow inside Duration.toNanos() for Long.MIN_VALUE. Move one negative second into the nanos so the intermediate product fits whenever the total does.
		val seconds = value.seconds
		val adjustment = if (seconds < 0) 1L else 0L
		Math.addExact(Math.multiplyExact(seconds + adjustment, 1_000_000_000L), value.nano - adjustment * 1_000_000_000L)
	} catch (_: ArithmeticException) {
		throw IllegalArgumentException("The duration $value is outside the 64-bit range of nanoseconds")
	},
)

/**
The duration as a `java.time.Duration`, which holds every value exactly.
*/
public fun SomlDuration.toJavaDuration(): java.time.Duration = java.time.Duration.ofNanos(nanoseconds)

/**
A `java.time.LocalDate` as a native SOML date, and as YYYY-MM-DD text in other formats. Years must be 0001 to 9999, in every format. It is contextual in every `Soml` instance, so a property only needs `@Contextual`. Use it directly with `@Serializable(with = JavaDateSerializer::class)`.
*/
public object JavaDateSerializer : KSerializer<java.time.LocalDate> {
	override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.LocalDate", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: java.time.LocalDate) {
		val date = SomlDate(value)
		if (encoder !is SomlEncoder) {
			encoder.encodeString(date.toString())
			return
		}
		encoder.encodeSomlValue(date)
	}

	override fun deserialize(decoder: Decoder): java.time.LocalDate {
		if (decoder !is SomlDecoder) {
			return SomlDate(java.time.LocalDate.parse(decoder.decodeString())).toJavaLocalDate()
		}
		val value = decoder.decodeSomlValue()
		return (value as? SomlDate ?: throw SomlException("Expected a date, but found ${describeValue(value)}")).toJavaLocalDate()
	}
}

/**
A `java.time.Instant` as a SOML instant, and as its ISO 8601 text in other formats. It is contextual in every `Soml` instance, so a property only needs `@Contextual`. Use it directly with `@Serializable(with = JavaInstantSerializer::class)`.
*/
public object JavaInstantSerializer : KSerializer<java.time.Instant> {
	override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: java.time.Instant) {
		if (encoder !is SomlEncoder) {
			encoder.encodeString(value.toString())
			return
		}

		encoder.encodeSomlValue(SomlInstant(value))
	}

	override fun deserialize(decoder: Decoder): java.time.Instant {
		if (decoder !is SomlDecoder) {
			return java.time.Instant.parse(decoder.decodeString())
		}

		val value = decoder.decodeSomlValue()
		return (value as? SomlInstant ?: throw SomlException("Expected an instant, but found ${describeValue(value)}")).toJavaInstant()
	}
}

/**
A `java.time.Duration` as a SOML duration, and as its ISO 8601 text in other formats. It is contextual in every `Soml` instance, so a property only needs `@Contextual`. Use it directly with `@Serializable(with = JavaDurationSerializer::class)`.
*/
public object JavaDurationSerializer : KSerializer<java.time.Duration> {
	override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.Duration", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: java.time.Duration) {
		if (encoder !is SomlEncoder) {
			encoder.encodeString(value.toString())
			return
		}

		encoder.encodeSomlValue(SomlDuration.of(value))
	}

	override fun deserialize(decoder: Decoder): java.time.Duration {
		if (decoder !is SomlDecoder) {
			return java.time.Duration.parse(decoder.decodeString())
		}

		val value = decoder.decodeSomlValue()
		return (value as? SomlDuration ?: throw SomlException("Expected a duration, but found ${describeValue(value)}")).toJavaDuration()
	}
}
