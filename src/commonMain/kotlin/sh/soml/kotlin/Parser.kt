package sh.soml.kotlin

import kotlin.time.Instant

/*
A recursive-descent parser that builds a `SomlValue` tree and checks every rule in the spec.

It is a port of the parser of soml-java, which ports the JavaScript reference implementation. All three work on UTF-16 code units, so they report every error at the same position and with the same reason. Most of the code is diagnostics: when something fails, it works out what the author most likely meant, such as a YAML list, a TOML table header, or an unquoted string. The diagnostics are in `ParserDiagnostics.kt`.

Not ported from soml-java, to keep it simple: the event sink for decoding without a tree, the recorded value positions, the pooled scratch buffers, the key cache, and the scan of four characters at a time in string and comment content.
*/
internal class Parser private constructor(internal val source: String) {
	// Note: Not using String.get() in the scans because direct string indexing measured slower than the character array.
	internal val characters: CharArray = source.toCharArray()
	internal val length: Int = characters.size
	internal var index: Int = 0

	// Where the document's collection starts, after the comments and whitespace before it.
	internal var documentStart: Int = 0

	internal companion object {
		private val POWERS_OF_TEN = doubleArrayOf(1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15)

		// No character is a NUL that is not caught as a control character first, so this matches nothing more.
		internal const val NONE = '\u0000'

		/**
		Parses a document, which is always an object or an array.

		@throws SomlException When the document is not valid.
		*/
		fun parse(source: String): SomlValue = Parser(source).run()

		/**
		Checks a document.

		@throws SomlException When the document is not valid.
		*/
		fun validate(source: String) {
			Parser(source).run()
		}

		/**
		The value of a quoted string or key that is already known to be valid, from `start` to `end` in `source`, for the syntax tree.
		*/
		fun readQuoted(source: String, start: Int, end: Int): String {
			// A literal string, or a string without escapes, is the text between its quotes.
			if (source[start] == '\'' || source.indexOfWithin('\\', start, end) == -1) {
				return source.substring(start + 1, end - 1)
			}

			return Parser(source.substring(start, end)).parseString()
		}

		/**
		UTF-8 without replacement. Invalid input is reported at the first byte of the first invalid sequence, with the position counted in the text decoded before it.

		@throws SomlException When `bytes` is not well-formed UTF-8.
		*/
		fun decodeUtf8(bytes: ByteArray): String {
			// Note: Not using decodeToString(throwOnInvalidSequence = true) because it adds decoder buffers and measured slower for ASCII on the JVM.
			// The fast path: the replacing decoder writes U+FFFD for every invalid sequence, so text without one is valid. Text that holds a U+FFFD, valid or not, takes the strict path.
			val text = bytes.decodeToString()

			if (text.indexOf('�') == -1) {
				return text
			}

			val offset = findInvalidUtf8(bytes)

			if (offset == bytes.size) {
				return text
			}

			val valid = bytes.decodeToString(0, offset)

			if (sequenceLength(bytes, offset) > bytes.size - offset) {
				throw SomlException.at("Incomplete UTF-8 sequence at the end of the input", valid, valid.length)
			}

			throw SomlException.at("Invalid UTF-8 byte 0x" + buildString { appendHex(bytes[offset].toInt() and 0xFF, 2) }, valid, valid.length)
		}

		/**
		The first byte of the first invalid sequence. A sequence's length is decided by its first byte, as in the reference.
		*/
		private fun findInvalidUtf8(bytes: ByteArray): Int {
			var index = 0

			while (index < bytes.size) {
				if (bytes[index] >= 0) {
					index++
					continue
				}

				val length = sequenceLength(bytes, index)

				if (length == 0 || bytes.size - index < length) {
					return index
				}

				index += length
			}

			return index
		}

		/**
		The length of the well-formed sequence or incomplete prefix at `index`, or 0 when there is none, by the table of well-formed UTF-8 in the Unicode standard.
		*/
		private fun sequenceLength(bytes: ByteArray, index: Int): Int {
			// Note: Not using toUByte().toInt() because masking measured faster in the Native byte loop.
			val first = bytes[index].toInt() and 0xFF
			var secondMinimum = 0x80
			var secondMaximum = 0xBF

			val length = when (first) {
				in 0xC2..0xDF -> 2
				in 0xE0..0xEF -> {
					if (first == 0xE0) {
						secondMinimum = 0xA0
					} else if (first == 0xED) {
						secondMaximum = 0x9F
					}

					3
				}
				in 0xF0..0xF4 -> {
					if (first == 0xF0) {
						secondMinimum = 0x90
					} else if (first == 0xF4) {
						secondMaximum = 0x8F
					}

					4
				}
				else -> return 0
			}

			val available = minOf(length, bytes.size - index)

			for (offset in 1..<available) {
				val next = bytes[index + offset].toInt() and 0xFF
				val minimum = if (offset == 1) secondMinimum else 0x80
				val maximum = if (offset == 1) secondMaximum else 0xBF

				if (next !in minimum..maximum) {
					return 0
				}
			}

			return length
		}

		/**
		Whether `text` is one valid value, so that an error message only suggests a fix that works.
		*/
		internal fun isValidValue(text: String): Boolean {
			val parser = Parser(text)

			return try {
				parser.skipTrivia()
				parser.parseValue(2)
				parser.skipTrivia()
				parser.isAtEnd
			} catch (_: SomlException) {
				false
			}
		}

		/**
		The days from 1970-01-01 to a date in the proleptic Gregorian calendar, by Howard Hinnant's `days_from_civil`.
		*/
		internal fun daysFromCivil(year: Int, month: Int, day: Int): Long {
			val shiftedYear = (if (month <= 2) year - 1 else year).toLong()
			val era = shiftedYear.floorDiv(400L)
			val yearOfEra = shiftedYear - (era * 400)
			val dayOfYear = (((153L * (if (month > 2) month - 3 else month + 9)) + 2) / 5) + day - 1
			val dayOfEra = (yearOfEra * 365) + (yearOfEra / 4) - (yearOfEra / 100) + dayOfYear
			return (era * 146_097) + dayOfEra - 719_468
		}

	}

	/**
	Parses the whole document. After an error, a character that is an error anywhere is reported instead, as the reference does, which checks every character first.
	*/
	private fun run(): SomlValue {
		try {
			return parseDocument()
		} catch (error: SomlException) {
			checkCharacters()
			throw error
		}
	}

	/**
	Characters that are errors wherever they appear, in one pass over the document. A control character is reported before a lone surrogate, wherever each is, and both before any other error, as the reference reports them.

	The parse does not run this first. Outside a string or a comment, such a character is a syntax error anyway, and the scans of strings and comments call this when they meet one, so it runs only when the document has an error.
	*/
	internal fun checkCharacters() {
		// Note: Not using isISOControl() because C1 characters are valid string and comment content.
		if (length > 0 && characters[0] == '﻿') {
			throw fail("A byte order mark (BOM) is not allowed", 0)
		}

		var loneSurrogate = -1
		var offset = 0

		while (offset < length) {
			val character = characters[offset]

			// Printable ASCII, the common case, in one comparison.
			if (character.code - 0x20 in 0..<0x5F) {
				offset++
				continue
			}

			if (character.code < 0x20) {
				if (character != '\t' && character != '\n') {
					throw failControlCharacter(offset)
				}
			} else if (character.isSurrogate()) {
				if (character.isHighSurrogate() && offset + 1 < length && characters[offset + 1].isLowSurrogate()) {
					offset++
				} else if (loneSurrogate == -1) {
					loneSurrogate = offset
				}
			} else if (character.code == 0x7F) {
				throw failControlCharacter(offset)
			}

			offset++
		}

		if (loneSurrogate != -1) {
			throw fail("A lone surrogate (${describeCharacter(characters[loneSurrogate].code)}) is not a Unicode scalar value", loneSurrogate)
		}
	}

	/**
	A character in a string or a comment that is not printable ASCII and not a line feed. A tab, a character above ASCII, and a surrogate pair are content, and the offset of the last character it takes is returned. Anything else is an error somewhere in the document, which the full check reports, at the first such character.
	*/
	private fun checkContent(offset: Int): Int {
		val character = characters[offset]

		if (character == '\t' || (character.code >= 0x80 && !character.isSurrogate())) {
			return offset
		}

		if (character.isHighSurrogate() && offset + 1 < length && characters[offset + 1].isLowSurrogate()) {
			return offset + 1
		}

		checkCharacters()
		error("The character at $offset is not reported by the check of every character")
	}

	/**
	Skips valid string or comment content up to a line feed, either special ASCII character, or `end`. `NONE` matches no character.
	*/
	internal fun skipContent(start: Int, end: Int, first: Char, second: Char): Int {
		var offset = start

		while (offset < end) {
			val character = characters[offset]

			if (character.code - 0x20 in 0..<0x5F) {
				if (character == first || character == second) {
					return offset
				}
			} else if (character == '\n') {
				return offset
			} else {
				offset = checkContent(offset)
			}

			offset++
		}

		return offset
	}

	private fun failControlCharacter(offset: Int): SomlException {
		val character = characters[offset]

		if (character == '\r') {
			return fail("A carriage return (U+000D) is not allowed anywhere. Use LF line endings", offset)
		}

		return fail("A raw control character (${formatCodePoint(character.code)}) is not allowed anywhere, including in strings and comments. In a string, write it as the escape \\u{${character.code.toString(16)}} inside \"...\"", offset)
	}

	internal fun fail(reason: String, offset: Int = index): SomlException = SomlException.at(reason, source, offset)

	/**
	The UTF-16 code unit at `offset`, or -1 outside the source.
	*/
	// Note: Not using getOrNull() because nullable Char access allocates for non-ASCII on the JVM and measured slower on Native.
	internal fun code(offset: Int = index): Int = if (offset in 0..<length) characters[offset].code else -1

	internal val isAtEnd: Boolean
		get() = index >= length

	private fun parseDocument(): SomlValue {
		skipTrivia()
		documentStart = index

		if (isAtEnd) {
			throw fail("A document must contain an object or an array, but this one is empty")
		}

		val value = when (code()) {
			'{'.code -> parseObject(1)
			'['.code -> parseArray(1)
			else -> parseBareObject()
		}

		skipTrivia()

		if (!isAtEnd) {
			throw fail("Unexpected ${describeHere()} after the end of the document")
		}

		return value
	}

	/**
	`bare-object = entry ( entry-sep entry )*`, where a separator is one or more line breaks.
	*/
	private fun parseBareObject(): SomlValue {
		val members = LinkedHashMap<String, SomlValue>()
		val start = index

		try {
			parseEntry(members, 1)
		} catch (error: SomlException) {
			diagnoseBareValue(start)
			throw error
		}

		var hasLineBreak = skipTrivia()

		while (!isAtEnd) {
			if (code() == ','.code) {
				throw fail("Top-level entries are separated by line breaks, not commas. Use braces for a one-line object")
			}

			if (!hasLineBreak) {
				throw fail("Expected a line break before the next entry, but found ${describeHere()}${slashCommentHint()}")
			}

			parseEntry(members, 1)
			hasLineBreak = skipTrivia()
		}

		return SomlObject.trusted(members)
	}

	/**
	A whole document that is one scalar, such as `5` or an instant, fails as an entry. That failure is reported as what it is.
	*/
	private fun diagnoseBareValue(start: Int) {
		index = start

		val isBareValue = try {
			parseValue(1)
			skipTrivia()
			isAtEnd
		} catch (_: SomlException) {
			// Not a bare value either, so the original error stands.
			false
		}

		if (isBareValue) {
			throw fail("A bare value is not a document. A document is an object or an array, so write it as `key: value` or `[value]`", start)
		}
	}

	/**
	Skips spaces, tabs, line breaks, and comments, and reports whether a line break was crossed. A line break inside a block comment does not count.
	*/
	internal fun skipTrivia(): Boolean {
		var hasCrossedLineBreak = false

		while (index < length) {
			when (characters[index]) {
				' ', '\t' -> index++
				'\n' -> {
					hasCrossedLineBreak = true
					index++
				}
				else -> if (!skipComment()) {
					return hasCrossedLineBreak
				}
			}
		}

		return hasCrossedLineBreak
	}

	/**
	Skips the comment that starts here, or returns false when there is none.
	*/
	private fun skipComment(): Boolean {
		val code = code()

		if (code == '#'.code) {
			index = skipContent(index + 1, length, NONE, NONE)
			return true
		}

		if (code == '/'.code && code(index + 1) == '*'.code) {
			skipBlockComment()
			return true
		}

		return false
	}

	private fun skipBlockComment() {
		// Note: Not using indexOf("*/") because comment content must also be checked for controls, surrogates, and nested openers.
		val start = index
		var end = -1
		var nested = -1
		var offset = start + 2

		while (offset < length) {
			val character = characters[offset]

			if (character.code - 0x20 in 0..<0x5F) {
				if (character == '*' && offset + 1 < length && characters[offset + 1] == '/') {
					end = offset
					break
				}

				if (nested == -1 && character == '/' && offset + 1 < length && characters[offset + 1] == '*') {
					nested = offset
				}
			} else if (character != '\n') {
				offset = checkContent(offset)
			}

			offset++
		}

		if (end == -1) {
			throw fail("Unterminated block comment", start)
		}

		// The body may not contain `/*`. An opening must end before the closing `*/`, so an opening that overlaps it, as in `/*/`, is not inside the body.
		if (nested != -1 && nested + 2 <= end) {
			throw fail("Block comments cannot be nested, and their body may not contain “/*”", nested)
		}

		index = end + 2
	}

	/**
	`entry = key ":" ws value`.
	*/
	private fun parseEntry(members: MutableMap<String, SomlValue>, depth: Int) {
		val keyStart = index
		val key = parseKey()
		val code = code()

		if (code == '.'.code) {
			throw failDotInKey(keyStart)
		}

		if (code != ':'.code) {
			throw failMissingColon(keyStart)
		}

		val colon = index
		index++
		skipTrivia()

		val value = try {
			parseValue(depth + 1)
		} catch (error: SomlException) {
			diagnoseBadValue(error, key, keyStart, colon)
			throw error
		}

		if (members.put(key, value) != null) {
			throw fail("Duplicate key ${describeKey(key)}", keyStart)
		}
	}

	internal fun parseKey(): String {
		val start = index
		val code = code(start)

		if (code == '\''.code || code == '"'.code) {
			if (code(start + 1) == code && code(start + 2) == code) {
				throw fail("A block string cannot be a key")
			}

			// A key is a string, so it may hold anything a string can, including a line feed written as an escape.
			return parseString()
		}

		var end = start

		while (end < length && characters[end].code.isBareKeyCode()) {
			end++
		}

		if (end == start) {
			if (code == '['.code) {
				diagnoseTableHeader(isValue = false)
			}

			throw fail(if (isAtEnd) "Expected a key" else "Expected a key, but found ${describeHere()}${keyQuotingHint()}${slashCommentHint()}")
		}

		index = end
		return source.substring(start, end)
	}

	private fun parseObject(depth: Int): SomlValue {
		val members = LinkedHashMap<String, SomlValue>()
		parseItems('}') {
			parseEntry(members, depth)
		}
		return if (members.isEmpty()) SomlObject.Empty else SomlObject.trusted(members)
	}

	private fun parseArray(depth: Int): SomlValue {
		val items = ArrayList<SomlValue>()
		parseItems(']') {
			// An array collects its values instead of object members.
			items.add(parseValue(depth + 1))
		}
		return if (items.isEmpty()) SomlArray.Empty else SomlArray.trusted(items)
	}

	/**
	`{` or `[`, then items separated by a comma, a line break, or both, with an optional trailing comma, then the `closing` bracket. A comma must be on the line of the item before it. `parseItem` consumes one member or array item.
	*/
	private inline fun parseItems(closing: Char, parseItem: () -> Unit) {
		val start = index
		index++
		skipTrivia()

		while (code() != closing.code) {
			var hasLineBreak = false
			var itemEnd = index

			if (!isAtEnd) {
				parseItem()

				itemEnd = index
				hasLineBreak = skipTrivia()
			}

			if (isAtEnd) {
				throw fail("Unterminated ${if (closing == '}') "object" else "array"}: expected “$closing”", start)
			}

			if (code() == ','.code) {
				if (hasLineBreak) {
					throw fail("A comma must be on the same line as the item before it. The line break already separates the items, so remove the comma")
				}

				index++
				skipTrivia()
			} else if (!hasLineBreak && code() != closing.code) {
				val item = if (closing == '}') "an object member" else "an array item"
				// Without a line break that separates, a line break in the gap is inside a block comment.
				val hint = if (indexOf('\n', itemEnd, index) != -1) ". A line break inside a block comment does not separate items" else slashCommentHint()
				throw fail("Expected “,”, a line break, or “$closing” after $item, but found ${describeHere()}$hint")
			}
		}

		index++
	}

	internal fun parseValue(depth: Int): SomlValue {
		val code = code()

		if (depth > MAX_DEPTH && (code == '{'.code || code == '['.code)) {
			throw fail("The document is nested more than $MAX_DEPTH levels deep")
		}

		if (code == '{'.code) {
			return parseObject(depth)
		}

		if (code == '['.code) {
			return parseArray(depth)
		}

		val start = index
		// An int or a float written with digits.
		var isNumber = false

		val value: SomlValue = when (code) {
			'\''.code, '"'.code -> SomlString.trusted(parseString())
			't'.code if isKeyword("true") -> SomlBool.True
			'f'.code if isKeyword("false") -> SomlBool.False
			'n'.code if isKeyword("null") -> SomlNull
			'i'.code if isKeyword("infinity") -> SomlFloat(Double.POSITIVE_INFINITY)
			'-'.code if isKeyword("-infinity") -> SomlFloat(Double.NEGATIVE_INFINITY)
			'-'.code, in '0'.code..'9'.code -> parseNumberOrTemporal().also {
				isNumber = it is SomlInt || it is SomlFloat
			}
			else -> throw failUnexpectedValue()
		}

		if (!isValueEnd(code())) {
			throw failAfterValue(start, isNumber)
		}

		if (isNumber && code().isSpaceCode()) {
			diagnoseUnitAfterSpace(start)
		}

		return value
	}

	private fun isKeyword(word: String): Boolean {
		if (!source.startsWith(word, index)) {
			return false
		}

		// A longer word, such as `nullable`, is not the keyword.
		if (code(index + word.length).isBareKeyCode()) {
			return false
		}

		index += word.length
		return true
	}

	internal fun parseString(): String {
		val code = code()

		if (code(index + 1) == code && code(index + 2) == code) {
			return parseBlockString(code.toChar())
		}

		return if (code == '\''.code) parseLiteralString() else parseEscapedString(skipContent(index + 1, length, '"', '\\'))
	}

	/**
	`'...'` has no escapes, so its value is the source text between the quotes.
	*/
	private fun parseLiteralString(): String {
		// Note: Not using concatToString() because it added allocation for empty strings and measured slower for ASCII copies.
		val start = index
		val end = skipContent(start + 1, length, '\'', '\'')

		if (end >= length || characters[end] == '\n') {
			throw failUnterminatedString(start)
		}

		index = end + 1
		return source.substring(start + 1, end)
	}

	internal fun failUnterminatedString(start: Int): SomlException {
		val reason = if (characters[start] == '\'') {
			"Unterminated string. A '...' string must end on the line it starts on; use a block string (''') for multiple lines"
		} else {
			"Unterminated string. A \"...\" string must end on the line it starts on; use a block string (\"\"\") for multiple lines"
		}

		return fail(reason, start)
	}

	/**
	Decodes an escaped string that starts at `index`, from its first quote or backslash after the opening quote, `special`, which is already found.
	*/
	private fun parseEscapedString(firstSpecial: Int): String {
		val start = index
		var chunkStart = start + 1
		var special = firstSpecial
		var value: StringBuilder? = null

		while (true) {
			if (special >= length || characters[special] == '\n') {
				throw failUnterminatedString(start)
			}

			if (characters[special] == '"') {
				index = special + 1

				if (value == null) {
					return source.substring(chunkStart, special)
				}

				return value.appendRange(characters, chunkStart, special).toString()
			}

			val builder = value ?: StringBuilder(minOf(8192, special - start + 16)).also { value = it }
			builder.appendRange(characters, chunkStart, special)
			chunkStart = parseEscape(special, builder)
			special = skipContent(chunkStart, length, '"', '\\')
		}
	}

	/**
	Decodes the escape at `offset`, which is a backslash, into `value`, and returns the offset after it.
	*/
	private fun parseEscape(offset: Int, value: StringBuilder): Int {
		when (val character = code(offset + 1)) {
			'\\'.code, '"'.code -> {
				value.append(character.toChar())
				return offset + 2
			}
			'n'.code -> {
				value.append('\n')
				return offset + 2
			}
			't'.code -> {
				value.append('\t')
				return offset + 2
			}
			'u'.code -> {
				val hexStart = offset + 3
				var hexEnd = hexStart

				// Uppercase digits too, so that the value of such an escape is checked before its spelling.
				if (code(offset + 2) == '{'.code) {
					while (hexEnd < hexStart + 6 && code(hexEnd).isHexDigitCode()) {
						hexEnd++
					}
				}

				if (hexEnd == hexStart || code(hexEnd) != '}'.code) {
					throw fail(describeBadUnicodeEscape(offset + 1), offset)
				}

				val digits = source.substring(hexStart, hexEnd)
				// Note: Not using hexToInt() because its public API still requires a substring here and measured no material improvement.
				val codePoint = digits.toInt(16)

				// The value is checked before the spelling, so that the uppercase and leading zeros errors never point to an escape that is not allowed either.
				if (codePoint == 0x0D) {
					throw fail("A carriage return (U+000D) cannot be represented, so \\u{$digits} is not allowed", offset)
				}

				if (codePoint in Char.MIN_SURROGATE.code..Char.MAX_SURROGATE.code) {
					throw fail("\\u{$digits} is a surrogate, which is not a Unicode scalar value", offset)
				}

				if (codePoint > MAX_CODE_POINT) {
					throw fail("\\u{$digits} is above U+10FFFF, the largest Unicode scalar value", offset)
				}

				for (position in hexStart..<hexEnd) {
					if (characters[position] in 'A'..'F') {
						throw fail("A Unicode escape uses lowercase hexadecimal digits", offset)
					}
				}

				if (hexEnd - hexStart > 1 && characters[hexStart] == '0') {
					throw fail("A Unicode escape may not have leading zeros; write \\u{${codePoint.toString(16)}}", offset)
				}

				value.appendCodePointOf(codePoint)
				return hexEnd + 1
			}
			else -> {
				// A backslash at the end of the document is the same mistake as one at the end of a line.
				val reason = when (character) {
					'r'.code -> "There is no \\r escape, because a carriage return cannot be represented"
					'\''.code -> "A ' needs no escape inside \"...\""
					'\n'.code, -1 -> "A backslash must be followed by an escape character. Use \\\\ for a literal backslash, or a '...' string"
					else -> "Unknown escape “\\${codePointToString(source.codePointAtOffset(offset + 1))}”. The escapes are \\\\, \\\", \\n, \\t, and \\u{…}; use a '...' string for literal backslashes"
				}

				throw fail(reason, offset)
			}
		}
	}

	/**
	A block string opens with a run of three or more quotes and a line break, and closes at the first line whose first non-whitespace content is a run of exactly that many quotes. The closing line's indentation is removed from every content line, except a blank one, which holds only spaces and tabs and becomes an empty line.
	*/
	private fun parseBlockString(quote: Char): String {
		// Note: Not using trimIndent() because block strings remove the closing delimiter's exact indentation.
		// Note: Not using isBlank() because non-ASCII spaces are block-string content, while only spaces and tabs make a blank line.
		val start = index
		var delimiterLength = 0

		while (code(start + delimiterLength) == quote.code) {
			delimiterLength++
		}

		index = start + delimiterLength

		if (isAtEnd) {
			throw fail("Unterminated block string", start)
		}

		// As in Swift, the opening delimiter is followed directly by a line break, not even by trailing whitespace.
		if (code() != '\n'.code) {
			throw fail("A block string's opening delimiter must be followed directly by a line break, and its content starts on the next line")
		}

		val contentStart = index + 1
		var closingLineStart = -1
		var closingDelimiterStart = -1
		var lineStart = contentStart

		while (lineStart < length) {
			val delimiterStart = skipSpaces(lineStart)
			var runLength = 0

			while (code(delimiterStart + runLength) == quote.code) {
				runLength++
			}

			if (runLength == delimiterLength) {
				closingLineStart = lineStart
				closingDelimiterStart = delimiterStart
				break
			}

			lineStart = findLineEnd(lineStart) + 1
		}

		if (closingLineStart == -1) {
			throw fail("Unterminated block string${describeInlineClosingDelimiter(contentStart, quote, delimiterLength)}", start)
		}
		val indentationLength = closingDelimiterStart - closingLineStart
		index = closingDelimiterStart + delimiterLength
		// The first and last lines that are not blank, without an offset kept for every line. A blank line becomes an empty line.
		var firstContentStart = -1
		var lastContentEnd = -1
		lineStart = contentStart

		while (lineStart < closingLineStart) {
			val lineEnd = skipContent(lineStart, closingLineStart, NONE, NONE)

			// A blank line, which is empty or holds only spaces and tabs, may leave out the indentation. Swift is stricter here: there only a completely empty line may leave it out.
			if (skipSpaces(lineStart) < lineEnd) {
				if (!hasIndentation(lineStart, closingLineStart, indentationLength)) {
					throw fail("This line does not start with the indentation of its block string's closing delimiter. Every line except a blank one must start with exactly the same spaces and tabs", lineStart)
				}

				if (firstContentStart == -1) {
					firstContentStart = lineStart
				}

				lastContentEnd = lineEnd
			}

			lineStart = lineEnd + 1
		}

		if (firstContentStart == -1) {
			return ""
		}

		// Blank lines directly after the opening delimiter and directly before the closing one are not content.
		val value = StringBuilder(minOf(8192, lastContentEnd - firstContentStart))
		lineStart = firstContentStart

		while (lineStart < lastContentEnd) {
			val lineEnd = findLineEnd(lineStart)

			if (lineStart > firstContentStart) {
				value.append('\n')
			}

			if (skipSpaces(lineStart) < lineEnd) {
				val textStart = lineStart + indentationLength

				if (quote == '\'') {
					value.appendRange(characters, textStart, lineEnd)
				} else {
					unescapeLine(textStart, lineEnd, value)
				}
			}

			lineStart = lineEnd + 1
		}

		return value.toString()
	}

	/**
	Whether the line at `lineStart` starts with the `length` characters of indentation at `indentationStart`.
	*/
	private fun hasIndentation(lineStart: Int, indentationStart: Int, length: Int): Boolean {
		// Note: Not using regionMatches() because the character-array loop measured faster for typical short indentation.
		// Note: Not using CharArray.contentEquals() because comparing indentation ranges requires copied arrays in common code.
		if (lineStart + length > this.length) {
			return false
		}

		for (offset in 0..<length) {
			if (characters[lineStart + offset] != characters[indentationStart + offset]) {
				return false
			}
		}

		return true
	}

	/**
	Decodes the escapes of one content line of a `"""` block. The search for a backslash stops at the end of the line, so that a document with many lines stays linear.
	*/
	private fun unescapeLine(lineStart: Int, lineEnd: Int, value: StringBuilder) {
		var chunkStart = lineStart
		var offset = indexOf('\\', chunkStart, lineEnd)

		while (offset != -1) {
			value.appendRange(characters, chunkStart, offset)
			chunkStart = parseEscape(offset, value)
			offset = indexOf('\\', chunkStart, lineEnd)
		}

		value.appendRange(characters, chunkStart, lineEnd)
	}

	private fun parseNumberOrTemporal(): SomlValue {
		var plain = parsePlainNumber()

		if (plain == null && code().isDigitCode() && code(index + 4) == '-'.code) {
			plain = parsePlainInstant()
		}

		if (plain != null) {
			return plain
		}

		val start = index
		val end = findNumberEnd(start)
		val text = source.substring(start, end)
		index = end

		if (hasDatePrefix(text)) {
			// A date and time separated by a space retain the instant separator diagnostic. A following ordinary value gets the collection's missing-separator error.
			val hasTime = code() == ' '.code && source.hasHourMinuteAt(index + 1)

			if (text.length == 10 && !hasTime) {
				return parseDate(text, start)
			}

			return parseInstant(text, start)
		}

		val kind = classifyNumber(text)

		if (kind == NumberKind.None && isDurationLike(text)) {
			return parseDuration(text, start)
		}

		return when (kind) {
			NumberKind.Int -> {
				if (text == "-0") {
					throw fail("“-0” is not allowed, because zero has one spelling: 0", start)
				}

				SomlInt(parseInteger(text, start))
			}
			NumberKind.Float -> {
				// The grammar is checked already, so the conversion never gets a `+`, `NaN`, `Infinity`, a hexadecimal float, or a suffix, which some platforms accept.
				// Note: Not using filterNot() because it allocates for underscore-free numbers and measured much slower on that common path.
				val value = parseFloat(text.replace("_", ""))

				if (value.isInfinite()) {
					throw fail("${abbreviate(text)} is too large to be a finite float. Use ${if (value < 0) "-infinity" else "infinity"} if you mean it", start)
				}

				if (value == 0.0) {
					// A nonzero digit before the exponent means the literal is not zero, so it underflowed.
					// Note: Not using substringBefore() because copying the mantissa adds allocation to this zero check.
					val exponent = text.indexOf('e')
					val mantissaEnd = if (exponent == -1) text.length else exponent

					for (offset in 0..<mantissaEnd) {
						if (text[offset] in '1'..'9') {
							throw fail("${abbreviate(text)} is too small to be told apart from zero. Write 0.0 if you mean zero", start)
						}
					}
				}

				SomlFloat(value)
			}
			NumberKind.None -> throw fail(describeThousandsSeparator(text, start) ?: describeBadNumber(text, unquotedText(start)), start)
		}
	}

	/**
	The common case, a short decimal int or float such as `8080`, `-3`, or `30.5`, without the general path's maximal-run scan and classification. Anything else, including every error, returns null and is left to the general path.
	*/
	private fun parsePlainNumber(): SomlValue? {
		// Note: Not using toLongOrNull() or toDouble() here because reparsing token substrings measured slower than scanning ordinary numbers directly.
		val start = index
		var offset = if (code(start) == '-'.code) start + 1 else start
		val integerStart = offset
		var significand = 0L

		while (code(offset).isDigitCode()) {
			if (offset - integerStart == 18) {
				return null
			}

			significand = (significand * 10) + (characters[offset] - '0')
			offset++
		}

		val integerLength = offset - integerStart

		// No digits, or a leading zero followed by more digits, which the general path reports as an error or reads as an instant before the year 1000.
		if (integerLength == 0 || (integerLength > 1 && code(integerStart) == '0'.code)) {
			return null
		}

		var isFloat = false
		var fractionDigits = 0

		if (code(offset) == '.'.code) {
			val fractionStart = offset + 1
			offset = fractionStart

			while (code(offset).isDigitCode()) {
				if (offset - integerStart >= 15) {
					return null
				}

				significand = (significand * 10) + (characters[offset] - '0')
				offset++
			}

			if (offset == fractionStart) {
				return null
			}

			isFloat = true
			fractionDigits = offset - fractionStart
		}

		// At most 18 integer digits fit a `Long`. A float has at most 15 characters including its point, so it needs no slow path. A character that may continue a number, such as `e`, `_`, or `-`, needs the general path.
		if (!isValueEnd(code(offset))) {
			return null
		}

		if (isFloat) {
			// Clinger's fast path: at most 14 digits fit a double exactly, and so does a power of ten up to 10^22, so one division, which IEEE 754 rounds correctly, gives the correctly rounded value of the decimal. The limit of 15 above counts the `.`, so a float here has at most 14 digits.
			val value = significand / POWERS_OF_TEN[fractionDigits]
			index = offset
			return SomlFloat(if (start == integerStart) value else -value)
		}

		// `-0` is an error, which the general path reports.
		if (significand == 0L && offset - start > 1) {
			return null
		}

		index = offset
		return SomlInt(if (start == integerStart) significand else -significand)
	}

	/**
	The common instant, as `2026-09-19T14:00:00Z`, `2026-09-19T14:00:00.5Z`, or with an offset such as `+02:00`, read from the characters without the general path's token and parts. Anything else, including every error, returns null and is left to the general path, which reports it.
	*/
	private fun parsePlainInstant(): SomlValue? {
		val start = index

		// The shortest instant, as `2026-09-19T14:00:00Z`, has 20 characters, so the fixed fields below are inside the document.
		if (start + 20 > length || characters[start + 4] != '-' || characters[start + 7] != '-' || characters[start + 10] != 'T' || characters[start + 13] != ':' || characters[start + 16] != ':') {
			return null
		}

		val year = source.decimalDigits(start, 4)
		val month = source.decimalDigits(start + 5, 2)
		val day = source.decimalDigits(start + 8, 2)
		val hour = source.decimalDigits(start + 11, 2)
		val minute = source.decimalDigits(start + 14, 2)
		val second = source.decimalDigits(start + 17, 2)

		if (SomlDate.calendarError(year, month, day) != null || hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
			return null
		}

		var offset = start + 19
		var nanoseconds = 0

		if (code(offset) == '.'.code) {
			val fractionStart = offset + 1
			offset = fractionStart

			while (offset < length && characters[offset].code.isDigitCode()) {
				offset++
			}

			val count = offset - fractionStart

			if (count == 0 || count > 9) {
				return null
			}

			nanoseconds = source.decimalDigits(fractionStart, count) * POWERS_OF_TEN[9 - count].toInt()
		}

		var offsetSeconds = 0

		if (code(offset) == 'Z'.code) {
			offset++
		} else if ((code(offset) == '+'.code || code(offset) == '-'.code) && offset + 6 <= length && characters[offset + 3] == ':') {
			val offsetHour = source.decimalDigits(offset + 1, 2)
			val offsetMinute = source.decimalDigits(offset + 4, 2)
			val isNegative = characters[offset] == '-'

			if (offsetHour !in 0..23 || offsetMinute !in 0..59 || (isNegative && offsetHour == 0 && offsetMinute == 0)) {
				return null
			}

			offsetSeconds = ((offsetHour * 3600) + (offsetMinute * 60)) * (if (isNegative) -1 else 1)
			offset += 6
		} else {
			return null
		}

		// The general path reads the longest run of number characters, so an instant that one follows is its error to report.
		if (code(offset).isNumberCode()) {
			return null
		}

		val seconds = (daysFromCivil(year, month, day) * 86_400L) + (hour * 3600L) + (minute * 60L) + second - offsetSeconds
		val instant = Instant.fromEpochSeconds(seconds, nanoseconds)

		if (instant !in SomlInstant.MIN..SomlInstant.MAX) {
			return null
		}

		index = offset
		return SomlInstant(instant)
	}

	/**
	The grammar has already been checked. Radix prefixes and their leading zeros are removed before underscores, so even a huge integer needs only a bounded temporary string or error message.
	*/
	private fun parseInteger(text: String, start: Int): Long {
		// The grammar is checked already, so only a radix int can start with zero and contain more characters. A decimal zero has one spelling, and a radix int has no sign.
		val isRadix = text.length > 1 && text[0] == '0'
		var radix = 10
		var digitsStart = 0
		var maximumDigits = 19

		if (isRadix) {
			radix = when (text[1]) {
				'x' -> 16
				'o' -> 8
				else -> 2
			}
			maximumDigits = when (radix) {
				16 -> 16
				8 -> 21
				else -> 63
			}
			digitsStart = 2

			while (digitsStart < text.length && (text[digitsStart] == '0' || text[digitsStart] == '_')) {
				digitsStart++
			}

			if (digitsStart == text.length) {
				return 0
			}
		}

		// Single underscores can at most double the digit count minus one. The decimal sign adds one character.
		if (text.length - digitsStart > maximumDigits * 2 - (if (text[0] == '-') 0 else 1)) {
			throw failIntegerRange(text, start)
		}

		val digits = text.substring(digitsStart).replace("_", "")

		// Read as a signed number, so a 64-bit pattern with the top bit set, such as `0xFFFFFFFFFFFFFFFF`, is out of range rather than a negative int, as the spec requires.
		return digits.toLongOrNull(radix) ?: throw failIntegerRange(text, start)
	}

	private fun failIntegerRange(text: String, start: Int): SomlException = fail("The integer ${abbreviate(text)} is outside the 64-bit range (-9223372036854775808 to 9223372036854775807)", start)

	/**
	A whole ASCII date token, validated without a time or timezone.
	*/
	private fun parseDate(text: String, start: Int): SomlValue {
		val year = text.decimalDigits(0, 4)
		val month = text.decimalDigits(5, 2)
		val day = text.decimalDigits(8, 2)
		val problem = SomlDate.calendarError(year, month, day)

		if (problem != null) {
			throw fail("Invalid date $text: $problem", start)
		}

		return SomlDate(year, month, day)
	}

	private fun parseInstant(text: String, start: Int): SomlValue {
		// Note: Not using Instant.parse() because it rejects SOML offsets beyond ±18 hours and accepts offset spellings that SOML forbids.
		val fields = if (text.length > MAX_DIAGNOSED_LENGTH) null else InstantFields.match(text)

		if (fields == null) {
			// A date, a space, and a time, as TOML and Python write a date and time.
			val timeStart = index + 1
			val end = if (code() == ' '.code && code(timeStart).isDigitCode()) findNumberEnd(timeStart) else index
			val time = if (end > timeStart) source.substring(timeStart, end) else ""
			// More of the instant may follow, as in `14:00:00,5Z` or `14:00:00[Europe/Oslo]`, so it may have an offset.
			val isWholeValue = isValueEndAt(end) && !(code(end) == ','.code && code(end + 1).isDigitCode())
			throw fail(describeBadInstant(text, time, isWholeValue, findOffsetAfterSpace()), start)
		}

		fun check(isValid: Boolean, reason: String) {
			if (!isValid) {
				throw fail("Invalid instant ${abbreviate(text)}: $reason", start)
			}
		}

		check(fields.fraction == null || fields.fraction.length <= 9, "a fractional second has at most nine digits")
		val calendarError = SomlDate.calendarError(fields.year, fields.month, fields.day)
		if (calendarError != null) {
			throw fail("Invalid instant ${abbreviate(text)}: $calendarError", start)
		}
		check(fields.hour <= 23, "the hour must be 00 to 23")
		check(fields.minute <= 59, "the minute must be 00 to 59")
		check(fields.second <= 59, "the second must be 00 to 59, and a leap second is not representable")

		if (!fields.isUtc) {
			check(fields.offsetHour <= 23, "the offset hour must be 00 to 23")
			check(fields.offsetMinute <= 59, "the offset minute must be 00 to 59")
			check(!(fields.isOffsetNegative && fields.offsetHour == 0 && fields.offsetMinute == 0), "-00:00 means “offset unknown” in RFC 3339, which is not representable; use Z or +00:00")
		}

		val offsetSeconds = (fields.offsetHour * 3600L) + (fields.offsetMinute * 60L)
		val seconds = (daysFromCivil(fields.year, fields.month, fields.day) * 86_400L) + (fields.hour * 3600L) + (fields.minute * 60L) + fields.second - (if (fields.isOffsetNegative) -offsetSeconds else offsetSeconds)
		val fraction = fields.fraction ?: ""
		val nanoseconds = if (fraction.isEmpty()) 0 else fraction.toInt() * POWERS_OF_TEN[9 - fraction.length].toInt()
		val instant = Instant.fromEpochSeconds(seconds, nanoseconds)
		check(instant in SomlInstant.MIN..SomlInstant.MAX, "in UTC it falls outside the years 0001 to 9999")
		return SomlInstant(instant)
	}

	/**
	The parts of a token that matches the instant grammar lexically, before the ranges are checked.
	*/
	private class InstantFields(
		val year: Int,
		val month: Int,
		val day: Int,
		val hour: Int,
		val minute: Int,
		val second: Int,
		val fraction: String?,
		val isUtc: Boolean,
		val isOffsetNegative: Boolean,
		val offsetHour: Int,
		val offsetMinute: Int,
	) {
		companion object {
			/**
			`dddd-dd-ddTdd:dd:dd(.d+)?(Z|[+-]dd:dd)`, or null.
			*/
			fun match(text: String): InstantFields? {
				// Note: Not using Regex.matchEntire() with captures because it adds allocations and measured slower for instant range errors.
				if (text.length < 20 || !hasDatePrefix(text) || text[10] != 'T' || !isDigits(text, 11, 13) || text[13] != ':' || !isDigits(text, 14, 16) || text[16] != ':' || !isDigits(text, 17, 19)) {
					return null
				}

				var offset = 19
				var fraction: String? = null

				if (text[offset] == '.') {
					var fractionEnd = offset + 1

					while (fractionEnd < text.length && text[fractionEnd].code.isDigitCode()) {
						fractionEnd++
					}

					if (fractionEnd == offset + 1) {
						return null
					}

					fraction = text.substring(offset + 1, fractionEnd)
					offset = fractionEnd
				}

				val year = text.decimalDigits(0, 4)
				val month = text.decimalDigits(5, 2)
				val day = text.decimalDigits(8, 2)
				val hour = text.decimalDigits(11, 2)
				val minute = text.decimalDigits(14, 2)
				val second = text.decimalDigits(17, 2)

				if (offset == text.length - 1 && text[offset] == 'Z') {
					return InstantFields(year, month, day, hour, minute, second, fraction, isUtc = true, isOffsetNegative = false, offsetHour = 0, offsetMinute = 0)
				}

				val sign = if (offset < text.length) text[offset] else NONE

				if (offset + 6 != text.length || (sign != '+' && sign != '-') || !isDigits(text, offset + 1, offset + 3) || text[offset + 3] != ':' || !isDigits(text, offset + 4, offset + 6)) {
					return null
				}

				val offsetHour = text.decimalDigits(offset + 1, 2)
				val offsetMinute = text.decimalDigits(offset + 4, 2)
				return InstantFields(year, month, day, hour, minute, second, fraction, isUtc = false, isOffsetNegative = sign == '-', offsetHour = offsetHour, offsetMinute = offsetMinute)
			}
		}
	}

	/**
	A scan of the parts in order, so that each error names the part it is about. The total is counted as an unsigned magnitude, so that the most negative duration fits.
	*/
	private fun parseDuration(text: String, start: Int): SomlValue {
		// Note: Not using Duration.parse() because its syntax and precision differ from SOML's exact nanosecond grammar.
		val isNegative = text[0] == '-'
		var offset = if (isNegative) 1 else 0
		var previousUnit: DurationUnit? = null
		var total = 0L
		var isOutOfRange = false
		// The fraction of the last part, and that part's unit size. Only the last part may have one, so the value checks wait until every part is read.
		var fractionStart = 0
		var fractionEnd = 0
		var size = 0L

		while (offset < text.length) {
			val digitsEnd = skipIntegerPart(text, offset)
			val next = if (digitsEnd < text.length) text[digitsEnd].code else -1

			if (digitsEnd == offset) {
				val code = text[offset]

				if (code == '-' && offset + 1 < text.length && text[offset + 1].code.isDigitCode()) {
					throw failDuration(text, start, "only the whole duration takes a sign, as in -1h30m")
				}

				throw failDuration(text, start, if (code == '+') "a “+” sign is not allowed" else "expected a number at “${abbreviate(text.substring(offset, minOf(text.length, offset + 11)), 10)}”")
			}

			if (text[offset] == '0' && (next == '_'.code || next.isDigitCode())) {
				throw failDuration(text, start, "leading zeros are not allowed")
			}

			if (next == '_'.code) {
				throw failDuration(text, start, "an underscore must be between two digits")
			}

			fractionStart = 0
			fractionEnd = 0
			var unitStart = digitsEnd

			if (next == '.'.code) {
				unitStart = skipDigits(text, digitsEnd + 1)

				if (unitStart == digitsEnd + 1) {
					throw failDuration(text, start, "a “.” must be followed by a digit")
				}

				if (unitStart < text.length && text[unitStart] == '_') {
					throw failDuration(text, start, "an underscore must be between two digits")
				}

				// Trailing zeros change nothing, and leaving them out keeps the arithmetic small however many there are.
				fractionStart = digitsEnd + 1
				fractionEnd = unitStart

				while (fractionEnd > digitsEnd + 1 && (text[fractionEnd - 1] == '0' || text[fractionEnd - 1] == '_')) {
					fractionEnd--
				}
			}

			var unitEnd = unitStart

			while (unitEnd < text.length && text[unitEnd].code.isLetterCode()) {
				unitEnd++
			}

			val unit = DurationUnit.parse(text, unitStart, unitEnd) ?: throw failDuration(text, start, describeBadDurationUnit(text.substring(unitStart, unitEnd), text))

			// Comparing with the previous unit enforces both the descending order and that each unit appears at most once.
			if (previousUnit != null && unit.nanoseconds >= previousUnit.nanoseconds) {
				throw failDuration(text, start, "the units are in the order h, m, s, ms, us, ns, and each appears at most once")
			}

			if (next == '.'.code && unitEnd < text.length && text[unitEnd].code.isDigitCode()) {
				throw failDuration(text, start, "only the last part may have a fraction")
			}

			size = unit.nanoseconds
			// Nineteen digits with single underscores occupy at most 37 characters. Longer parts are already out of range.
			val digits = if (digitsEnd - offset <= 37) text.substring(offset, digitsEnd).replace("_", "") else ""

			// More than 19 digits is out of range in any unit. Up to 19 digits fit an unsigned `Long`.
			if (digits.isEmpty() || digits.length > 19) {
				isOutOfRange = true
			} else {
				val number = digits.toULong()
				val product = number.toLong() * size

				// The product overflows 64 unsigned bits when the number is larger than the largest number that the unit size fits into them.
				if (number != 0UL && size.toULong() > ULong.MAX_VALUE / number) {
					isOutOfRange = true
				}

				val sum = total + product

				if (sum.toULong() < total.toULong()) {
					isOutOfRange = true
				}

				total = sum
			}

			offset = unitEnd
			previousUnit = unit
		}

		// More than 13 fraction digits without a trailing zero is finer than a nanosecond in any unit.
		if (fractionEnd - fractionStart > 25) {
			throw failDuration(text, start, "it is not a whole number of nanoseconds")
		}

		val fraction = text.substring(fractionStart, fractionEnd).replace("_", "")

		if (fraction.length > 13) {
			throw failDuration(text, start, "it is not a whole number of nanoseconds")
		}

		if (fraction.isNotEmpty()) {
			val scale = POWERS_OF_TEN[fraction.length].toLong()
			var divisor = scale
			var remainder = size

			while (remainder != 0L) {
				val next = divisor % remainder
				divisor = remainder
				remainder = next
			}

			val number = fraction.toLong()
			val denominator = scale / divisor

			if (number % denominator != 0L) {
				throw failDuration(text, start, "it is not a whole number of nanoseconds")
			}

			// The quotient is below the unit size, so it fits. It is reduced before the multiplication to keep every intermediate value in a `Long`.
			val sum = total + (number / denominator) * (size / divisor)

			if (sum.toULong() < total.toULong()) {
				isOutOfRange = true
			}

			total = sum
		}

		if (isNegative && total == 0L && !isOutOfRange) {
			throw failDuration(text, start, "“-” is not allowed before zero, because zero has one spelling: 0s")
		}

		// The limit is 2^63 for a negative duration and 2^63 - 1 for a positive one, as unsigned numbers.
		if (isOutOfRange || total.toULong() > (if (isNegative) Long.MIN_VALUE else Long.MAX_VALUE).toULong()) {
			throw failDuration(text, start, "it is outside the 64-bit range of nanoseconds, about 292 years either way")
		}

		return SomlDuration(if (isNegative) -total else total)
	}

	internal enum class DurationUnit(val nanoseconds: Long) {
		Hours(3_600_000_000_000L),
		Minutes(60_000_000_000L),
		Seconds(1_000_000_000L),
		Milliseconds(1_000_000L),
		Microseconds(1000L),
		Nanoseconds(1L);

		companion object {
			/**
			The unit from `start` to `end` in `text`, or null. It reads the characters in place, so that no part of a duration allocates a string.
			*/
			fun parse(text: String, start: Int, end: Int): DurationUnit? {
				// Note: Not using entries.firstOrNull() because the direct unit switch measured faster than searching enum entries.
				if (end - start == 1) {
					return when (text[start]) {
						'h' -> Hours
						'm' -> Minutes
						's' -> Seconds
						else -> null
					}
				}

				if (end - start == 2 && text[start + 1] == 's') {
					return when (text[start]) {
						'm' -> Milliseconds
						'u' -> Microseconds
						'n' -> Nanoseconds
						else -> null
					}
				}

				return null
			}
		}
	}

	internal fun failDuration(text: String, start: Int, reason: String): SomlException = fail("Invalid duration ${abbreviate(text)}: $reason", start)

	/**
	The end of the number, instant, or duration token that starts at `start`: a maximal run of letters, digits, and `_.:+-`.
	*/
	internal fun findNumberEnd(start: Int): Int {
		var end = start + 1

		while (code(end).isNumberCode()) {
			end++
		}

		return end
	}

	internal fun findLineEnd(offset: Int): Int {
		val end = source.indexOf('\n', offset)
		return if (end == -1) length else end
	}

	internal fun skipSpaces(start: Int): Int {
		var offset = start

		while (code(offset).isSpaceCode()) {
			offset++
		}

		return offset
	}

	internal fun skipSpacesBack(start: Int): Int {
		var offset = start

		while (offset > 0 && code(offset - 1).isSpaceCode()) {
			offset--
		}

		return offset
	}

	/**
	Whether a value ends at `offset`, so that a suggestion that ends there leaves nothing out. A `/` only starts a comment with a `*` after it, or a `//` comment of another language, which the error for what follows names, so in `km/h` the text goes on.
	*/
	internal fun isValueEndAt(offset: Int): Boolean {
		val code = code(offset)
		val next = code(offset + 1)
		return isValueEnd(code) && (code != '/'.code || next == '*'.code || next == '/'.code)
	}

	/**
	The first offset of `character` from `start` to before `end`, or -1. Only that range of the text is searched.
	*/
	internal fun indexOf(character: Char, start: Int, end: Int): Int = source.indexOfWithin(character, maxOf(start, 0), minOf(end, length))

	internal enum class NumberKind {
		Int,
		Float,
		None,
	}
}

/**
Whether a code unit may directly follow a scalar value: whitespace, a separator, a closing bracket, a comment, or the end.
*/
// Note: Not using contains() because direct separator branches measured faster on Native.
internal fun isValueEnd(code: Int): Boolean = when (code) {
	-1, ' '.code, '\t'.code, '\n'.code, ','.code, ']'.code, '}'.code, '#'.code, '/'.code -> true
	else -> false
}

internal fun Int.isHexDigitCode(): Boolean = isDigitCode() || this in 'A'.code..'F'.code || this in 'a'.code..'f'.code

internal fun isDigits(text: String, start: Int, end: Int): Boolean {
	if (end > text.length) {
		return false
	}

	for (offset in start..<end) {
		if (!text[offset].code.isDigitCode()) {
			return false
		}
	}

	return true
}

// Note: Not using Regex.matchesAt() because it allocates a matcher and measured slower than these fixed-width checks.
internal fun hasDatePrefix(text: String): Boolean = text.length >= 10 && isDigits(text, 0, 4) && text[4] == '-' && isDigits(text, 5, 7) && text[7] == '-' && isDigits(text, 8, 10)

/**
Whether an `HH:MM` starts at `offset`: two digits, a colon, and two digits. What follows may be anything.
*/
internal fun String.hasHourMinuteAt(offset: Int): Boolean = offset + 5 <= length && decimalDigits(offset, 2) != -1 && this[offset + 2] == ':' && decimalDigits(offset + 3, 2) != -1

/**
Whether `text` is an int, in any radix, or a float, following the grammar. A run of digits may have single underscores between digits.
*/
internal fun classifyNumber(text: String): Parser.NumberKind {
	if (text.length > 1 && text[0] == '0' && (text[1] == 'x' || text[1] == 'o' || text[1] == 'b')) {
		val end = skipRadixDigits(text, 2, text[1])
		return if (end > 2 && end == text.length) Parser.NumberKind.Int else Parser.NumberKind.None
	}

	var offset = if (text[0] == '-') 1 else 0
	// The integer part is `0` or has no leading zero.
	val integerEnd = skipIntegerPart(text, offset)

	if (integerEnd == offset) {
		return Parser.NumberKind.None
	}

	offset = integerEnd
	var kind = Parser.NumberKind.Int

	if (offset < text.length && text[offset] == '.') {
		val end = skipDigits(text, offset + 1)

		if (end == offset + 1) {
			return Parser.NumberKind.None
		}

		offset = end
		kind = Parser.NumberKind.Float
	}

	if (offset < text.length && text[offset] == 'e') {
		val isNegative = offset + 1 < text.length && text[offset + 1] == '-'
		val exponentStart = offset + (if (isNegative) 2 else 1)
		// The exponent follows the same leading-zero rule as the integer part, and its zero has one spelling, `e0`, so `e-0` is not an exponent.
		val end = if (isNegative && exponentStart < text.length && text[exponentStart] == '0') exponentStart else skipIntegerPart(text, exponentStart)

		if (end == exponentStart) {
			return Parser.NumberKind.None
		}

		offset = end
		kind = Parser.NumberKind.Float
	}

	return if (offset == text.length) kind else Parser.NumberKind.None
}

// Note: Not using digitToIntOrNull() because it accepts lowercase hexadecimal letters and Unicode digits outside this grammar.
private fun isRadixDigit(code: Int, radix: Char): Boolean = when (radix) {
	'x' -> code.isDigitCode() || code in 'A'.code..'F'.code
	'o' -> code in '0'.code..'7'.code
	'b' -> code == '0'.code || code == '1'.code
	else -> code.isDigitCode()
}

/**
The offset after a run of digits of `radix`, which is `x`, `o`, `b`, or `d` for decimal, that may have single underscores between them, or `start` when there is no digit there.
*/
internal fun skipRadixDigits(text: String, start: Int, radix: Char): Int {
	if (start >= text.length || !isRadixDigit(text[start].code, radix)) {
		return start
	}

	var offset = start + 1

	while (true) {
		val code = if (offset < text.length) text[offset].code else -1

		if (isRadixDigit(code, radix)) {
			offset++
		} else if (code == '_'.code && offset + 1 < text.length && isRadixDigit(text[offset + 1].code, radix)) {
			offset += 2
		} else {
			return offset
		}
	}
}

internal fun skipDigits(text: String, offset: Int): Int = skipRadixDigits(text, offset, 'd')

/**
The offset after an integer part, which is `0` or digits without a leading zero, or `offset` when there is none. Anything after a leading `0`, such as the `5` in `05`, is left for the caller to reject.
*/
internal fun skipIntegerPart(text: String, offset: Int): Int = if (offset < text.length && text[offset] == '0') offset + 1 else skipDigits(text, offset)

/**
The decimal number of `count` digits at `offset`, or -1 when one of them is not a digit. The range must be inside the string and fit in an `Int`.
*/
internal fun String.decimalDigits(offset: Int, count: Int): Int {
	var number = 0

	for (position in offset..<(offset + count)) {
		val character = this[position]

		if (!character.code.isDigitCode()) {
			return -1
		}

		number = (number * 10) + (character - '0')
	}

	return number
}
