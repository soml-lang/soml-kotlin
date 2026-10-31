package sh.soml.kotlin

/**
The formatter of the spec: it changes the layout of a document and nothing else. One tab per level, every member and item of a container that spans lines on its own line with no commas, a container on one line kept on one line, comments in place, and the spelling of every value as it is.

A port of the JavaScript reference's `format()`. Every scan of the text is bounded by the range it is about, so that a document with long lines stays linear.
*/
internal class Formatter private constructor(private val tree: SyntaxTree) {
	private val text = tree.text
	private val comments = tree.comments
	private val output = StringBuilder(text.length + 16)
	private var commentIndex = 0

	private fun print(): String {
		val body = tree.body
		val items = if (body is SyntaxTree.ObjectNode && !body.isBraced) body.members else listOf(body)

		// The document is a list without brackets: the members of a brace-less object, or one braced collection.
		writeList(items, 0, text.length, level = 0, isBracketed = false)
		return output.appendLine().toString()
	}

	/**
	Starts a new line at `level`, with one blank line before it when the source had one. The first line of the file has no line break before it.
	*/
	private fun newLine(level: Int, isBlank: Boolean) {
		if (output.isEmpty()) {
			return
		}

		output.append(if (isBlank) "\n\n" else "\n")

		output.appendTabs(level)
	}

	private fun indexOfLineFeed(start: Int, end: Int): Int = text.indexOfWithin('\n', start, end)

	private fun isSameLine(start: Int, end: Int): Boolean = indexOfLineFeed(start, end) == -1

	/**
	Whether a line between `start` and `end` holds only spaces and tabs, if anything.
	*/
	private fun hasBlankLine(start: Int, end: Int): Boolean {
		// Note: Not using Regex.containsMatchIn() because matching a copied range adds allocation and measured slower than this bounded scan.
		var offset = indexOfLineFeed(start, end)

		while (offset != -1) {
			var next = offset + 1

			while (next < end && text[next].code.isSpaceCode()) {
				next++
			}

			if (next < end && text[next] == '\n') {
				return true
			}

			offset = indexOfLineFeed(next, end)
		}

		return false
	}

	/**
	The next comment that ends at or before `end`, without consuming it.
	*/
	private fun peekComment(end: Int): SyntaxTree.Comment? = comments.getOrNull(commentIndex)?.takeIf { it.end <= end }

	private fun writeComment(comment: SyntaxTree.Comment) {
		commentIndex++

		if (!comment.isBlock) {
			output.append(text, comment.start, trimEnd(comment.start, comment.end))
			return
		}

		// The lines of a block comment keep their own indentation. Trailing whitespace is removed, and runs of blank lines collapse to one, as everywhere outside a block string.
		// Note: Not using lineSequence() because it allocates line strings instead of appending source ranges directly.
		var isPreviousEmpty = false
		var isFirst = true
		var lineStart = comment.start

		while (lineStart <= comment.end) {
			val lineEnd = lineEndWithin(lineStart, comment.end)
			val trimmedEnd = trimEnd(lineStart, lineEnd)
			val isEmpty = trimmedEnd == lineStart

			if (!(isEmpty && isPreviousEmpty && !isFirst)) {
				if (!isFirst) {
					output.appendLine()
				}

				output.append(text, lineStart, trimmedEnd)
			}

			isFirst = false
			isPreviousEmpty = isEmpty
			lineStart = lineEnd + 1
		}
	}

	/**
	The line feed after `start`, or `end` when none comes before it. The search stops at `end`, so that many comments on one long line stay linear.
	*/
	private fun lineEndWithin(start: Int, end: Int): Int = indexOfLineFeed(start, end).takeIf { it != -1 } ?: end

	/**
	The end of the text from `start` to `end` without the spaces and tabs at its end. Only spaces and tabs are whitespace in SOML. Any other character, such as a no-break space, is content.
	*/
	private fun trimEnd(start: Int, end: Int): Int {
		// Note: Not using trimEnd() because only an offset is needed, and trimming a bounded subsequence copies the comment text.
		var trimmed = end

		while (trimmed > start && text[trimmed - 1].code.isSpaceCode()) {
			trimmed--
		}

		return trimmed
	}

	/**
	The offset of the `,` after an item in the source, or -1 when there is none. Between two items there is only whitespace, comments, and at most one comma.
	*/
	private fun findComma(start: Int, end: Int): Int {
		var searchStart = start

		for (position in commentIndex..<comments.size) {
			val comment = comments[position]

			if (comment.start >= end) {
				break
			}

			val comma = indexOfComma(searchStart, comment.start)

			if (comma != -1) {
				return comma
			}

			// A comma inside a comment is not one. The syntax tree already found its end, so its content need not be scanned again.
			searchStart = comment.end
		}

		return indexOfComma(searchStart, end)
	}

	private fun indexOfComma(start: Int, end: Int): Int = text.indexOfWithin(',', start, end)

	/**
	Writes `items` one per line at `level`, with the comments between them, from `start` to `end` in the source.

	A comment on the line of the item before it, or of the opening bracket, stays at the end of that line. The exception is a block comment after the comma that the next item follows on the same line, as in `[1, /* note */ 2]`: it stays in front of that item. Every other comment starts a new line, and a comment or an item that follows a block comment on its line stays on that line.
	*/
	private fun writeList(items: List<SyntaxTree.Item>, start: Int, end: Int, level: Int, isBracketed: Boolean) {
		var previousEnd = start
		var itemEnd = -1
		// Whether a comment may stay at the end of the line before it, which needs an item or an opening bracket there.
		var canTrail = isBracketed
		// Whether the list has written something other than a comment at the end of the opening bracket's line. A blank line is kept only after that, so none is left directly inside the bracket or at the start of the file.
		var hasWritten = false
		var isOnSameLine = false

		// Note: Not using items + null because it copies each container's items into another list.
		for (position in 0..items.size) {
			val item = items.getOrNull(position)
			val itemStart = item?.start ?: end
			val comma = if (isBracketed) findComma(previousEnd, itemStart) else -1
			// Found once per item rather than once per comment, which would be quadratic in the number of comments on one line.
			val itemLineStart = if (item == null) -1 else lineStartAfter(previousEnd, itemStart)
			var comment = peekComment(itemStart)

			while (comment != null) {
				val isTrailing = canTrail && !isOnSameLine && isTrailing(comment, previousEnd, itemEnd, itemLineStart, comma)

				if (isTrailing || isOnSameLine) {
					output.append(' ')
				} else {
					newLine(level, hasWritten && hasBlankLine(previousEnd, comment.start))
				}

				writeComment(comment)
				hasWritten = hasWritten || !isTrailing

				// A block comment that the next comment or item follows on the same line keeps it on that line.
				val next = peekComment(itemStart)
				val nextStart = next?.start ?: itemStart
				isOnSameLine = comment.isBlock && !isTrailing && isSameLine(comment.end, nextStart)
				previousEnd = comment.end
				comment = next
			}

			if (item == null) {
				return
			}

			if (isOnSameLine) {
				output.append(' ')
			} else {
				newLine(level, hasWritten && hasBlankLine(previousEnd, itemStart))
			}

			writeItem(item, level)
			previousEnd = item.end
			itemEnd = previousEnd
			canTrail = true
			hasWritten = true
			isOnSameLine = false
		}
	}

	/**
	The start of the line that holds `offset`, but not before `start`.
	*/
	private fun lineStartAfter(start: Int, offset: Int): Int {
		// Note: Not using lastIndexOf() because it cannot stop at the lower bound and would repeatedly scan earlier items on long lines.
		for (position in (offset - 1) downTo start) {
			if (text[position] == '\n') {
				return position + 1
			}
		}

		return start
	}

	/**
	Whether a comment stays at the end of the line before it. That line ends at the item before it, or at the comma after that item.
	*/
	private fun isTrailing(comment: SyntaxTree.Comment, previousEnd: Int, itemEnd: Int, itemLineStart: Int, comma: Int): Boolean {
		val isAfterComma = comma != -1 && comment.start > comma

		// A block comment after the comma that the next item follows on the same line stays in front of that item. The comment ends on the item's line when it ends after the start of that line.
		if (itemLineStart != -1 && comment.isBlock && (comma == -1 || isAfterComma) && comment.end >= itemLineStart) {
			return false
		}

		// A comment after the comma ends the comma's line, which is later than the item's when a block comment that spans lines comes between them. The comma is removed, so the comment moves to the end of the item's line, which only holds when nothing came between them.
		val lineEnd = if (isAfterComma && previousEnd == itemEnd) comma + 1 else previousEnd
		return isSameLine(lineEnd, comment.start)
	}

	/**
	Writes a container that is on one line in the source on one line, with a comma and a space between its members or items and no trailing comma. Only a block comment can be inside it, and each one stays where it is among the items and commas.
	*/
	private fun writeOneLine(items: List<SyntaxTree.Item>, start: Int, end: Int, level: Int, opening: Char, closing: Char) {
		output.append(opening)
		var previousEnd = start + 1
		var hasWrittenToken = false

		for (position in 0..items.size) {
			val item = items.getOrNull(position)
			val itemStart = item?.start ?: (end - 1)
			// The comma after the last item is left out.
			val comma = if (item == null) -1 else findComma(previousEnd, itemStart)
			var hasWrittenComma = false
			var comment = peekComment(itemStart)

			while (comment != null) {
				if (!hasWrittenComma && comma != -1 && comma < comment.start) {
					output.append(',')
					hasWrittenComma = true
				}

				if (hasWrittenToken) {
					output.append(' ')
				}

				writeComment(comment)
				hasWrittenToken = true
				comment = peekComment(itemStart)
			}

			if (item == null) {
				break
			}

			if (comma != -1 && !hasWrittenComma) {
				output.append(", ")
			} else if (hasWrittenToken) {
				output.append(' ')
			}

			writeItem(item, level)
			previousEnd = item.end
			hasWrittenToken = true
		}

		output.append(closing)
	}

	private fun writeItem(item: SyntaxTree.Item, level: Int) {
		when (item) {
			is SyntaxTree.MemberNode -> writeMember(item, level)
			is SyntaxTree.ValueNode -> writeValue(item, level)
		}
	}

	private fun writeMember(member: SyntaxTree.MemberNode, level: Int) {
		val key = member.key
		val value = member.value
		output.append(text, key.start, key.end).append(':')
		val isBlockString = value is SyntaxTree.ScalarNode && value.isBlockString

		if (peekComment(value.start) == null) {
			// A block string begins on the next line, one level deeper, so its delimiters and content line up.
			if (isBlockString) {
				newLine(level + 1, isBlank = false)
				writeValue(value, level + 1)
				return
			}

			output.append(' ')
			writeValue(value, level)
			return
		}

		// With comments between the `:` and the value, the line breaks between them are kept, but not the blank lines, and a value on a new line is one level deeper. Nothing may come between a key and its `:`, so the `:` is the character right after the key.
		var previousEnd = key.end + 1
		var valueLevel = level
		var comment = peekComment(value.start)

		while (comment != null) {
			if (writeSeparator(previousEnd, comment.start, level + 1)) {
				valueLevel = level + 1
			}

			writeComment(comment)
			previousEnd = comment.end
			comment = peekComment(value.start)
		}

		if (isBlockString) {
			newLine(level + 1, isBlank = false)
			valueLevel = level + 1
		} else if (writeSeparator(previousEnd, value.start, level + 1)) {
			valueLevel = level + 1
		}

		writeValue(value, valueLevel)
	}

	/**
	A space when `end` is on the line of `start` in the source, and otherwise a new line at `level`. Returns whether it started a new line.
	*/
	private fun writeSeparator(start: Int, end: Int, level: Int): Boolean {
		if (isSameLine(start, end)) {
			output.append(' ')
			return false
		}

		newLine(level, isBlank = false)
		return true
	}

	private fun writeValue(node: SyntaxTree.ValueNode, level: Int) {
		when (node) {
			is SyntaxTree.Container -> {
				val opening = if (node is SyntaxTree.ObjectNode) '{' else '['
				val closing = if (node is SyntaxTree.ObjectNode) '}' else ']'
				val items = node.items

				// Every comment before the container is already written, so a next comment that ends by its end is inside it. A container that holds only comments is not empty.
				if (items.isEmpty() && peekComment(node.end) == null) {
					output.append(opening).append(closing)
					return
				}

				if (isSameLine(node.start, node.end)) {
					writeOneLine(items, node.start, node.end, level, opening, closing)
					return
				}

				output.append(opening)
				writeList(items, node.start + 1, node.end - 1, level + 1, isBracketed = true)
				newLine(level, isBlank = false)
				output.append(closing)
			}
			is SyntaxTree.ScalarNode if node.isBlockString -> reindentBlockString(node, level)
			is SyntaxTree.ScalarNode -> output.append(text, node.start, node.end)
		}
	}

	/**
	Moves a block string to the indentation of `level`. Its content is relative to the closing delimiter's indentation, so replacing that indentation on every line keeps the value. A blank line is left as it is, because it becomes an empty line either way. The first line starts at the opening delimiter, after the indentation that is already written.
	*/
	private fun reindentBlockString(node: SyntaxTree.ScalarNode, level: Int) {
		val closingStart = text.lastIndexOf('\n', node.end - 1) + 1
		var oldIndentationLength = 0

		while (closingStart + oldIndentationLength < node.end && text[closingStart + oldIndentationLength].code.isSpaceCode()) {
			oldIndentationLength++
		}

		var lineStart = node.start

		while (lineStart <= node.end) {
			val lineEnd = lineEndWithin(lineStart, node.end)

			if (lineStart > node.start) {
				output.appendLine()
			}

			if (lineStart == node.start || trimEnd(lineStart, lineEnd) == lineStart) {
				output.append(text, lineStart, lineEnd)
			} else {
				// The parser checked that every line that is not blank starts with the closing line's indentation, so cutting its length removes exactly that.
				output.appendTabs(level)

				output.append(text, lineStart + oldIndentationLength, lineEnd)
			}

			lineStart = lineEnd + 1
		}
	}

	companion object {
		/**
		@throws SomlException When the document is not valid.
		*/
		fun format(text: String): String = Formatter(SyntaxTree.parse(text)).print()
	}
}
