package sh.soml.kotlin

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

/**
A SOML value: a string, an int, a float, a bool, null, a date, an instant, a duration, an array, or an object.

Equality follows the spec: an int and a float are never equal, so `3` and `3.0` differ, `-0.0` equals `0.0`, the order of an object's members does not matter, dates compare their year, month, and day, and two instants are equal when they are the same point in time.

`toString()` gives the value as canonical form writes it, such as `'text'`, `1.5`, or `1h30m`.

A value is valid by construction: a string cannot hold U+000D or a lone surrogate, a float cannot be NaN, dates must be valid Gregorian components in years 0001 to 9999, and an instant cannot be outside the years 0001 to 9999.

```kotlin
when (val value = document["port"]) {
	is SomlInt -> value.value
	is SomlString -> value.value.toLong()
	else -> 8080
}
```
*/
@Serializable(with = AnySomlValueSerializer::class)
public sealed interface SomlValue

/**
A string. It cannot hold U+000D, which SOML does not represent, or a lone surrogate, which is not a Unicode scalar value.
*/
@Serializable(with = SomlStringSerializer::class)
public class SomlString private constructor(
	/**
	The text of the string.
	*/
	public val value: String,
	@Suppress("unused") isTrusted: Boolean,
) : SomlValue {
	/**
	@throws IllegalArgumentException When `value` holds U+000D or a lone surrogate.
	*/
	public constructor(value: String) : this(value.also(::checkString), isTrusted = true)

	override fun equals(other: Any?): Boolean = other is SomlString && other.value == value

	override fun hashCode(): Int = value.hashCode()

	override fun toString(): String = buildString { appendString(value) }

	internal companion object {
		/**
		A string the parser has already checked.
		*/
		fun trusted(value: String): SomlString = SomlString(value, isTrusted = true)
	}
}

/**
An int, which is a 64-bit signed integer.
*/
@Serializable(with = SomlIntSerializer::class)
public class SomlInt(public val value: Long) : SomlValue {
	override fun equals(other: Any?): Boolean = other is SomlInt && other.value == value

	override fun hashCode(): Int = value.hashCode()

	override fun toString(): String = value.toString()
}

/**
A float, which is an IEEE 754 binary64 value or an infinity. NaN is not a SOML value, and `-0.0` is stored as `0.0`, because the sign of a zero is not part of the format.
*/
@Serializable(with = SomlFloatSerializer::class)
public class SomlFloat(value: Double) : SomlValue {
	/**
	The value, never NaN and never `-0.0`.
	*/
	public val value: Double

	init {
		require(!value.isNaN()) { "NaN is not a SOML value. Use SomlNull for a missing value" }
		// Adding a positive zero turns `-0.0` into `0.0` and changes no other value.
		this.value = value + 0.0
	}

	override fun equals(other: Any?): Boolean = other is SomlFloat && other.value == value

	override fun hashCode(): Int = value.hashCode()

	override fun toString(): String = formatFloat(value)
}

/**
A bool.
*/
@Serializable(with = SomlBoolSerializer::class)
public enum class SomlBool(public val value: Boolean) : SomlValue {
	False(false),
	True(true);

	override fun toString(): String = value.toString()

	public companion object {
		/**
		The bool for `value`.
		*/
		public fun of(value: Boolean): SomlBool = if (value) True else False
	}
}

/**
Null, which says that a member is present with no value, unlike a member that is left out.
*/
@Serializable(with = SomlNullSerializer::class)
public data object SomlNull : SomlValue {
	override fun toString(): String = "null"
}

/**
A calendar date in the proleptic Gregorian calendar, with no timezone or time of day. Components are preserved exactly, without conversion through an instant.

@throws IllegalArgumentException When the year is outside 0001 to 9999, or the month or day does not exist.
*/
@Serializable(with = SomlDateSerializer::class)
public class SomlDate(
	/**
	The year, from 1 to 9999.
	*/
	public val year: Int,
	/**
	The month, from 1 to 12.
	*/
	public val month: Int,
	/**
	The day, from 1 to the last day of the month.
	*/
	public val day: Int,
) : SomlValue {
	init {
		val problem = calendarError(year, month, day)
		require(problem == null) { "Invalid date $this: $problem" }
	}

	override fun equals(other: Any?): Boolean = other is SomlDate && other.year == year && other.month == month && other.day == day

	override fun hashCode(): Int = (year * 31 + month) * 31 + day

	override fun toString(): String = buildString { appendDate(year, month, day) }

	internal companion object {
		/**
		A date alone is a calendar value, not an instant, and is accepted only when its components exist. Returns the first invalid component, shared with the calendar part of an instant.
		*/
		fun calendarError(year: Int, month: Int, day: Int): String? {
			if (year !in 1..9999) {
				return "the year must be 0001 to 9999"
			}
			if (month !in 1..12) {
				return "the month must be 01 to 12"
			}
			val lastDay = daysInMonth(year, month)
			return if (day !in 1..lastDay) "the day must be 01 to $lastDay in that month" else null
		}

		/**
		The length of a month from 1 to 12 in the proleptic Gregorian calendar.
		*/
		fun daysInMonth(year: Int, month: Int): Int {
			if (month == 2) {
				val isLeapYear = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
				return if (isLeapYear) 29 else 28
			}
			return if (month == 4 || month == 6 || month == 9 || month == 11) 30 else 31
		}
	}
}

/**
An instant: a point in time with nanosecond precision, from `0001-01-01T00:00:00Z` to `9999-12-31T23:59:59.999999999Z`.

The offset a document writes an instant with is not part of its value, so `2026-09-19T16:00:00+02:00` and `2026-09-19T14:00:00Z` are the same instant.
*/
@Serializable(with = SomlInstantSerializer::class)
public class SomlInstant(public val value: Instant) : SomlValue {
	init {
		// Note: Not using a ClosedRange because it adds a generic range without a consistent constructor benefit.
		require(value >= MIN && value <= MAX) { "The instant $value is outside the years 0001 to 9999" }
	}

	override fun equals(other: Any?): Boolean = other is SomlInstant && other.value == value

	override fun hashCode(): Int = value.hashCode()

	override fun toString(): String = buildString { appendInstant(value) }

	public companion object {
		/**
		The earliest instant: `0001-01-01T00:00:00Z`.
		*/
		public val MIN: Instant = Instant.fromEpochSeconds(-62_135_596_800L)

		/**
		The latest instant: `9999-12-31T23:59:59.999999999Z`.
		*/
		public val MAX: Instant = Instant.fromEpochSeconds(253_402_300_799L, 999_999_999)
	}
}

/**
A duration: a signed count of nanoseconds in the 64-bit range, about 292 years either way.

It is a count of nanoseconds rather than a `kotlin.time.Duration`, because a Kotlin duration holds nanoseconds exactly only up to about 146 years.
*/
@Serializable(with = SomlDurationSerializer::class)
public class SomlDuration(public val nanoseconds: Long) : SomlValue {
	/**
	The duration as a `kotlin.time.Duration`.

	@throws IllegalStateException When the duration is longer than about 146 years and not a whole number of milliseconds, because a Kotlin duration of that length holds only milliseconds.
	*/
	public fun toDuration(): Duration {
		val duration = nanoseconds.nanoseconds
		check(duration.toExactNanosecondsOrNull() == nanoseconds) { "The duration ${this} is too long for a kotlin.time.Duration to hold exactly" }
		return duration
	}

	override fun equals(other: Any?): Boolean = other is SomlDuration && other.nanoseconds == nanoseconds

	override fun hashCode(): Int = nanoseconds.hashCode()

	override fun toString(): String = buildString { appendDuration(nanoseconds) }

	public companion object {
		/**
		The duration of `value`.

		@throws IllegalArgumentException When `value` is infinite or does not fit in 64 bits of nanoseconds.
		*/
		public fun of(value: Duration): SomlDuration = SomlDuration(value.toExactNanoseconds())
	}
}

/**
The nanoseconds of a finite duration, or null when it is infinite or does not fit in a `Long`. A long duration holds milliseconds, so this is exact for every duration.
*/
private fun Duration.toExactNanosecondsOrNull(): Long? {
	// Note: Not using toComponents() because the equivalent exactness check measured slower without reducing JVM allocation.
	if (isInfinite()) {
		return null
	}

	// Under 2^62 nanoseconds, a duration holds nanoseconds and the conversion is exact. Above, it holds milliseconds, and the conversion clamps at the `Long` range, which a clamped value no longer equals.
	val nanoseconds = inWholeNanoseconds

	if (nanoseconds == Long.MAX_VALUE || nanoseconds == Long.MIN_VALUE) {
		val milliseconds = inWholeMilliseconds

		if (milliseconds > Long.MAX_VALUE / 1_000_000 || milliseconds < Long.MIN_VALUE / 1_000_000) {
			return null
		}
	}

	return nanoseconds
}

private fun Duration.toExactNanoseconds(): Long = requireNotNull(toExactNanosecondsOrNull()) { "The duration $this is outside the 64-bit range of nanoseconds" }

/**
An array: an ordered list of values, which may have different types.
*/
@Serializable(with = SomlArraySerializer::class)
public class SomlArray private constructor(
	private val items: List<SomlValue>,
	@Suppress("unused") isTrusted: Boolean,
) : SomlValue, List<SomlValue> by items {
	/**
	An array of a copy of `items`.
	*/
	public constructor(items: List<SomlValue>) : this(items.toList(), isTrusted = true)

	/**
	An array of `items`.
	*/
	public constructor(vararg items: SomlValue) : this(items.toList(), isTrusted = true)

	override fun equals(other: Any?): Boolean = this === other || (other is SomlArray && other.items == items)

	override fun hashCode(): Int = items.hashCode()

	override fun toString(): String = Writer.writeValue(this)

	internal companion object {
		/**
		An array of a list that nothing else holds.
		*/
		fun trusted(items: List<SomlValue>): SomlArray = SomlArray(items, isTrusted = true)

		val Empty: SomlArray = SomlArray(emptyList(), isTrusted = true)
	}
}

/**
An object: a map from keys to values, whose member order is not significant.

The members keep the order they were written or added in, which `Soml.encodeToString` writes and canonical form sorts. Equality and hash codes ignore that order.
*/
@Serializable(with = SomlObjectSerializer::class)
public class SomlObject private constructor(
	private val members: Map<String, SomlValue>,
	@Suppress("unused") isTrusted: Boolean,
) : SomlValue, Map<String, SomlValue> by members {
	/**
	An object of a copy of `members`, in their iteration order.

	@throws IllegalArgumentException When a key holds U+000D or a lone surrogate.
	*/
	public constructor(members: Map<String, SomlValue>) : this(
		// Note: Not using mapValues() because its backing map allocates substantially more memory for larger object copies.
		buildMap(members.size) {
			for ((key, value) in members) {
				checkString(key)
				put(key, value)
			}
		},
		isTrusted = true,
	)

	/**
	An object of `members`, in order.

	@throws IllegalArgumentException When two members have the same key, or a key holds U+000D or a lone surrogate.
	*/
	public constructor(vararg members: Pair<String, SomlValue>) : this(
		buildMap(members.size) {
			for ((key, value) in members) {
				checkString(key)
				require(put(key, value) == null) { "Duplicate key ${describeKey(key)}" }
			}
		},
		isTrusted = true,
	)

	override fun equals(other: Any?): Boolean = this === other || (other is SomlObject && other.members == members)

	override fun hashCode(): Int = members.hashCode()

	override fun toString(): String = Writer.writeValue(this)

	internal companion object {
		/**
		An object of a map that nothing else holds, with keys that are already checked.
		*/
		fun trusted(members: Map<String, SomlValue>): SomlObject = SomlObject(members, isTrusted = true)

		val Empty: SomlObject = SomlObject(emptyMap(), isTrusted = true)
	}
}

/**
Throws when `value` holds U+000D, which SOML does not represent, or a lone surrogate, which is not a Unicode scalar value.
*/
internal fun checkString(value: String) {
	val problem = findUnrepresentable(value) ?: return
	throw IllegalArgumentException(problem)
}

/**
What makes `value` not representable in SOML, or null when it is representable.
*/
internal fun findUnrepresentable(value: String): String? {
	var index = 0

	while (index < value.length) {
		val character = value[index]

		if (character == '\r') {
			return "A string cannot hold a carriage return (U+000D), which SOML does not represent"
		}

		if (character.isSurrogate()) {
			if (character.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()) {
				index += 2
				continue
			}

			return "A string cannot hold a lone surrogate (${formatCodePoint(character.code)}), which is not a Unicode scalar value"
		}

		index++
	}

	return null
}
