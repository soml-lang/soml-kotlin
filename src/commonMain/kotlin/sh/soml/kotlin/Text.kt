package sh.soml.kotlin

/*
Small text functions shared by the parser, the writer, and error messages.
*/

/**
The deepest a document may nest. Every object and array counts, including the document's own collection.
*/
internal const val MAX_DEPTH = 100

/**
The longest token that a diagnostic looks at. A longer one is described from its first part, so that no message or scan for a hint grows with the input.
*/
internal const val MAX_DIAGNOSED_LENGTH = 1000

private const val UPPERCASE_HEX_DIGITS = "0123456789ABCDEF"
private const val LOWERCASE_HEX_DIGITS = "0123456789abcdef"

// Note: Not using Char.isDigit() or Char.isLetterOrDigit() because SOML's digits and bare keys are ASCII.
internal fun Int.isDigitCode(): Boolean = this in '0'.code..'9'.code

internal fun Int.isLetterCode(): Boolean = this in 'A'.code..'Z'.code || this in 'a'.code..'z'.code

internal fun Int.isSpaceCode(): Boolean = this == ' '.code || this == '\t'.code

/**
Whether a UTF-16 code unit, or -1 past the end, may be part of a bare key: an ASCII letter, a digit, `_`, or `-`.
*/
internal fun Int.isBareKeyCode(): Boolean = isDigitCode() || isLetterCode() || this == '_'.code || this == '-'.code

/**
Whether a code unit may be part of a run of scalar characters: an int, a float, a date, an instant, a duration, or a keyword.
*/
internal fun Int.isNumberCode(): Boolean = isDigitCode() || isLetterCode() || this == '_'.code || this == '.'.code || this == ':'.code || this == '+'.code || this == '-'.code

/**
Whether `key` can be written without quotes: one or more ASCII letters, digits, `_`, or `-`.
*/
internal fun isBareKey(key: String): Boolean {
	// Note: Not using all() because the direct String loop measured faster for long ASCII keys.
	if (key.isEmpty()) {
		return false
	}

	for (character in key) {
		if (!character.code.isBareKeyCode()) {
			return false
		}
	}

	return true
}

/**
A key as an error message shows it: bare when it can be, and otherwise quoted, and cut short when it is long.
*/
internal fun describeKey(key: String): String = if (isBareKey(key)) abbreviate(key) else quoteJson(abbreviate(key))

/**
Shortens text quoted in an error message, so a huge token does not make a huge message. The cut never splits a surrogate pair.
*/
internal fun abbreviate(text: String, maximumLength: Int = 40): String {
	if (text.length <= maximumLength) {
		return text
	}

	val end = if (text[maximumLength - 1].isHighSurrogate()) maximumLength - 1 else maximumLength
	return text.substring(0, end) + "…"
}

/**
A string as JSON writes it, as JavaScript's `JSON.stringify` does, for a key in a message.
*/
internal fun quoteJson(text: String): String = buildString(text.length + 2) {
	append('"')
	var chunkStart = 0
	var offset = 0

	while (offset < text.length) {
		val character = text[offset]

		// A lone surrogate is escaped and a surrogate pair is kept, as the well-formed `JSON.stringify` of ES2019 does.
		if (character.isHighSurrogate() && offset + 1 < text.length && text[offset + 1].isLowSurrogate()) {
			offset += 2
			continue
		}

		if (character.code >= 0x20 && character != '"' && character != '\\' && !character.isSurrogate()) {
			offset++
			continue
		}

		append(text, chunkStart, offset)

		when (character) {
			'"' -> append("\\\"")
			'\\' -> append("\\\\")
			'\b' -> append("\\b")
			'\u000C' -> append("\\f")
			'\n' -> append("\\n")
			'\r' -> append("\\r")
			'\t' -> append("\\t")
			else -> {
				append("\\u")
				appendHex(character.code, 4, uppercase = false)
			}
		}

		offset++
		chunkStart = offset
	}

	append(text, chunkStart, text.length)
	append('"')
}

/**
Appends `value` in hexadecimal, padded with zeros to at least `width` digits.
*/
internal fun StringBuilder.appendHex(value: Int, width: Int, uppercase: Boolean = true) {
	// Note: Not using toHexString() because it allocates a temporary string instead of appending directly to the output.
	val digitCount = maxOf(1, (Int.SIZE_BITS - value.countLeadingZeroBits() + 3) / 4)
	val digits = if (uppercase) UPPERCASE_HEX_DIGITS else LOWERCASE_HEX_DIGITS

	repeat(maxOf(0, width - digitCount)) {
		append('0')
	}

	for (position in (digitCount - 1) downTo 0) {
		append(digits[(value ushr (position * 4)) and 0xF])
	}
}

/**
A code point as `U+00A0`: four digits for the BMP, with further digits only when the value needs them.
*/
internal fun formatCodePoint(codePoint: Int): String = buildString {
	append("U+")
	appendHex(codePoint, 4)
}

/**
Describes a character for an error message, such as `“$”` or `U+00A0 (NO-BREAK SPACE)`.
*/
internal fun describeCharacter(codePoint: Int): String {
	if (codePoint in 0x20..0x7E) {
		return "“${codePoint.toChar()}”"
	}

	val hasName = (codePoint > ' '.code && isWhiteSpace(codePoint))
		|| codePoint == 0x061C
		|| codePoint == 0xAD
		|| codePoint in 0x200B..0x200F
		|| codePoint in 0x202A..0x202E
		|| codePoint == 0x2060
		|| codePoint in 0x2066..0x2069
		|| codePoint == 0xFEFF

	if (hasName) {
		val name = if (codePoint == 0x85) "NEXT LINE" else characterNames.getValue(codePoint)
		val isWhitespace = isWhiteSpace(codePoint) || codePoint == 0x200B || codePoint == 0xFEFF
		return "${formatCodePoint(codePoint)} ($name${if (isWhitespace) "; only space, tab, and line feed are whitespace" else ""})"
	}

	// Control, format, private-use, unassigned, surrogate, separator, and default-ignorable characters are invisible or ambiguous, so they are shown by code point.
	if (codePoint != ' '.code && (isDefaultIgnorable(codePoint) || isOther(codePoint) || isSeparator(codePoint))) {
		return formatCodePoint(codePoint)
	}

	return "“${codePointToString(codePoint)}”"
}

/**
The Unicode property White_Space.
*/
// Note: Not using Char.isWhitespace() because its character set differs from Unicode White_Space.
internal fun isWhiteSpace(codePoint: Int): Boolean = codePoint in 0x09..0x0D
	|| codePoint == 0x20
	|| codePoint == 0x85
	|| codePoint == 0xA0
	|| codePoint == 0x1680
	|| codePoint in 0x2000..0x200A
	|| codePoint == 0x2028
	|| codePoint == 0x2029
	|| codePoint == 0x202F
	|| codePoint == 0x205F
	|| codePoint == 0x3000

/**
The Unicode general category Other: control, format, surrogate, private use, and unassigned.
*/
internal fun isOther(codePoint: Int): Boolean {
	// Kotlin knows the categories of the BMP on every platform, but not above it. There, only the private use planes are known to be Other, which only changes how an error message shows a character.
	if (codePoint > 0xFFFF) {
		return codePoint >= 0xF0000
	}

	return when (codePoint.toChar().category) {
		CharCategory.CONTROL, CharCategory.FORMAT, CharCategory.SURROGATE, CharCategory.PRIVATE_USE, CharCategory.UNASSIGNED -> true
		else -> false
	}
}

/**
The Unicode general category Separator: space, line, and paragraph separators, which are all in the BMP.
*/
private fun isSeparator(codePoint: Int): Boolean {
	if (codePoint > 0xFFFF) {
		return false
	}

	return when (codePoint.toChar().category) {
		CharCategory.SPACE_SEPARATOR, CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR -> true
		else -> false
	}
}

/**
The Unicode names of the characters that `describeCharacter()` names, as the JDK's `Character.getName()` gives them.
*/
private val characterNames: Map<Int, String> = mapOf(
	0x00A0 to "NO-BREAK SPACE",
	0x00AD to "SOFT HYPHEN",
	0x061C to "ARABIC LETTER MARK",
	0x1680 to "OGHAM SPACE MARK",
	0x2000 to "EN QUAD",
	0x2001 to "EM QUAD",
	0x2002 to "EN SPACE",
	0x2003 to "EM SPACE",
	0x2004 to "THREE-PER-EM SPACE",
	0x2005 to "FOUR-PER-EM SPACE",
	0x2006 to "SIX-PER-EM SPACE",
	0x2007 to "FIGURE SPACE",
	0x2008 to "PUNCTUATION SPACE",
	0x2009 to "THIN SPACE",
	0x200A to "HAIR SPACE",
	0x200B to "ZERO WIDTH SPACE",
	0x200C to "ZERO WIDTH NON-JOINER",
	0x200D to "ZERO WIDTH JOINER",
	0x200E to "LEFT-TO-RIGHT MARK",
	0x200F to "RIGHT-TO-LEFT MARK",
	0x2028 to "LINE SEPARATOR",
	0x2029 to "PARAGRAPH SEPARATOR",
	0x202A to "LEFT-TO-RIGHT EMBEDDING",
	0x202B to "RIGHT-TO-LEFT EMBEDDING",
	0x202C to "POP DIRECTIONAL FORMATTING",
	0x202D to "LEFT-TO-RIGHT OVERRIDE",
	0x202E to "RIGHT-TO-LEFT OVERRIDE",
	0x202F to "NARROW NO-BREAK SPACE",
	0x205F to "MEDIUM MATHEMATICAL SPACE",
	0x2060 to "WORD JOINER",
	0x2066 to "LEFT-TO-RIGHT ISOLATE",
	0x2067 to "RIGHT-TO-LEFT ISOLATE",
	0x2068 to "FIRST STRONG ISOLATE",
	0x2069 to "POP DIRECTIONAL ISOLATE",
	0x3000 to "IDEOGRAPHIC SPACE",
	0xFEFF to "ZERO WIDTH NO-BREAK SPACE",
)

/**
The Unicode property Default_Ignorable_Code_Point, which Java's regular expressions do not support.
*/
// Note: Not using isIdentifierIgnorable() because its character set differs from Unicode Default_Ignorable_Code_Point.
internal fun isDefaultIgnorable(codePoint: Int): Boolean = codePoint == 0x00AD
	|| codePoint == 0x034F
	|| codePoint == 0x061C
	|| codePoint in 0x115F..0x1160
	|| codePoint in 0x17B4..0x17B5
	|| codePoint in 0x180B..0x180F
	|| codePoint in 0x200B..0x200F
	|| codePoint in 0x202A..0x202E
	|| codePoint in 0x2060..0x206F
	|| codePoint == 0x3164
	|| codePoint in 0xFE00..0xFE0F
	|| codePoint == 0xFEFF
	|| codePoint == 0xFFA0
	|| codePoint in 0xFFF0..0xFFF8
	|| codePoint in 0x1BCA0..0x1BCA3
	|| codePoint in 0x1D173..0x1D17A
	|| codePoint in 0xE0000..0xE0FFF

/**
Replaces every character that a terminal acts on rather than shows with U+FFFD, which is also one code unit, so a caret under the text still lines up: control characters except tab, bidirectional controls, and lone surrogates.
*/
internal fun sanitize(text: String): String {
	// Note: Not using StringBuilder.set() because copying the whole text then setting characters allocated more for dirty input and measured slower for sparse replacements.
	var output: StringBuilder? = null
	var runStart = 0
	var index = 0

	while (index < text.length) {
		val character = text[index]

		if (character.code in 0x20..0x7E) {
			index++
			continue
		}

		if (character.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
			index += 2
			continue
		}

		val isUnprintable = character.isSurrogate() || character.isBidiControl() || (character.isISOControl() && character != '\t')

		if (isUnprintable) {
			val builder = output ?: StringBuilder(text.length).also { output = it }
			builder.append(text, runStart, index).append('�')
			runStart = index + 1
		}

		index++
	}

	return output?.append(text, runStart, text.length)?.toString() ?: text
}

private fun Char.isBidiControl(): Boolean = code == 0x061C || code == 0x200E || code == 0x200F || code in 0x202A..0x202E || code in 0x2066..0x2069

/**
The path as a message shows it, such as `servers[1].port`, or an empty string for the top of the document. A key that is not bare is quoted, as in `labels["app.kubernetes.io/name"]`.
*/
internal fun formatPath(path: List<Any>): String = buildString {
	// Note: Not using joinTo() because transformed path components allocate strings instead of appending directly.
	for (component in path) {
		when (component) {
			is Int -> append('[').append(component).append(']')
			is String if isBareKey(component) -> {
				if (isNotEmpty()) {
					append('.')
				}

				append(abbreviate(component))
			}
			is String -> append('[').append(quoteJson(abbreviate(component))).append(']')
			else -> error("Unexpected path component: $component")
		}
	}
}

/**
The first index of `character` from `start` to before `end`, or -1. The search never looks past `end`, so that scans of one range of a long text stay linear.
*/
internal expect fun String.indexOfWithin(character: Char, start: Int, end: Int): Int

/**
A bounded character search for platforms without the JDK 21 intrinsic.
*/
internal fun String.indexOfWithinPortably(character: Char, start: Int, end: Int): Int {
	for (index in start..<end) {
		if (this[index] == character) {
			return index
		}
	}

	return -1
}

/**
The largest Unicode code point.
*/
internal const val MAX_CODE_POINT = 0x10FFFF

/**
Whether two code units are a surrogate pair, which is one code point above U+FFFF.
*/
// Note: Not using codePointAt() or codePointCount() because they are JVM-only, while these helpers also support JS, Wasm, and Native.
internal fun isSurrogatePair(high: Char, low: Char): Boolean = high.isHighSurrogate() && low.isLowSurrogate()

/**
The code point of a surrogate pair.
*/
internal fun toCodePoint(high: Char, low: Char): Int = ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000

/**
The number of code units of a code point: 2 above U+FFFF, and 1 otherwise.
*/
internal fun charCountOf(codePoint: Int): Int = if (codePoint > 0xFFFF) 2 else 1

/**
The code point at `index`: the surrogate pair that starts there, or the code unit, which may be a lone surrogate.
*/
internal fun String.codePointAtOffset(index: Int): Int {
	val high = this[index]

	if (index + 1 < length && isSurrogatePair(high, this[index + 1])) {
		return toCodePoint(high, this[index + 1])
	}

	return high.code
}

/**
The number of code points from `start` to `end`, where a surrogate pair is one and a lone surrogate is one.
*/
internal fun String.codePointCountBetween(start: Int, end: Int): Int {
	// Note: Not using zipWithNext() because it builds a list and requires copying this text range.
	// Note: Not using sumOf() because visiting both units of surrogate pairs measured slower in long diagnostic text.
	var count = 0
	var index = start

	while (index < end) {
		index += if (index + 1 < end && isSurrogatePair(this[index], this[index + 1])) 2 else 1
		count++
	}

	return count
}

/**
Appends a code point, as a surrogate pair above U+FFFF.
*/
internal fun StringBuilder.appendCodePointOf(codePoint: Int) {
	if (codePoint > 0xFFFF) {
		val offset = codePoint - 0x10000
		append((0xD800 + (offset shr 10)).toChar())
		append((0xDC00 + (offset and 0x3FF)).toChar())
	} else {
		append(codePoint.toChar())
	}
}

/**
A code point as a string.
*/
// Note: Not using a Char.toString() fast path because it adds a branch without a consistent whole-diagnostic timing benefit.
internal fun codePointToString(codePoint: Int): String = buildString { appendCodePointOf(codePoint) }

/**
A map that is safe to read and fill from several threads, for what a `Soml` instance learns about classes. Two threads may both make the value of a missing key, and one of them is kept.
*/
internal expect class ConcurrentCache<K : Any, V : Any>() {
	fun getOrPut(key: K, makeValue: () -> V): V
}
