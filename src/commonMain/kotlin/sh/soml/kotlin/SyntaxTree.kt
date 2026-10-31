package sh.soml.kotlin

/**
A document as it is written: every member, item, key, and scalar with its range in the text, and every comment, for the formatter, the editor, and the positions of decoding errors. A range is a start and an end as UTF-16 offsets into the text.

The parser validates the document first, so the builder reads it again assuming it is valid. It is a port of the JavaScript reference's `parseTree()`, with only what the formatter and the editor use.
*/
internal class SyntaxTree private constructor(
	val text: String,
	val body: Container,
	// In source order, never overlapping.
	val comments: List<Comment>,
) {
	/**
	A member of an object or an item of an array.
	*/
	sealed interface Item {
		val start: Int
		val end: Int
	}

	sealed interface ValueNode : Item

	sealed interface Container : ValueNode {
		val items: List<Item>

		/**
		False only for a top-level object written without braces.
		*/
		val isBraced: Boolean
	}

	class ObjectNode(
		val members: List<MemberNode>,
		override val isBraced: Boolean,
		override val start: Int,
		override val end: Int,
	) : Container {
		override val items: List<Item>
			get() = members

		/**
		The member with the key `key`. The keys of an object are unique in a valid document, so the first match is the only one.
		*/
		fun member(key: String): MemberNode? = members.firstOrNull { it.key.matches(key) }
	}

	class ArrayNode(
		val elements: List<ValueNode>,
		override val start: Int,
		override val end: Int,
	) : Container {
		override val items: List<Item>
			get() = elements

		override val isBraced: Boolean
			get() = true
	}

	class MemberNode(val key: KeyNode, val value: ValueNode) : Item {
		override val start: Int
			get() = key.start

		override val end: Int
			get() = value.end
	}

	/**
	A key, whose value is decoded only when the editor looks it up. Formatting only needs its range.
	*/
	class KeyNode(private val text: String, val start: Int, val end: Int) {
		private val isQuoted: Boolean
			get() = text[start] == '\'' || text[start] == '"'

		val value: String
			get() = if (isQuoted) Parser.readQuoted(text, start, end) else text.substring(start, end)

		fun matches(key: String): Boolean {
			val contentStart = if (isQuoted) start + 1 else start
			val contentEnd = if (isQuoted) end - 1 else end

			// Escapes can shorten the written key, but never make its decoded UTF-16 length longer.
			if (key.length > contentEnd - contentStart) {
				return false
			}

			if (text[start] == '"' && text.indexOfWithin('\\', contentStart, contentEnd) != -1) {
				return value == key
			}

			return key.length == contentEnd - contentStart && text.startsWith(key, contentStart)
		}
	}

	/**
	A string, a number, a keyword, a date, an instant, or a duration, as its text.
	*/
	class ScalarNode(val isBlockString: Boolean, override val start: Int, override val end: Int) : ValueNode

	/**
	A line comment, from `#` to the end of its line, or a block comment.
	*/
	class Comment(val isBlock: Boolean, val start: Int, val end: Int)

	/**
	The offset of the value at `path`, or of the deepest value on the way to it that exists, for the position of a decoding error. The offset of a member is its key's when `isKey` is true.
	*/
	fun offsetOf(path: List<Any>, isKey: Boolean = false): Int {
		var node: ValueNode = body
		var member: MemberNode? = null

		for (component in path) {
			member = null

			node = when {
				node is ObjectNode && component is String -> {
					member = node.member(component) ?: break
					member.value
				}
				node is ArrayNode && component is Int -> node.elements.getOrNull(component) ?: break
				else -> break
			}
		}

		if (isKey && member != null) {
			return member.key.start
		}

		return node.start
	}

	companion object {
		/**
		@throws SomlException When the document is not valid.
		*/
		fun parse(text: String): SyntaxTree {
			Parser.validate(text)
			return ofValid(text)
		}

		/**
		The syntax tree of a text that the parser has already validated.
		*/
		fun ofValid(text: String): SyntaxTree = Builder(text).build()
	}

	private class Builder(private val text: String) {
		private val comments = ArrayList<Comment>()
		private var index = 0

		// Past the end, -1, which matches no character, so the scans stop at the end.
		private fun code(offset: Int = index): Int = if (offset < text.length) text[offset].code else -1

		fun build(): SyntaxTree {
			skipTrivia()

			val body = when (code()) {
				'{'.code -> parseObject()
				'['.code -> parseArray()
				else -> parseBareObject()
			}

			skipTrivia()
			return SyntaxTree(text, body, comments)
		}

		private fun skipTrivia() {
			while (true) {
				val code = code()
				val start = index

				when {
					code == '\n'.code || code.isSpaceCode() -> index++
					code == '#'.code -> {
						val lineEnd = text.indexOf('\n', start)
						index = if (lineEnd == -1) text.length else lineEnd
						comments.add(Comment(isBlock = false, start, index))
					}
					code == '/'.code && code(start + 1) == '*'.code -> {
						// The `*/` begins after the `/*`, so the `*` of `/*/` does not close it, and the document is valid, so the `*/` exists.
						index = text.indexOf("*/", start + 2) + 2
						comments.add(Comment(isBlock = true, start, index))
					}
					else -> return
				}
			}
		}

		private fun parseBareObject(): ObjectNode {
			val members = ArrayList<MemberNode>()

			// The document is valid, so after a member and the trivia after it, either the text ends or another member begins.
			do {
				members.add(parseMember())
				skipTrivia()
			} while (index < text.length)

			return ObjectNode(members, isBraced = false, members.first().start, members.last().end)
		}

		private fun parseObject(): ObjectNode {
			val start = index
			val members = parseItems('}', ::parseMember)
			return ObjectNode(members, isBraced = true, start, index)
		}

		private fun parseArray(): ArrayNode {
			val start = index
			val elements = parseItems(']', ::parseValue)
			return ArrayNode(elements, start, index)
		}

		/**
		The items between an opening bracket and its closing one, which is consumed too. The parser has already checked the separators.
		*/
		private inline fun <T> parseItems(closing: Char, parseItem: () -> T): List<T> {
			index++
			skipTrivia()

			if (code() == closing.code) {
				index++
				return emptyList()
			}

			// Note: Not using buildList() because it measured slower for small syntax trees without reducing allocations.
			val items = ArrayList<T>()

			while (code() != closing.code) {
				items.add(parseItem())
				skipTrivia()

				if (code() == ','.code) {
					index++
					skipTrivia()
				}
			}

			index++
			return items
		}

		private fun parseMember(): MemberNode {
			val key = parseKey()
			// Nothing may come between a key and its `:`.
			index++
			skipTrivia()
			return MemberNode(key, parseValue())
		}

		private fun parseKey(): KeyNode {
			val start = index
			val code = code()

			if (code == '\''.code || code == '"'.code) {
				index = singleLineStringEnd(start)
			} else {
				while (code().isBareKeyCode()) {
					index++
				}
			}

			return KeyNode(text, start, index)
		}

		private fun parseValue(): ValueNode {
			val code = code()

			if (code == '{'.code) {
				return parseObject()
			}

			if (code == '['.code) {
				return parseArray()
			}

			val start = index

			if (code == '\''.code || code == '"'.code) {
				// In a valid document, a quote never follows a value directly, so three quotes always open a block string.
				if (code(start + 1) == code && code(start + 2) == code) {
					index = blockStringEnd(start, code)
					return ScalarNode(isBlockString = true, start, index)
				}

				index = singleLineStringEnd(start)
				return ScalarNode(isBlockString = false, start, index)
			}

			// Any other value is one run of scalar characters: a keyword, a number, a date, an instant, or a duration.
			index++

			while (code().isNumberCode()) {
				index++
			}

			return ScalarNode(isBlockString = false, start, index)
		}

		/**
		`'...'` ends at the next `'`. `"..."` ends at the next `"` that is not escaped, and a `\` always starts an escape that holds no quote.
		*/
		private fun singleLineStringEnd(start: Int): Int {
			if (text[start] == '\'') {
				return text.indexOf('\'', start + 1) + 1
			}

			var end = text.indexOf('"', start + 1)

			while (true) {
				var backslashStart = end

				while (text[backslashStart - 1] == '\\') {
					backslashStart--
				}

				if ((end - backslashStart) % 2 == 0) {
					return end + 1
				}

				end = text.indexOf('"', end + 1)
			}
		}

		/**
		The end of the closing delimiter: the first line whose first content after spaces and tabs is a run of exactly as many quotes as the opening delimiter.
		*/
		private fun blockStringEnd(start: Int, quote: Int): Int {
			var delimiterLength = 0

			while (code(start + delimiterLength) == quote) {
				delimiterLength++
			}

			// The opening delimiter is followed directly by a line break, and the document is valid, so a closing line exists.
			var lineStart = text.indexOf('\n', start) + 1

			while (true) {
				var delimiterStart = lineStart

				while (code(delimiterStart).isSpaceCode()) {
					delimiterStart++
				}

				var runLength = 0

				while (code(delimiterStart + runLength) == quote) {
					runLength++
				}

				if (runLength == delimiterLength) {
					return delimiterStart + delimiterLength
				}

				lineStart = text.indexOf('\n', lineStart) + 1
			}
		}
	}
}
