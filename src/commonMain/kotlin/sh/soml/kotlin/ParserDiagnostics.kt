package sh.soml.kotlin

/*
The diagnostics of the parser: when something fails, these work out what the author most likely meant, such as a YAML list, a TOML table header, or an unquoted string, and say it. They run only after an error, so they may use regular expressions and allocate.
*/

private const val INSTANT_FORMAT = "An instant is written as 2026-09-19T14:00:00Z, with an optional fraction of up to nine digits and an offset of Z or ±HH:MM"
private const val KEY_QUOTING_HINT = ". A key that contains characters other than letters, digits, “_”, and “-” must be quoted"

// The patterns are compiled once, because malformed input can be frequent too.
private val tableHeaderPattern = Regex("""[\t ]*\[\[?([A-Z_a-z][\w\-]*)\]\]?[\t ]*""")
private val localDateTimePattern = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?""")
private val uppercaseExponentPattern = Regex("""^-?\d[\d_]*(?:\.[\d_]+)?E-?[\d_]*$""")
private val missingFractionPattern = Regex("""^-?[\d_]+\.(?:$|[^\d_])""")
private val colonNumberPattern = Regex("""^\d+(?::\d+)+$""")
private val timeOfDayPattern = Regex("""^\d{1,2}:\d{2}(?::\d{2}(?:\.\d+)?)?$""")
private val misplacedUnderscorePattern = Regex("""_(?:$|\D)|(?:^|\D)_""")
private val nonNumberCharacterPattern = Regex("""[^\d\-._e]""")
private val zeroPrefixedExponentPattern = Regex("""^-?\d[\d_]*(?:\.[\d_]+)?e-?0_?\d""")
private val negativeZeroExponentPattern = Regex("""^-?\d[\d_]*(?:\.[\d_]+)?e-0$""")
private val missingExponentPattern = Regex("""^-?\d[\d_]*(?:\.[\d_]+)?e-?$""")
private val monthSizePattern = Regex("""\d[\d_]*M""")

/**
Reports a value that failed to parse as what the entry was meant to be, when that is clear.
*/
internal fun Parser.diagnoseBadValue(error: SomlException, key: String, keyStart: Int, colon: Int) {
	// A key that contains a `:`, as in `12:30: 'lunch'`, ends at the first `:`, and the rest is read as the value.
	if (code(colon + 1).isBareKeyCode()) {
		diagnoseKeyWithColon(keyStart)
	}

	index = colon + 1
	val isValueOnNextLine = skipTrivia()
	val valueStart = index
	val commentHint = describeCommentAsValue(colon)

	if (isValueOnNextLine) {
		diagnoseMissingValue(valueStart, key, keyStart, commentHint)
	}

	// A value that was left out at the end of the document or of an object.
	val code = code(valueStart)

	if (commentHint.isNotEmpty() && error.offset == valueStart && (code == '}'.code || code == -1)) {
		throw fail(error.reason + commentHint, valueStart)
	}
}

/**
A bare key followed directly by `:` and more of a key, up to a `:` that ends the key, as in `a:b: 1`.
*/
private fun Parser.diagnoseKeyWithColon(keyStart: Int) {
	for (offset in keyStart..<(keyStart + MAX_DIAGNOSED_LENGTH)) {
		val code = code(offset)

		if (code == ':'.code) {
			val next = code(offset + 1)

			if (next == '\n'.code || next.isSpaceCode() || next == -1) {
				throw fail("A key that contains “:” must be quoted, as in '${abbreviate(source.substring(keyStart, offset))}'", keyStart)
			}
		} else if (!code.isBareKeyCode()) {
			return
		}
	}
}

/**
The hint for a `#` directly after a `:`, as in `color: #FFF`, which starts a comment rather than a value.
*/
private fun Parser.describeCommentAsValue(colon: Int): String {
	val hash = skipSpaces(colon + 1)

	if (code(hash) != '#'.code) {
		return ""
	}

	// A `#` followed by a space, or by another `#`, starts an ordinary comment. A separator or a closing bracket after the value is not part of it.
	val limit = minOf(source.length, hash + MAX_DIAGNOSED_LENGTH)
	val second = if (hash + 1 < limit) source[hash + 1] else null

	if (second == null || second in "\t\n #,]}") {
		return ""
	}

	var end = hash + 2

	while (end < limit && source[end] !in "\t\n ,]}") {
		end++
	}

	return ". “#” starts a comment, so a value that starts with “#” must be quoted${quotingExample(source.substring(hash, end))}"
}

/**
An entry whose value was left out, as in `a:` followed by `'b': 1` on the next line, reads the next key as the value. That failure is reported as what it is.
*/
private fun Parser.diagnoseMissingValue(start: Int, parent: String, keyStart: Int, commentHint: String) {
	// A YAML block sequence.
	if (code(start) == '-'.code && code(start + 1).isSpaceCode()) {
		throw fail("Expected a value, but found a “-” list. An array is written in brackets, as in [80, 443]", start)
	}

	index = start

	val key = try {
		parseKey()
	} catch (_: SomlException) {
		// Not a key either, so the original error stands.
		null
	}

	if (key == null || code() != ':'.code) {
		return
	}

	// Whitespace or the end follows the `:` of a bare key. A digit follows the `:` in an instant such as `2026-09-19T25:00:00Z`, whose own error is more precise. A quoted key cannot be part of a value, so anything may follow its `:`.
	val next = code(index + 1)
	val firstCode = code(start)

	if (firstCode != '\''.code && firstCode != '"'.code && next != '\n'.code && !next.isSpaceCode() && next != -1) {
		return
	}

	val hint = commentHint.ifEmpty { describeIndentedKey(parent, key, keyStart, start) }
	throw fail("Expected a value, but found the key ${describeKey(key)}$hint", start)
}

/**
The hint for a key at `start` that is indented under the entry whose value is missing, as YAML nests an object.
*/
private fun Parser.describeIndentedKey(parent: String, key: String, keyStart: Int, start: Int): String {
	val keyIndentation = getIndentation(keyStart)
	val indentation = getIndentation(start)

	if (keyIndentation == -1 || indentation == -1 || indentation <= keyIndentation) {
		return ""
	}

	// The keys are written as in a document, so that the suggestion is valid.
	return ". Indentation does not nest objects, so write ${abbreviate(formatKey(parent), 200)}: {${abbreviate(formatKey(key), 200)}: …}"
}

/**
The number of spaces and tabs before `offset` on its line, or -1 when something else comes before it.
*/
private fun Parser.getIndentation(offset: Int): Int {
	val lineStart = source.lastIndexOf('\n', offset - 1) + 1
	return if (skipSpaces(lineStart) == offset) offset - lineStart else -1
}

internal fun Parser.failMissingColon(keyStart: Int): SomlException {
	val next = skipSpaces(index)
	val nextCode = code(next)

	if (nextCode == ':'.code && next > index) {
		return fail("Whitespace is not allowed between a key and its “:”")
	}

	// A block comment followed by the ":" was meant to come before it.
	if (nextCode == '/'.code && code(next + 1) == '*'.code) {
		val commentEnd = source.indexOf("*/", next + 2)

		if (commentEnd != -1 && code(skipSpaces(commentEnd + 2)) == ':'.code) {
			return fail("A comment is not allowed between a key and its “:”", next)
		}
	}

	// A key with a space in it, such as `the name: 1`. The hint is only given for plain words, because quoting a quoted key would change what it means, and for a `:` that whitespace or the end follows, because quoting the words before the `:` in `server localhost:8080` would give a valid document with another meaning. So `the name:1` gets no hint.
	val colon = if (next > index && nextCode.isBareKeyCode()) indexOf(':', next, findLineEnd(next)) else -1

	if (colon != -1 && isSpaceOrLineEnd(code(colon + 1))) {
		// Only spaces and tabs are trimmed, because other characters are not whitespace in SOML, such as U+00A0.
		val keyEnd = skipSpacesBack(colon)

		if (isWordsWithSpaces(keyStart, keyEnd)) {
			val key = source.substring(keyStart, minOf(keyEnd, keyStart + MAX_DIAGNOSED_LENGTH))
			return fail("A bare key cannot contain spaces. Quote it, as in '${abbreviate(key)}'", keyStart)
		}
	}

	if (isAtEnd || code() == '\n'.code) {
		return fail("Expected “:” after the key")
	}

	// A character directly after a bare key is most likely meant to be part of it.
	val previousCode = code(index - 1)
	val hint = if (previousCode != '\''.code && previousCode != '"'.code && next == index && isQuotableKeyStart(next)) KEY_QUOTING_HINT else ""
	index = next
	return fail("Expected “:” after the key, but found ${describeHere()}$hint")
}

/**
The hint for a key that starts with a character a bare key cannot hold, such as the `$` in `$schema`, or nothing for a character with another meaning, such as `}`.
*/
internal fun Parser.keyQuotingHint(): String {
	if (!isQuotableKeyStart(index)) {
		return ""
	}

	// A longer key gets the hint without the example.
	val limit = minOf(source.length, index + MAX_DIAGNOSED_LENGTH)
	var end = index

	while (end < limit) {
		val codePoint = source.codePointAtOffset(end)

		if (!isQuotableKeyCharacter(codePoint)) {
			break
		}

		end += charCountOf(codePoint)
	}

	if (end >= limit || source[end] != ':') {
		return KEY_QUOTING_HINT
	}

	return "$KEY_QUOTING_HINT, as in '${abbreviate(source.substring(index, end))}'"
}

private fun Parser.isQuotableKeyStart(offset: Int): Boolean = offset < source.length && isQuotableKeyCharacter(source.codePointAtOffset(offset))

/**
The characters that may be meant as part of a key: visible ones that have no meaning in the grammar, which includes every bare key character. A `=` is left out, because after a key, as in `name=foo`, it was most likely meant as the `:` of INI and TOML, so a key that holds a `=` gets no quoting hint.
*/
private fun isQuotableKeyCharacter(codePoint: Int): Boolean {
	if ("\"#'*,/:=[]{}".indexOf(codePoint.toChar()) != -1 && codePoint < 0x80) {
		return false
	}

	return !isDefaultIgnorable(codePoint) && !isOther(codePoint) && !isWhiteSpace(codePoint)
}

/**
A `.` after a key, as in `example.com: 1`. A key is never a path, so the `.` is meant either as part of the key or as nesting.
*/
internal fun Parser.failDotInKey(keyStart: Int): SomlException {
	var end = index

	while (end < keyStart + MAX_DIAGNOSED_LENGTH && (code(end).isBareKeyCode() || code(end) == '.'.code)) {
		end++
	}

	val key = source.substring(keyStart, end)
	// Note: Not using splitToSequence() because validation, joining, and counting would repeatedly split and copy the words.
	val words = key.split('.')

	// The suggestions are only given for a bare key whose dots each have a word on both sides, so that both are valid.
	if (code(end) != ':'.code || !code(keyStart).isBareKeyCode() || words.contains("")) {
		return fail("A key cannot contain “.” unless it is quoted. Quote the whole key, or use braces to nest, as in a: {b: …}")
	}

	val nested = words.joinToString(": {") + ": …" + "}".repeat(words.size - 1)
	return fail("A bare key cannot contain “.”. Quote it, as in '${abbreviate(key)}', or use braces to nest, as in ${abbreviate(nested)}")
}

/**
A character that cannot follow the value that starts at `start`.
*/
internal fun Parser.failAfterValue(start: Int, isNumber: Boolean): SomlException {
	val code = code()

	// Go writes microseconds as `µs`, with the micro sign or the Greek letter mu. The duration is only suggested when it is valid, which a number with an exponent or a radix prefix, for example, is not.
	if (isNumber && (code == 0xB5 || code == 0x3BC) && code(index + 1) == 's'.code) {
		val number = source.substring(start, index)
		return fail("The unit for microseconds is written us${if (Parser.isValidValue(number + "us")) ", as in ${abbreviate(number)}us" else ""}")
	}

	// A `''` inside a '...' string, as SQL and YAML escape a quote, ends the string.
	if (code == '\''.code && code(start) == '\''.code) {
		return fail("There is no '' escape in a '...' string. Write a string that contains ' as \"...\", as in \"it's\"")
	}

	return fail("Unexpected ${describeHere()} after a value")
}

/**
A number followed by a space and a word, such as `512 MiB` or `10 seconds`, which is an error anyway. A word followed by more than spaces, a separator, or a comment is left to the general errors, because it may be a key, as in `a: 1 b: 2`, or a sentence.
*/
internal fun Parser.diagnoseUnitAfterSpace(start: Int) {
	val unitStart = skipSpaces(index)
	var unitEnd = unitStart

	while (code(unitEnd).isLetterCode()) {
		unitEnd++
	}

	val unit = source.substring(unitStart, minOf(unitEnd, unitStart + MAX_DIAGNOSED_LENGTH))

	// A keyword after a number is a missing comma.
	if (unit.isEmpty() || !isValueEndAt(skipSpaces(unitEnd)) || unit == "true" || unit == "false" || unit == "null" || unit == "infinity") {
		return
	}

	val number = source.substring(start, index)
	// The duration is only suggested when it is valid. The string is always offered, because a unit such as `m` may mean meters rather than minutes.
	val duration = if (Parser.DurationUnit.parse(unit, 0, unit.length) != null && Parser.isValidValue(number + unit)) abbreviate(number) + unit else "10s"
	throw fail("A unit cannot follow a number after a space. Write a duration without the space, as in $duration, and anything else, such as a size, as a string, as in '${abbreviate("$number $unit")}'", unitStart)
}

internal fun Parser.failUnexpectedValue(): SomlException {
	if (isAtEnd) {
		return fail("Expected a value, but reached the end of the document")
	}

	val code = code()

	if (code == '+'.code) {
		return fail("A “+” sign is not allowed. A number without a sign is positive")
	}

	// A `.` that no digit follows begins a string, such as `.env` or `./foo`, rather than a number.
	if (code == '.'.code) {
		return fail(if (code(index + 1).isDigitCode()) "A number cannot begin with “.”; write a digit before it, as in 0.5" else describeUnknownWord(".", unquotedText(index)))
	}

	var wordEnd = index

	while (code(wordEnd).isBareKeyCode()) {
		wordEnd++
	}

	if (wordEnd > index) {
		val word = source.substring(index, minOf(wordEnd, index + MAX_DIAGNOSED_LENGTH))

		// A key where a value should be, as when the value of an entry is left out and the next line has a key, or as in `[a: 1]`, is reported as a key. A word after the `:` of a member and a space, as in `msg: Error: file not found` or `url: https://example.com`, is that member's value instead, an unquoted string. Without the space, as in `a:b: 1`, the first `:` was most likely meant as part of the key.
		val spacesStart = skipSpacesBack(index)
		val isMemberValue = spacesStart < index && code(spacesStart - 1) == ':'.code

		if (!isMemberValue && code(wordEnd) == ':'.code) {
			return fail("Expected a value, but found the key ${abbreviate(word)}")
		}

		diagnoseTableHeader(isValue = true)
		return fail(describeUnknownWord(word, unquotedText(index)))
	}

	return fail("Expected a value, but found ${describeHere()}${if (isBlockScalarIndicator()) ". Write a multiline string as a block string, between ''' lines" else slashCommentHint()}")
}

/**
The text from `start` that was most likely meant as one unquoted string, such as `John Smith`: up to the end of the line, a comma, a closing bracket, or a comment after a space or a tab, without the spaces and tabs at its end.
*/
internal fun Parser.unquotedText(start: Int): String {
	val limit = minOf(source.length, start + MAX_DIAGNOSED_LENGTH)
	var end = start

	while (end < limit && !isUnquotedTextEnd(end)) {
		end++
	}

	return source.substring(start, maxOf(start, skipSpacesBack(end)))
}

private fun Parser.isUnquotedTextEnd(offset: Int): Boolean {
	val code = code(offset)
	val isComment = code == '#'.code || (code == '/'.code && code(offset + 1) == '*'.code)
	return code == '\n'.code || code == ','.code || code == ']'.code || code == '}'.code || (isComment && code(offset - 1).isSpaceCode())
}

/**
A TOML table header, as in `[server]` or `[[servers]]` on a line of its own, reads as an array that holds a word, or as a key that starts with "[". Where a value is expected, `isValue`, it is only a table header when its bracket opens the document, because a table cannot be written as an item of an array inside it.
*/
internal fun Parser.diagnoseTableHeader(isValue: Boolean) {
	val lineStart = source.lastIndexOf('\n', index - 1) + 1

	if (isValue && skipSpaces(lineStart) != documentStart) {
		return
	}

	val limit = lineStart + minOf(source.length - lineStart, MAX_DIAGNOSED_LENGTH)
	val lineEnd = source.indexOfWithin('\n', lineStart, limit).takeUnless { it == -1 } ?: limit
	val match = tableHeaderPattern.matchEntire(source.substring(lineStart, lineEnd)) ?: return
	throw fail("There are no table headers. Write the table as an object, as in ${abbreviate(match.groupValues[1])}: {…}")
}

/**
A YAML literal block scalar indicator, as in `key: |` or `key: |-`, at the end of its line.
*/
private fun Parser.isBlockScalarIndicator(): Boolean {
	// A folded block scalar, `>`, joins its lines, which a block string does not, so only `|` gets the hint.
	if (code() != '|'.code) {
		return false
	}

	val chomping = code(index + 1)
	val end = skipSpaces(if (chomping == '-'.code || chomping == '+'.code) index + 2 else index + 1)
	val next = code(end)
	return next == '\n'.code || next == -1
}

/**
The hint for a `//` comment, as in JavaScript.
*/
internal fun Parser.slashCommentHint(): String = if (code() == '/'.code && code(index + 1) == '/'.code) ". A comment starts with “#”" else ""

internal fun Parser.describeHere(): String {
	if (isAtEnd) {
		return "the end of the document"
	}

	val codePoint = source.codePointAtOffset(index)
	return if (codePoint == '\n'.code) "a line break" else describeCharacter(codePoint)
}

/**
The hint for a content line that ends with the delimiter, as TOML allows. Adding a closing line after it would keep the delimiter as content.
*/
internal fun Parser.describeInlineClosingDelimiter(contentStart: Int, quote: Char, delimiterLength: Int): String {
	if (delimiterLength > MAX_DIAGNOSED_LENGTH) {
		return ""
	}

	val delimiter = quote.toString().repeat(delimiterLength)
	var line = 1

	// Note: Not using count() because counting this bounded prefix measured slower than the direct loop.
	for (offset in 0..<contentStart) {
		if (source[offset] == '\n') {
			line++
		}
	}

	var lineStart = contentStart

	while (lineStart < source.length) {
		val textStart = skipSpaces(lineStart)
		val textEnd = maxOf(textStart, skipSpacesBack(findLineEnd(lineStart)))
		var quoteEnd = textStart

		while (quoteEnd < textEnd && characters[quoteEnd] == quote) {
			quoteEnd++
		}

		// A line of only quotes, longer than the delimiter, is content.
		if (textEnd - textStart >= delimiterLength && source.startsWith(delimiter, textEnd - delimiterLength) && quoteEnd < textEnd) {
			return ". Its closing delimiter must start a line, so move the $delimiter at the end of line $line to a new line"
		}

		line++
		lineStart = findLineEnd(lineStart) + 1
	}

	return ""
}

/**
A comma used as a thousands separator, as in `[1,000]`, makes the group after it a separate item, which fails only when it has a leading zero. Writing that item in octal, as the leading zero message suggests, would parse to the wrong items.
*/
internal fun Parser.describeThousandsSeparator(text: String, start: Int): String? {
	if (!(text.length == 3 && text[0] == '0' && text[1].code.isDigitCode() && text[2].code.isDigitCode()) || code(start - 1) != ','.code) {
		return null
	}

	var groupEnd = start - 1
	var numberStart = getGroupStart(groupEnd)

	// Earlier groups of three digits, as in `12,345,000`.
	while (groupEnd - numberStart == 3 && code(numberStart - 1) == ','.code && code(numberStart - 2).isDigitCode()) {
		groupEnd = numberStart - 1
		numberStart = getGroupStart(groupEnd)
	}

	val before = code(numberStart - 1)

	// The first group has one to three digits, which are not the end of a float, of a number with underscores, or of a number with a radix prefix.
	if (numberStart == groupEnd || before == '.'.code || before == '_'.code || numberStart < groupEnd - 3 || before.isLetterCode()) {
		return null
	}

	// The sign belongs to the number, as in `-1,000`.
	if (before == '-'.code) {
		numberStart--
	}

	val groupsStart = start + text.length
	var groupsEnd = groupsStart

	// Further groups of exactly three digits.
	while (code(groupsEnd) == ','.code && code(groupsEnd + 1).isDigitCode() && code(groupsEnd + 2).isDigitCode() && code(groupsEnd + 3).isDigitCode() && !code(groupsEnd + 4).isDigitCode() && groupsEnd + 4 <= groupsStart + MAX_DIAGNOSED_LENGTH) {
		groupsEnd += 4
	}

	val number = source.substring(numberStart, minOf(groupsEnd, numberStart + MAX_DIAGNOSED_LENGTH))
	val reason = "Leading zeros are not allowed in a decimal number. A comma separates items, so ${abbreviate(number)} is not one number"

	if (groupsEnd - numberStart > MAX_DIAGNOSED_LENGTH) {
		return reason
	}

	// Both spellings are the same int, so they are suggested only when it is in range.
	val withUnderscores = number.replace(',', '_')
	return if (Parser.isValidValue(withUnderscores)) "$reason. Write it as ${abbreviate(withUnderscores)} or ${abbreviate(number.replace(",", ""))}" else reason
}

private fun Parser.getGroupStart(groupEnd: Int): Int {
	var groupStart = groupEnd

	while (code(groupStart - 1).isDigitCode()) {
		groupStart--
	}

	return groupStart
}

/**
An offset on its own after spaces, as in `2026-09-19T14:00:00 Z`, which belongs directly after the time, or null.
*/
internal fun Parser.findOffsetAfterSpace(): String? {
	val offsetStart = skipSpaces(index)

	if (offsetStart == index) {
		return null
	}

	var end = offsetStart

	if (code(offsetStart) == 'Z'.code) {
		end++
	} else if ((code(offsetStart) == '+'.code || code(offsetStart) == '-'.code) && source.hasHourMinuteAt(offsetStart + 1)) {
		end += 6
	}

	return if (end != offsetStart && isValueEndAt(end)) source.substring(offsetStart, end) else null
}

private fun isYearUnit(text: String, start: Int, end: Int): Boolean {
	// Note: Not using a normalized setOf() lookup because it allocates bounded unit strings.
	val word = when (end - start) {
		1 -> "y"
		2 -> "yr"
		3 -> "yrs"
		4 -> "year"
		5 -> "years"
		else -> return false
	}

	return text.regionMatches(start, word, 0, word.length, ignoreCase = true)
}

internal fun describeBadDurationUnit(unit: String, text: String): String {
	if (unit.isEmpty()) {
		return "every number needs a unit: h, m, s, ms, us, or ns"
	}

	if (unit == "d" || unit == "day" || unit == "days") {
		return "there is no day unit, because a day is not a fixed length. Write 24h for a fixed 24 hours"
	}

	if (unit == "w" || unit == "week" || unit == "weeks") {
		return "there is no week unit, because a day is not a fixed length. Write 168h for a fixed 168 hours"
	}

	if (isYearUnit(unit, 0, unit.length)) {
		return "there is no year unit, because a year is not a fixed length. Write 8760h for a fixed 365 days"
	}

	if (unit == "M") {
		// A number with `M` alone is more often a size, as in `memory: 512M`, and `512m` would read as minutes.
		val isSize = text.length <= MAX_DIAGNOSED_LENGTH && monthSizePattern.matches(text)
		return "there is no month unit, because a month is not a fixed length${if (isSize) ". A size, such as 512M, is a string: '${abbreviate(text)}'" else ""}"
	}

	val lowercase = if (unit.length <= 2) unit.lowercase() else ""
	return if (Parser.DurationUnit.parse(lowercase, 0, lowercase.length) != null) "the units are lowercase: $lowercase" else "“${abbreviate(unit, 10)}” is not a unit. The units are h, m, s, ms, us, and ns, and a string must be quoted"
}

/**
Whether a token that is not a number or an instant was meant as a duration: its first letter could begin a unit, or a day or a week, or it is a year unit. A radix prefix, an exponent, and other letters, such as the `T` in `20260919T140000Z` or the `x` in `1.5x`, are left to the number errors.
*/
internal fun isDurationLike(text: String): Boolean {
	// Note: Not using removePrefix() because advancing past the sign avoids copying negative tokens.
	val start = if (text[0] == '-') 1 else 0

	if (start >= text.length || !text[start].code.isDigitCode()) {
		return false
	}

	var offset = start

	while (offset < text.length && (text[offset].code.isDigitCode() || text[offset] == '_')) {
		offset++
	}

	if (offset < text.length && text[offset] == '.') {
		offset++

		while (offset < text.length && (text[offset].code.isDigitCode() || text[offset] == '_')) {
			offset++
		}
	}

	// A year unit, as in `1y` or `2years`, is only one when the token or another part follows it, so that a word such as `100yen` stays a string to quote. The longest unit and the character after it fit in 6 characters.
	if (offset >= text.length) {
		return false
	}

	if (text[offset] in "dhmnsuwDHMNSUW") {
		return true
	}

	// Note: Not using takeWhile() because it needs a copied prefix here just to find an end offset.
	var unitEnd = offset

	while (unitEnd < text.length && unitEnd < offset + 6 && text[unitEnd].code.isLetterCode()) {
		unitEnd++
	}

	return isYearUnit(text, offset, unitEnd) && (unitEnd == text.length || text[unitEnd].code.isDigitCode())
}

private fun isHexDigits(text: String, start: Int, end: Int): Boolean {
	if (end > text.length) {
		return false
	}

	for (offset in start..<end) {
		if (!text[offset].code.isHexDigitCode()) {
			return false
		}
	}

	return true
}

internal fun Parser.describeBadUnicodeEscape(offset: Int): String {
	val rest = source.substring(offset, minOf(source.length, offset + 12))

	if (isHexDigits(rest, 1, 5)) {
		return describeFourDigitEscape(rest)
	}

	if (rest.startsWith("u{")) {
		var position = 2

		while (position < rest.length && rest[position].code.isHexDigitCode()) {
			if (rest[position] in 'A'..'F') {
				return "A Unicode escape uses lowercase hexadecimal digits"
			}

			position++
		}
	}

	if (rest.startsWith("u{}")) {
		return "A Unicode escape needs one to six hexadecimal digits"
	}

	return if (rest.startsWith("u{") && isHexDigits(rest, 2, 9)) "A Unicode escape has at most six hexadecimal digits" else "A Unicode escape is written \\u{…} with one to six lowercase hexadecimal digits"
}

/**
The four-digit form of JSON, a backslash, a `u`, and four hexadecimal digits, from its `u`, with the escape to write instead. JSON writes a character above U+FFFF as two of them, a surrogate pair, which is one braced escape here. Here, a lone surrogate and a carriage return have no escape.
*/
private fun describeFourDigitEscape(rest: String): String {
	val code = rest.hexadecimalCodeUnit(1)
	val lowCode = if (rest.startsWith("\\u", 5) && isHexDigits(rest, 7, 11)) rest.hexadecimalCodeUnit(7) else -1

	if (lowCode != -1 && isSurrogatePair(code.toChar(), lowCode.toChar())) {
		val codePoint = toCodePoint(code.toChar(), lowCode.toChar())
		return "The four-digit \\${rest.substring(0, 5)}\\u${rest.substring(7, 11)} form is not an escape. Write \\u{${codePoint.toString(16)}}"
	}

	val form = "The four-digit \\${rest.substring(0, 5)} form is not an escape"

	if (code.toChar().isSurrogate()) {
		return "$form, and a lone surrogate is not a Unicode scalar value. Write the character it is half of as one \\u{…} escape"
	}

	return if (code == 0x0D) "$form, and a carriage return (U+000D) cannot be represented" else "$form. Write \\u{${code.toString(16)}}"
}

/**
`unquotedText` is the unquoted string that `fullText` begins, for the suggestion to quote it.
*/
internal fun describeBadNumber(fullText: String, unquotedText: String): String {
	// Note: Not using take() because it copies even an already short string on Native, while this branch reuses it.
	val text = if (fullText.length > MAX_DIAGNOSED_LENGTH) fullText.substring(0, MAX_DIAGNOSED_LENGTH) else fullText

	if (text.contains('+')) {
		return "A “+” sign is not allowed in a number, including in an exponent"
	}

	val signLength = if (fullText.startsWith('-')) 1 else 0

	if (text.length >= signLength + 2 && text[signLength] == '0' && text[signLength + 1] in "BOX") {
		return "A number prefix is lowercase: 0x, 0o, or 0b"
	}

	// The whole number, not the part cut at the diagnosed length, because a cut can end before the digit or the underscore that the message is about. Each check below takes linear time.
	if (fullText.length >= signLength + 2 && fullText[signLength] == '0' && fullText[signLength + 1] in "box") {
		val radix = fullText[signLength + 1]
		val digits = fullText.substring(signLength + 2)
		val name = when (radix) {
			'x' -> "hexadecimal"
			'o' -> "octal"
			else -> "binary"
		}

		if (signLength == 1) {
			return "${if (radix == 'o') "An " else "A "}$name integer cannot have a sign, because it states a bit pattern rather than a quantity"
		}

		if (digits.isEmpty()) {
			return "Expected $name digits after “0$radix”"
		}

		if (radix == 'x' && isLowercaseHexDigits(digits)) {
			// The uppercase spelling is only suggested when it is valid, so a misplaced underscore is reported first, and a value outside the 64-bit range gets no example.
			val uppercaseDigits = digits.uppercase()

			if (skipRadixDigits(uppercaseDigits, 0, 'x') != digits.length) {
				return "An underscore in a number must be between two digits"
			}

			val uppercase = "0x$uppercaseDigits"
			return if (Parser.isValidValue(uppercase)) "Hexadecimal digits are uppercase: ${abbreviate(uppercase)}" else "Hexadecimal digits are uppercase"
		}

		// A lowercase hexadecimal digit is a digit in the wrong case, so the one named is a character that is no digit in either case.
		var offset = 0

		while (offset < digits.length) {
			val codePoint = digits.codePointAtOffset(offset)
			val isValid = codePoint == '_'.code || when (radix) {
				'x' -> codePoint.isHexDigitCode()
				'o' -> codePoint in '0'.code..'7'.code
				else -> codePoint == '0'.code || codePoint == '1'.code
			}

			if (!isValid) {
				return "Invalid $name digit “${codePointToString(codePoint)}”"
			}

			offset += charCountOf(codePoint)
		}

		return "An underscore in a number must be between two digits"
	}

	// Only when the “E” is where an exponent marker goes, with digits, a `-`, or nothing after it. In `5EUR` or `10EB`, it is part of a word, so lowercasing it would not help.
	if (uppercaseExponentPattern.matches(text)) {
		return "An exponent marker is a lowercase “e”"
	}

	if (missingFractionPattern.containsMatchIn(text)) {
		return "A decimal point must be followed by a digit"
	}

	if (text.startsWith("-.")) {
		return "A number cannot begin with “.”; write a digit before it, as in -0.5"
	}

	if (colonNumberPattern.matches(text)) {
		return if (timeOfDayPattern.matches(text)) "A time of day is a string, so it must be quoted" else "Invalid number “${abbreviate(text)}”. A value that contains “:” must be quoted, as in '${abbreviate(text)}'"
	}

	// A value with a `:` that starts with a zero, such as the MAC address `00:1A:2B`, is not a number with a leading zero, because removing the zero would not make it valid.
	if ((text.length >= signLength + 2 && text[signLength] == '0' && (text[signLength + 1].code.isDigitCode() || text[signLength + 1] == '_')) && !text.contains(':')) {
		// Removing the zero gives a valid number with a different meaning, so the message says what the zero usually meant. A '...' string cannot hold a ', so then there is no example.
		val identifier = "an identifier, such as a ZIP code, as a string${if (unquotedText.contains('\'')) "" else ": '${abbreviate(unquotedText)}'"}"

		// The octal suggestion is only for the number on its own, not for one that more text follows, as in `0412 345 678`, and only when it is in range. It is decided on the whole number, because a cut can end before a digit that is not octal or that changes the value.
		if (unquotedText == fullText && isOctalWithLeadingZero(fullText)) {
			val digits = fullText.trimStart { it == '0' }
			val octal = "0o${digits.ifEmpty { "0" }}"

			if (Parser.isValidValue(octal)) {
				return "Leading zeros are not allowed in a decimal number. Write an octal number, such as a file mode, as ${abbreviate(octal)}, and $identifier"
			}
		}

		return if (signLength == 0 && isDigits(text, 1, text.length)) "Leading zeros are not allowed in a decimal number. Write $identifier" else "Leading zeros are not allowed in a decimal number"
	}

	// Only in a number: an underscore next to a letter other than the exponent marker is part of a word, such as `4k_video`.
	if (misplacedUnderscorePattern.containsMatchIn(text) && !nonNumberCharacterPattern.containsMatchIn(text)) {
		return "An underscore in a number must be between two digits"
	}

	if (zeroPrefixedExponentPattern.containsMatchIn(text)) {
		return "Leading zeros are not allowed in an exponent"
	}

	if (negativeZeroExponentPattern.matches(text)) {
		return "“e-0” is not allowed, because an exponent of zero has one spelling: e0"
	}

	// Only a number ends with its exponent marker. A word that ends with an “e”, such as `1byte` or `-verbose`, has no exponent.
	if (missingExponentPattern.matches(text)) {
		return "Expected digits after the exponent marker “e”"
	}

	if (text.indexOf('.') != text.lastIndexOf('.')) {
		return "Invalid number “${abbreviate(text)}”. A value with several dots, such as a version number, must be quoted"
	}

	if (signLength == 1 && (text.length == 1 || !text[1].code.isDigitCode())) {
		// The whole word, as for one without the “-”, so that a word such as `-nano` or `-info` is not taken for NaN or infinity.
		if (text.equals("-nan", ignoreCase = true)) {
			return "NaN is not representable. Use null for a missing value"
		}

		return if (text.equals("-inf", ignoreCase = true) || text.equals("-infinity", ignoreCase = true)) "“${abbreviate(text)}” is not a value. Negative infinity is written -infinity" else "Expected a digit or “infinity” after “-”"
	}

	return if (text.any { it.code.isLetterCode() }) "Invalid number “${abbreviate(text)}”. A string value must be quoted${quotingExample(unquotedText)}" else "Invalid number “${abbreviate(text)}”"
}

private fun isLowercaseHexDigits(digits: String): Boolean {
	var hasLowercase = false

	for (character in digits) {
		if (character in 'a'..'f') {
			hasLowercase = true
		} else if (!character.code.isHexDigitCode() && character != '_') {
			return false
		}
	}

	return hasLowercase
}

private fun isOctalWithLeadingZero(text: String): Boolean {
	if (text.length < 2 || text[0] != '0') {
		return false
	}

	for (index in 1..<text.length) {
		if (text[index] !in '0'..'7') {
			return false
		}
	}

	return true
}

/**
`time` is the token after a space that follows `text`, or an empty string. `isWholeValue` is whether nothing that may be part of the instant follows `text`. `offset` is an offset after a space that follows `text`, or null.
*/
internal fun describeBadInstant(text: String, time: String, isWholeValue: Boolean, offset: String?): String {
	if (text.length > MAX_DIAGNOSED_LENGTH) {
		return "Invalid instant “${abbreviate(text)}”. $INSTANT_FORMAT"
	}

	if (text.length == 10 && hasDatePrefix(text)) {
		return describeSpaceSeparatedInstant(text, time, isWholeValue)
	}

	if (text.length > 10 && text[10] == 't') {
		return "The date and time separator in an instant is an uppercase “T”"
	}

	if (text.endsWith('z')) {
		return "The UTC offset in an instant is an uppercase “Z”"
	}

	if (text.length >= 5 && (text[text.length - 5] == '+' || text[text.length - 5] == '-') && isDigits(text, text.length - 4, text.length)) {
		return "An instant's offset is written with a colon, as in +07:00"
	}

	if (!localDateTimePattern.matches(text)) {
		return "Invalid instant “${abbreviate(text)}”. $INSTANT_FORMAT"
	}

	if (offset != null) {
		// The instant has the offset it was meant in, so it is not a local time to write as a string.
		val reason = "An instant's offset follows its time directly, without a space"
		return if (Parser.isValidValue(text + offset)) "$reason, as in ${abbreviate(text + offset)}" else reason
	}

	return if (isWholeValue) "An instant needs an offset: Z or ±HH:MM. A date and time without an offset is a local time, which is not an instant, so write it as a string, as in '${abbreviate(text)}', or add the offset it was meant in" else "An instant needs an offset: Z or ±HH:MM"
}

/**
A date and a time with a space between them, where an instant has a "T". Following the example must not turn a local time into UTC silently, so a time without an offset is described as what it is. An instant is only shown when it is valid, so a date that does not exist, a time out of range, or an instant outside the years 0001 to 9999 in UTC gets the general format instead.
*/
private fun describeSpaceSeparatedInstant(date: String, time: String, isWholeValue: Boolean): String {
	val separator = "The date and time separator in an instant is an uppercase “T”, not a space"
	val instant = "${date}T$time"

	if (instant.length > MAX_DIAGNOSED_LENGTH) {
		return "$separator. $INSTANT_FORMAT"
	}

	if (Parser.isValidValue(instant)) {
		return "$separator, as in ${abbreviate(instant)}"
	}

	return if (isWholeValue && localDateTimePattern.matches(instant) && Parser.isValidValue(instant + "Z")) "$separator, and an instant needs the offset it was meant in, as in ${abbreviate(instant)}Z for UTC. A date and time without an offset is a local time, which is not an instant, so write it as a string, as in '${abbreviate("$date $time")}'" else "$separator. $INSTANT_FORMAT"
}

/**
`text` is the unquoted string that `word` begins, which the suggestion quotes.
*/
internal fun describeUnknownWord(word: String, text: String): String {
	// A keyword hint is only for the word on its own. In `Yes please` or `Nan Goldin`, following it would leave text behind or change the value.
	val lowercase = if (text == word) word.lowercase() else ""

	return when (lowercase) {
		"true", "false", "yes", "no", "on", "off" -> "“$word” is not a value. Booleans are written true and false, in lowercase"
		"null", "nil", "none", "undefined" -> "“$word” is not a value. Null is written null, in lowercase"
		"nan" -> "NaN is not representable. Use null for a missing value"
		"inf", "infinity" -> "“$word” is not a value. Infinity is written infinity, in lowercase"
		else -> "Unexpected “${abbreviate(word)}”. A string value must be quoted${quotingExample(text)}"
	}
}

/**
The example in a message that a string must be quoted, which quotes `text` as a `'...'` string. That cannot hold a `'`, so then there is no simple example.
*/
private fun quotingExample(text: String): String = if (text.contains('\'')) "" else ", as in '${abbreviate(text)}'"

private fun Parser.isWordsWithSpaces(start: Int, end: Int): Boolean {
	for (offset in start..<end) {
		val code = characters[offset].code

		if (!code.isSpaceCode() && !code.isBareKeyCode()) {
			return false
		}
	}

	return true
}

private fun isSpaceOrLineEnd(code: Int): Boolean = code.isSpaceCode() || code == '\n'.code || code == -1

/**
The four already validated hexadecimal digits at `offset`, without allocating a substring.
*/
private fun String.hexadecimalCodeUnit(offset: Int): Int {
	// Note: Not using hexToInt() because parsing these four digits through its public API requires a substring.
	var value = 0

	for (index in offset..<(offset + 4)) {
		value = (value shl 4) or this[index].digitToInt(16)
	}

	return value
}
