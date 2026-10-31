package sh.soml.kotlin

import kotlin.time.Instant

/**
Writes values as SOML text: one member or item per line, tab indentation, no commas, and members in their order, or sorted in canonical form.
*/
internal class Writer private constructor(
	private val output: StringBuilder,
	private val isCanonical: Boolean,
	// The indentation of the line the value starts on, for a value that the editor inserts.
	private val baseIndentation: String,
	private val isOneLine: Boolean,
) {
	// The keys and indexes from the top to the value being written, for the path of an error.
	// Note: Not using removeLast() because it can resolve to an Android API 35 member, while this writer supports API 33.
	private val path = ArrayList<Any>()

	private fun appendIndentation(tabs: Int) {
		output.append(baseIndentation)
		output.appendTabs(tabs)
	}

	private fun checkDepth(depth: Int) {
		if (depth > MAX_DEPTH) {
			throw SomlException.atPath("Cannot encode a value nested more than $MAX_DEPTH levels deep", path)
		}
	}

	/**
	Writes `value`, whose own depth is `depth`, with the lines of a container after its first indented by `indent` tabs.
	*/
	private fun writeValue(value: SomlValue, depth: Int, indent: Int) {
		when (value) {
			is SomlArray -> {
				checkDepth(depth)

				if (value.isEmpty()) {
					output.append("[]")
					return
				}

				output.append(if (isOneLine) "[" else "[\n")

				for ((index, item) in value.withIndex()) {
					if (isOneLine) {
						if (index > 0) {
							output.append(", ")
						}
					} else {
						appendIndentation(indent + 1)
					}

					path.add(index)
					writeValue(item, depth + 1, indent + 1)
					path.removeAt(path.lastIndex)

					if (!isOneLine) {
						output.appendLine()
					}
				}

				if (!isOneLine) {
					appendIndentation(indent)
				}

				output.append(']')
			}
			is SomlObject -> {
				checkDepth(depth)

				if (value.isEmpty()) {
					output.append("{}")
					return
				}

				output.append(if (isOneLine) "{" else "{\n")
				writeMembers(value, depth + 1, indent + 1)

				if (!isOneLine) {
					appendIndentation(indent)
				}

				output.append('}')
			}
			else -> output.appendScalar(value)
		}
	}

	/**
	Writes each member on its own line, indented by `indent` tabs. The values of the members have the depth `depth`.
	*/
	private fun writeMembers(value: SomlObject, depth: Int, indent: Int) {
		// Note: Not using isSortedWith() because its extra key scan measured slower for nearly sorted canonical objects.
		val entries: Collection<Map.Entry<String, SomlValue>> = if (isCanonical && value.size > 1) value.entries.sortedWith(canonicalMemberOrder) else value.entries
		var isFirst = true

		for ((key, member) in entries) {
			if (isOneLine) {
				if (!isFirst) {
					output.append(", ")
				}
			} else {
				appendIndentation(indent)
			}

			isFirst = false
			output.appendKey(key)
			output.append(": ")
			path.add(key)
			writeValue(member, depth, indent)
			path.removeAt(path.lastIndex)

			if (!isOneLine) {
				output.appendLine()
			}
		}
	}

	companion object {
		/**
		A document: a non-empty object without braces, and anything else as a value, ending with a line feed.

		@throws SomlException When the value is nested more than 100 levels deep.
		*/
		fun writeDocument(value: SomlValue, isCanonical: Boolean): String {
			require(value is SomlObject || value is SomlArray) { "A document is an object or an array, not ${describeType(value)}" }
			val output = StringBuilder(256)
			val writer = Writer(output, isCanonical, "", isOneLine = false)

			if (value is SomlObject && value.isNotEmpty()) {
				writer.checkDepth(1)
				writer.writeMembers(value, depth = 2, indent = 0)
			} else {
				writer.writeValue(value, depth = 1, indent = 0)
				output.appendLine()
			}

			return output.toString()
		}

		/**
		One value as it is written in canonical form, without a line feed at the end, for `toString()`. It does not check the nesting limit, so that it can show a value that is too deep to encode.
		*/
		fun writeValue(value: SomlValue): String {
			// Note: Not using buildString() because complete value writes measured slower than this explicit builder scope.
			val output = StringBuilder(64)
			// A depth that never reaches the limit, so that a value built in code can always be shown.
			Writer(output, isCanonical = true, "", isOneLine = false).writeValue(value, Int.MIN_VALUE / 2, 0)
			return output.toString()
		}

		/**
		A new value for the editor, whose own depth is `depth`, with the lines of a container after its first starting with `indentation`, or all on one line.

		@throws SomlException When the value would nest the document more than 100 levels deep.
		*/
		fun writeValueAt(value: SomlValue, depth: Int, indentation: String, isOneLine: Boolean): String {
			val output = StringBuilder(64)
			Writer(output, isCanonical = false, indentation, isOneLine).writeValue(value, depth, 0)
			return output.toString()
		}

		fun describeType(value: SomlValue): String = when (value) {
			is SomlString -> "a string"
			is SomlInt -> "an int"
			is SomlFloat -> "a float"
			is SomlBool -> "a bool"
			SomlNull -> "null"
			is SomlDate -> "a date"
			is SomlInstant -> "an instant"
			is SomlDuration -> "a duration"
			is SomlArray -> "an array"
			is SomlObject -> "an object"
		}
	}
}

/**
Tabs for every level of indentation, so that a line's indentation is one append.
*/
private val TABS = "\t".repeat(MAX_DEPTH + 2)

/**
Appends `count` tabs in bounded chunks without allocating a repeated string, including for a value deeper than the document limit.
*/
internal fun StringBuilder.appendTabs(count: Int) {
	// Note: Not using repeat() because it allocates a temporary indentation string for every output line.
	// A value that `toString()` shows can be deeper than the limit, and so deeper than the tabs.
	var remaining = count

	while (remaining > TABS.length) {
		append(TABS)
		remaining -= TABS.length
	}

	append(TABS, 0, remaining)
}

/**
Orders keys by Unicode scalar value, as canonical form sorts them, which is the order of their UTF-8 bytes.

Note: Not using String.compareTo() because it compares UTF-16 code units instead, which puts the characters above U+FFFF before U+E000 to U+FFFF.
*/
internal fun compareKeys(left: String, right: String): Int {
	if (left === right) {
		return 0
	}

	val length = minOf(left.length, right.length)

	for (index in 0..<length) {
		var leftCode = left[index].code
		var rightCode = right[index].code

		if (leftCode != rightCode) {
			// Below U+D800, the order of code units is the order of code points, so the two orders can differ only when both units are at or above it.
			if (leftCode >= 0xD800 && rightCode >= 0xD800) {
				leftCode = toCodePointOrder(leftCode)
				rightCode = toCodePointOrder(rightCode)
			}

			return leftCode - rightCode
		}
	}

	return left.length - right.length
}

/**
U+E000 to U+FFFF move down by 0x800 into the surrogate range, and the surrogates move up by 0x2000 above them, so each group keeps its own order.
*/
private fun toCodePointOrder(code: Int): Int = when {
	code >= 0xE000 -> code - 0x800
	code >= 0xD800 -> code + 0x2000
	else -> code
}

/**
The members of an object in canonical order.
*/
// Note: Not using compareBy() because wrapping the existing key comparator measured slower during canonical sorting.
private val canonicalMemberOrder: Comparator<Map.Entry<String, SomlValue>> = Comparator { left, right -> compareKeys(left.key, right.key) }

/**
Appends a scalar value as canonical form writes it.
*/
internal fun StringBuilder.appendScalar(value: SomlValue) {
	when (value) {
		is SomlString -> appendString(value.value)
		is SomlInt -> append(value.value)
		is SomlFloat -> append(formatFloat(value.value))
		is SomlBool -> append(value.value)
		SomlNull -> append("null")
		is SomlDate -> appendDate(value.year, value.month, value.day)
		is SomlInstant -> appendInstant(value.value)
		is SomlDuration -> appendDuration(value.nanoseconds)
		is SomlArray, is SomlObject -> throw IllegalArgumentException("Expected a scalar")
	}
}

/**
Appends a key: bare when it is non-empty and every character is in the bare-key set, and otherwise as a string.
*/
internal fun StringBuilder.appendKey(key: String) {
	if (isBareKey(key)) {
		append(key)
	} else {
		appendString(key)
	}
}

/**
A key as a document writes it.
*/
internal fun formatKey(key: String): String = buildString { appendKey(key) }

/**
Appends a string: `'...'` unless it holds `'`, a tab, a line feed, another C0 control, or U+007F, and otherwise `"..."` with only those characters, `\`, and `"` escaped.
*/
internal fun StringBuilder.appendString(value: String) {
	if (!needsEscapedForm(value)) {
		append('\'').append(value).append('\'')
		return
	}

	append('"')
	var chunkStart = 0

	for ((index, character) in value.withIndex()) {
		val escape = when (character) {
			'\\' -> "\\\\"
			'"' -> "\\\""
			'\n' -> "\\n"
			'\t' -> "\\t"
			in '\u0000'..'\u001F', '\u007F' -> null
			else -> continue
		}

		append(value, chunkStart, index)

		if (escape == null) {
			append("\\u{").append(character.code.toString(16)).append('}')
		} else {
			append(escape)
		}

		chunkStart = index + 1
	}

	append(value, chunkStart, value.length)
	append('"')
}

private fun needsEscapedForm(value: String): Boolean {
	for (character in value) {
		if (character == '\'' || character.code < 0x20 || character.code == 0x7F) {
			return true
		}
	}

	return false
}

private const val NANOSECONDS_PER_SECOND = 1_000_000_000L
private const val NANOSECONDS_PER_MINUTE = 60 * NANOSECONDS_PER_SECOND
private const val NANOSECONDS_PER_HOUR = 60 * NANOSECONDS_PER_MINUTE
private const val SECONDS_PER_DAY = 86_400L

/**
Appends exactly ten ASCII characters for a validated Gregorian date.
*/
internal fun StringBuilder.appendDate(year: Int, month: Int, day: Int) {
	appendPadded(year, 4)
	append('-')
	appendPadded(month, 2)
	append('-')
	appendPadded(day, 2)
}

/**
Appends an instant in UTC with `Z`, and a fraction of a second only when it is not zero, without trailing zeros.

The date comes from Howard Hinnant's `civil_from_days`, which is exact for every day of the proleptic Gregorian calendar.
*/
internal fun StringBuilder.appendInstant(value: Instant) {
	// Note: Not using Instant.toString() because canonicalizing its fractional zeros still allocates an intermediate ISO string and measured slower than appending directly.
	val seconds = value.epochSeconds
	val days = seconds.floorDiv(SECONDS_PER_DAY)
	val secondOfDay = seconds.mod(SECONDS_PER_DAY).toInt()
	// The days from 0000-03-01, so that a year starts in March and a leap day is the last day of its year. Every supported year follows that day, so this is positive and fits in an `Int`.
	val shiftedDays = (days + 719_468).toInt()
	val era = shiftedDays / 146_097
	val dayOfEra = shiftedDays - (era * 146_097)
	val yearOfEra = (dayOfEra - (dayOfEra / 1460) + (dayOfEra / 36_524) - (dayOfEra / 146_096)) / 365
	val dayOfYear = dayOfEra - ((365 * yearOfEra) + (yearOfEra / 4) - (yearOfEra / 100))
	val monthIndex = ((5 * dayOfYear) + 2) / 153
	val day = dayOfYear - (((153 * monthIndex) + 2) / 5) + 1
	val month = if (monthIndex < 10) monthIndex + 3 else monthIndex - 9
	val year = yearOfEra + (era * 400) + (if (month <= 2) 1 else 0)
	appendDate(year, month, day)
	append('T')
	appendPadded(secondOfDay / 3600, 2)
	append(':')
	appendPadded((secondOfDay / 60) % 60, 2)
	append(':')
	appendPadded(secondOfDay % 60, 2)
	appendFraction(value.nanosecondsOfSecond)
	append('Z')
}

/**
Appends a duration: `-` when it is negative, then its hours and minutes, each left out when zero, then its seconds with their fraction, left out when both are zero unless the whole duration is zero.
*/
internal fun StringBuilder.appendDuration(nanoseconds: Long) {
	// Note: Not using Duration.toString() because its day units, spaces, and subsecond units differ from canonical SOML.
	if (nanoseconds == 0L) {
		append("0s")
		return
	}

	// The arithmetic is in the negative range, which also holds the magnitude of the most negative duration.
	// Note: Not using -absoluteValue because it showed no consistent gain in complete duration formatting on Native.
	val negative = if (nanoseconds > 0) -nanoseconds else nanoseconds
	val hours = -(negative / NANOSECONDS_PER_HOUR)
	var remainder = -(negative % NANOSECONDS_PER_HOUR)
	val minutes = remainder / NANOSECONDS_PER_MINUTE
	remainder %= NANOSECONDS_PER_MINUTE
	val seconds = remainder / NANOSECONDS_PER_SECOND
	val fraction = (remainder % NANOSECONDS_PER_SECOND).toInt()

	if (nanoseconds < 0) {
		append('-')
	}

	if (hours > 0) {
		append(hours).append('h')
	}

	if (minutes > 0) {
		append(minutes).append('m')
	}

	if (seconds > 0 || fraction > 0) {
		append(seconds)
		appendFraction(fraction)
		append('s')
	}
}

/**
Appends the fraction of a second as canonical form writes it for an instant and a duration: nothing when it is zero, and otherwise a `.` and nine digits without the trailing zeros.
*/
private fun StringBuilder.appendFraction(nanoseconds: Int) {
	if (nanoseconds == 0) {
		return
	}

	var digits = nanoseconds
	var count = 9

	while (digits % 10 == 0) {
		digits /= 10
		count--
	}

	append('.')
	appendPadded(digits, count)
}

/**
Appends a non-negative number padded with zeros to at least `width` digits.
*/
private fun StringBuilder.appendPadded(value: Int, width: Int) {
	// Note: Not using padStart() because it allocates temporary decimal and padded strings instead of appending directly to the output.
	var digitCount = 1
	var rest = value

	while (rest >= 10) {
		rest /= 10
		digitCount++
	}

	repeat(width - digitCount) {
		append('0')
	}

	append(value)
}

/**
A float as canonical form writes it: the shortest decimal that reads back as the same value, laid out as ECMAScript's `Number::toString` does, with `.0` added so that it never reads as an int, as in `30.0`, `0.1`, `1e21`, and `infinity`.

Each platform finds the digits its own way, and how exact that is differs, as the readme says.
*/
internal expect fun formatFloat(value: Double): String

/**
The float nearest to a decimal number, which is valid in the grammar of a SOML float without underscores, as `1.5`, `-2e-7`, or `100.0`.

Each platform converts it its own way, and how exact that is differs, as the readme says.
*/
internal expect fun parseFloat(text: String): Double

/**
A float as canonical form writes it, from `text`, a spelling of its magnitude that reads back as the same value, such as `1.5E-7`, `1e+21`, or `123.45`, as a platform's `toString()` writes it.
*/
internal fun formatFloat(value: Double, text: String): String {
	if (value.isInfinite()) {
		return if (value < 0) "-infinity" else "infinity"
	}

	if (value == 0.0) {
		return "0.0"
	}

	// Note: Not using indexOfAny() because direct checks of the two exponent markers measured faster, even with a cached character array.
	val exponentIndex = text.indexOfFirst { it == 'e' || it == 'E' }
	val mantissaEnd = if (exponentIndex == -1) text.length else exponentIndex
	val exponent = if (exponentIndex == -1) 0 else text.substring(exponentIndex + 1).toInt()
	val pointIndex = text.indexOfWithin('.', 0, mantissaEnd)
	val integerLength = if (pointIndex == -1) mantissaEnd else pointIndex
	// Note: Not using indexOfFirst() for leading zeros because complete JavaScript float formatting showed no consistent gain.
	var first = 0
	var leadingZeros = 0

	while (first < mantissaEnd && (text[first] == '0' || text[first] == '.')) {
		if (text[first] == '0') {
			leadingZeros++
		}

		first++
	}

	var last = mantissaEnd

	while (text[last - 1] == '0' || text[last - 1] == '.') {
		last--
	}

	val digits = buildString(last - first) {
		if (pointIndex in first..<last) {
			append(text, first, pointIndex).append(text, pointIndex + 1, last)
		} else {
			append(text, first, last)
		}
	}
	// The value is 0.<digits> × 10^n, with no zeros at either end of the digits.
	return formatFloat(value, digits, integerLength + exponent - leadingZeros)
}

/**
A float as canonical form writes it, from the digits of its shortest decimal, for the value 0.<digits> × 10^n.
*/
internal fun formatFloat(value: Double, digits: String, n: Int): String {
	if (value.isInfinite()) {
		return if (value < 0) "-infinity" else "infinity"
	}

	if (value == 0.0) {
		return "0.0"
	}

	return buildString(32) {
		if (value < 0) {
			append('-')
		}

		layoutFloat(digits, n)
	}
}

/**
Lays out the `k` digits of 0.<digits> × 10^n by the steps of canonical rule 11.
*/
internal fun StringBuilder.layoutFloat(digits: String, n: Int) {
	val k = digits.length

	when {
		n in k..21 -> {
			append(digits)

			repeat(n - k) {
				append('0')
			}

			append(".0")
		}
		n in 1..<k -> append(digits, 0, n).append('.').append(digits, n, k)
		n in -5..0 -> {
			append("0.")

			repeat(-n) {
				append('0')
			}

			append(digits)
		}
		else -> {
			append(digits[0])

			if (k > 1) {
				append('.').append(digits, 1, k)
			}

			append('e').append(n - 1)
		}
	}
}
