package sh.soml.kotlin

/**
Sets or removes one value in a document, and keeps everything else as it is written, by the editing rules of the spec: only the text of the member or item that is added, replaced, or removed changes, with the comments it owns and its comma.

A port of the JavaScript reference's `edit()`. It replaces one range of the text and parses the result, so that a mistake here throws rather than writing a broken document.
*/
internal class Editor private constructor(
	tree: SyntaxTree,
	private val path: List<Any>,
	// The new value, or null to remove the value at the path.
	private val value: SomlValue?,
) {
	/**
	The changed text and its value, which is null when nothing changed.
	*/
	class Result(val text: String, val value: SomlValue?)

	private class Replacement(val start: Int, val end: Int, val text: String)

	/**
	A range of the text to remove. `isWholeLines` is whether it takes whole lines, which may leave a blank line next to another one.
	*/
	private class Removal(var start: Int, var end: Int, val isWholeLines: Boolean)

	private val text = tree.text
	private val comments = tree.comments
	private var replacement: Replacement? = null
	// Whether the container being edited is inside one that stays on one line, so that it stays on one line too. An edit follows one path, so one flag is enough.
	private var isInsideOneLine = false

	private fun fail(reason: String): SomlException = SomlException("Cannot edit ${describePath(path, path.size)}, because $reason")

	/**
	Edits the path from `index` on, inside `node`, whose own depth is `depth`.
	*/
	private fun descend(node: SyntaxTree.ValueNode, index: Int, depth: Int) {
		when (node) {
			is SyntaxTree.ArrayNode -> editInArray(node, index, depth)
			is SyntaxTree.ObjectNode -> editInObject(node, index, depth)
			is SyntaxTree.ScalarNode -> throw fail("${describeParent(index)} is not an object or an array")
		}
	}

	private fun editInArray(array: SyntaxTree.ArrayNode, index: Int, depth: Int) {
		val position = path[index] as? Int ?: throw fail("${describeParent(index)} is an array, so it needs an index, not a key")
		val elements = array.elements

		if (position < 0) {
			throw fail("an index is 0 or more, not $position")
		}

		if (position >= elements.size) {
			// Removing an item that is not there changes nothing, at the end of the array or past it, and so does removing something below it.
			val newValue = value ?: return
			val count = elements.size

			if (position != count) {
				val owner = if (index == 0) "the document" else "the array at ${describePath(path, index)}"
				throw fail("$owner has ${if (count == 1) "1 item" else "$count items"}. Add an item at index $count")
			}

			val nested = nest(index + 1, newValue)
			insert(array, elements.lastOrNull()) { indentation -> valueText(nested, depth + 1, indentation, array) }
			return
		}

		val element = elements[position]

		when {
			index < path.size - 1 -> {
				isInsideOneLine = isOneLine(array)
				descend(element, index + 1, depth + 1)
			}
			value == null -> removeNode(array, element)
			else -> replace(element, value, depth + 1, array)
		}
	}

	private fun editInObject(objectNode: SyntaxTree.ObjectNode, index: Int, depth: Int) {
		val key = path[index] as? String ?: throw fail("${describeParent(index)} is an object, so it needs a key, not an index")
		val member = objectNode.member(key)

		if (member != null) {
			when {
				index < path.size - 1 -> {
					isInsideOneLine = isOneLine(objectNode)
					descend(member.value, index + 1, depth + 1)
				}
				value == null -> removeMember(objectNode, member)
				else -> replaceMemberValue(member, value, depth + 1, objectNode)
			}

			return
		}

		val newValue = value ?: return
		val nested = nest(index + 1, newValue)
		insert(objectNode, objectNode.members.lastOrNull()) { indentation -> formatKey(key) + ": " + valueText(nested, depth + 1, indentation, objectNode) }
	}

	private fun replace(node: SyntaxTree.ValueNode, newValue: SomlValue, depth: Int, container: SyntaxTree.Container) {
		replacement = Replacement(node.start, node.end, valueText(newValue, depth, indentation(node.start), container))
	}

	/**
	Replaces the value of `member`. A block string begins on the line after its key, and the new value is never a block string, so when only whitespace comes between the `:` and a block string, the new value goes on the key's line, as the formatter writes it.
	*/
	private fun replaceMemberValue(member: SyntaxTree.MemberNode, newValue: SomlValue, depth: Int, objectNode: SyntaxTree.ObjectNode) {
		// Nothing may come between a key and its `:`, so the `:` is the character right after the key.
		val colonEnd = member.key.end + 1
		val oldValue = member.value

		if (!(oldValue is SyntaxTree.ScalarNode && oldValue.isBlockString && skipWhitespace(colonEnd) == oldValue.start)) {
			replace(oldValue, newValue, depth, objectNode)
			return
		}

		replacement = Replacement(colonEnd, oldValue.end, " " + valueText(newValue, depth, indentation(member.start), objectNode))
	}

	/**
	Removes `member`. Removing the only member of a document without braces leaves `{}` in its place, because a document is never empty, and the comments that the member owned stay.
	*/
	private fun removeMember(objectNode: SyntaxTree.ObjectNode, member: SyntaxTree.MemberNode) {
		if (objectNode.isBraced || objectNode.members.size > 1) {
			removeNode(objectNode, member)
			return
		}

		replacement = Replacement(member.start, member.end, "{}")
	}

	/**
	Removes `node` from `container`. The removal also takes a blank line that would be left next to another one or directly inside a bracket. When the only item of a braced container goes and no comment is left inside, its brackets close up, as `[]`.
	*/
	private fun removeNode(container: SyntaxTree.Container, node: SyntaxTree.Item) {
		val items = container.items
		// When the removed item is the last one and has no comma after it, it takes the comma before it, so that no trailing comma is left, as `[1, 2]` becomes `[1]`.
		val shouldTakeCommaBefore = node === items.last() && code(skipTrivia(node.end)) != ','.code
		val removal = nodeRemoval(node, shouldTakeCommaBefore)

		if (removal.isWholeLines) {
			takeBlankLine(removal, container)
		}

		if (items.size == 1 && container.isBraced && !isCommentLeft(container, removal)) {
			replacement = Replacement(container.start + 1, container.end - 1, "")
			return
		}

		replacement = Replacement(removal.start, removal.end, "")
	}

	/**
	Whether a comment inside `container` is outside `removal`.
	*/
	private fun isCommentLeft(container: SyntaxTree.Container, removal: Removal): Boolean {
		val first = commentIndexAt(container.start + 1)
		val end = commentIndexAt(container.end)
		return first < end && (comments[first].start < removal.start || comments[end - 1].start >= removal.end)
	}

	/**
	What removing a member or an item, with the comments it owns and its comma, takes out of the text. With `shouldTakeCommaBefore`, a comma directly before it goes too.
	*/
	private fun nodeRemoval(node: SyntaxTree.Item, shouldTakeCommaBefore: Boolean): Removal {
		val start = ownedStart(node.start)
		val end = ownedEnd(node.end)
		val next = skipSpaces(end)
		// Only a braced container can have commas.
		val hasComma = code(next) == ','.code

		// The comma before it is only taken when spaces alone are between them and the comments it owns. The scan back only crosses spaces, so it stays short on a long line.
		val before = skipSpacesBack(start)
		val removalStart = if (shouldTakeCommaBefore && code(before - 1) == ','.code) before - 1 else start
		// The comments after its comma go with it when they end its line, as the formatter keeps them after it.
		val lineEndAfterComma = if (hasComma) findLineEndAfterTrivia(next + 1) else -1

		val removalEnd = when {
			!hasComma -> end
			lineEndAfterComma == -1 -> next + 1
			else -> lineEndAfterComma
		}

		val removal = removal(removalStart, removalEnd)

		// When it takes the comma before it, the spaces before a comment after it stay, so that the comment does not join the item before the comma.
		if (removalStart != start && !removal.isWholeLines && hasCommentAt(removal.end)) {
			removal.end = skipSpacesBack(removal.end)
		}

		return removal
	}

	/**
	The start of the block comments that a member or an item at `offset` owns before it: those on its line after the comma or the opening bracket before it, as the comment in `[1, /* note */ 2]`. The formatter keeps them in front of it. It is `offset` when there are none.
	*/
	private fun ownedStart(offset: Int): Int {
		var start = offset

		// The start of each block comment is found by its end, walking backwards through the ordered comments.
		// Note: Not using listIterator() for comment walks because complete comment-heavy edits measured slower on Native.
		for (position in (commentIndexAt(offset) - 1) downTo 0) {
			val comment = comments[position]

			if (!comment.isBlock || comment.end != skipSpacesBack(start)) {
				return start
			}

			start = comment.start
		}

		return start
	}

	/**
	The end of the comments that a member or an item ending at `offset` owns after it: those on its line before its comma or the closing bracket, and a line comment at the end of its line. The formatter keeps them after it. It is `offset` when there are none.
	*/
	private fun ownedEnd(offset: Int): Int {
		var end = offset

		// The end of each comment is found by its start, so that the trivia between tokens can be skipped.
		for (position in commentIndexAt(offset)..<comments.size) {
			val comment = comments[position]

			if (comment.start != skipSpaces(end)) {
				return end
			}

			end = comment.end
		}

		return end
	}

	/**
	The removal of the text from `start` to `end`. When nothing else is on its lines, it takes the lines, including a comment at the end of the last one.
	*/
	private fun removal(start: Int, end: Int): Removal {
		val lineEnd = findLineEndAfterTrivia(end)

		// The line start is only looked for when nothing follows on the line, because the scan back to it is long for an item in the middle of a long line, and doing it for every item would be quadratic.
		if (lineEnd != -1) {
			val lineStart = lineStart(start)

			if (skipSpaces(lineStart) == start) {
				return Removal(lineStart, minOf(lineEnd + 1, text.length), isWholeLines = true)
			}
		}

		// The spaces after the text go too, and the spaces before it when nothing follows it on the line or in its container.
		val after = skipSpaces(end)
		val code = code(after)
		val isLast = after == text.length || code == '\n'.code || code == '}'.code || code == ']'.code
		return Removal(if (isLast) skipSpacesBack(start) else start, after, isWholeLines = false)
	}

	/**
	Widens a removal of whole lines by a blank line that would otherwise be left next to another blank line, or directly inside a bracket. The container's content start is only needed when a blank line follows the removal.
	*/
	private fun takeBlankLine(removal: Removal, container: SyntaxTree.Container) {
		val isBlankBefore = removal.start > 0 && isBlankLine(lineStart(removal.start - 1))
		val isBlankAfter = removal.end < text.length && isBlankLine(removal.end)

		// With a blank line on both sides, the one before goes, as the spec says.
		if (isBlankBefore && (isBlankAfter || isAtClosing(removal.end, container))) {
			removal.start = lineStart(removal.start - 1)
		} else if (isBlankAfter && removal.start == contentStart(container)) {
			removal.end = text.indexOf('\n', removal.end) + 1
		}
	}

	/**
	The offset after the blank lines that start at `offset`.
	*/
	private fun skipBlankLines(offset: Int): Int {
		var position = offset

		while (position < text.length && isBlankLine(position)) {
			position = text.indexOf('\n', position) + 1
		}

		return position
	}

	/**
	Whether only blank lines come between the line at `offset` and the line that closes `container`, or the end of the text.
	*/
	private fun isAtClosing(offset: Int, container: SyntaxTree.Container): Boolean {
		val position = skipBlankLines(offset)
		return position == text.length || (container.isBraced && skipSpaces(position) == container.end - 1)
	}

	/**
	The start of the first line that is not blank after the line that opens `container`, or after the start of the text. The line that opens a container ends after the comments that follow its bracket, and when an item follows on it, this is -1.
	*/
	private fun contentStart(container: SyntaxTree.Container): Int {
		if (!container.isBraced) {
			return skipBlankLines(0)
		}

		val lineEnd = findLineEndAfterTrivia(container.start + 1)
		return if (lineEnd == -1) -1 else skipBlankLines(lineEnd + 1)
	}

	private fun isBlankLine(lineStart: Int): Boolean = code(skipSpaces(lineStart)) == '\n'.code

	private fun lineStart(offset: Int): Int = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1

	/**
	Adds an item after `previous`, or into the empty `container` when there is none. `makeItem` gets the indentation of the item's line. An item on its own line needs no comma, so only an item added on the line of the item before it gets one, as in a one-line container.
	*/
	private fun insert(container: SyntaxTree.Container, previous: SyntaxTree.Item?, makeItem: (String) -> String) {
		if (previous == null) {
			val closing = container.end - 1

			// A container that stays on one line keeps the item on that line, so `[/* note */]` becomes `[/* note */ 1]`, and `[1, []]` becomes `[1, [2]]`.
			if (isOneLine(container)) {
				val start = skipSpacesBack(closing)
				// One space after a comment before the item, and none directly inside the brackets.
				replacement = Replacement(start, closing, (if (start > container.start + 1) " " else "") + makeItem(""))
				return
			}

			val outer = indentation(container.start)
			val inner = outer + "\t"
			val lineStart = lineStart(closing)

			// A closing bracket on its own line keeps that line, and the item goes on a line before it.
			replacement = if (lineStart > container.start && skipSpaces(lineStart) == closing) {
				Replacement(lineStart, lineStart, inner + makeItem(inner) + "\n")
			} else {
				Replacement(skipSpacesBack(closing), closing, "\n" + inner + makeItem(inner) + "\n" + outer)
			}

			return
		}

		// The new item goes after the comments that the one before it owns, so that they stay with that one.
		val previousEnd = ownedEnd(previous.end)
		val next = skipTrivia(previousEnd)
		val hasComma = code(next) == ','.code
		val afterComma = if (hasComma) next + 1 else previousEnd
		val lineEnd = findLineEndAfterTrivia(afterComma)

		// Something follows on the same line, such as the closing bracket of a one-line object, so the item goes on that line too, and takes its indentation.
		if (lineEnd == -1) {
			val item = makeItem(indentation(afterComma))
			// After a comma, the new item gets a comma too, so `[1, 2,]` becomes `[1, 2, 3,]`.
			replacement = if (hasComma) Replacement(afterComma, afterComma, " $item,") else Replacement(previousEnd, previousEnd, ", $item")
			return
		}

		val indentation = indentation(previous.start)
		replacement = Replacement(lineEnd, lineEnd, "\n" + indentation + makeItem(indentation))
	}

	/**
	Whether `container` stays on one line: its brackets are on one line and something is between them, which the formatter keeps, or it is inside a container that stays on one line. An empty `[]` or `{}` on its own gets its new item on a line of its own.
	*/
	private fun isOneLine(container: SyntaxTree.Container): Boolean {
		if (isInsideOneLine) {
			return true
		}

		if (!container.isBraced || skipSpaces(container.start + 1) >= container.end - 1) {
			return false
		}

		return text.indexOfWithin('\n', container.start, container.end) == -1
	}

	/**
	A new value inside `container`. In a container on one line, the value is on one line too.
	*/
	private fun valueText(newValue: SomlValue, depth: Int, indentation: String, container: SyntaxTree.Container): String {
		try {
			return Writer.writeValueAt(newValue, depth, indentation, isOneLine(container))
		} catch (_: SomlException) {
			throw fail("the change would nest the document more than $MAX_DEPTH levels deep")
		}
	}

	/**
	The spaces and tabs at the start of the line that holds `offset`. A line that a block comment from an earlier line runs into, as in a comment that ends before `x: 1` on its last line, is indented as the line where that comment starts.
	*/
	private fun indentation(offset: Int): String {
		var lineStart = lineStart(offset)
		var comment = commentAcross(lineStart)

		while (comment != null) {
			lineStart = lineStart(comment.start)
			comment = commentAcross(lineStart)
		}

		return text.substring(lineStart, skipSpaces(lineStart))
	}

	/**
	The comment that starts before `offset` and ends after it, if any.
	*/
	private fun commentAcross(offset: Int): SyntaxTree.Comment? {
		val position = commentIndexAt(offset)

		if (position == 0) {
			return null
		}

		return comments[position - 1].takeIf { it.end > offset }
	}

	/**
	The first comment whose start is at or after `offset`. The comments never overlap and are in source order, so a binary search finds it.
	*/
	private fun commentIndexAt(offset: Int): Int {
		// Note: Not using binarySearchBy() because its generic comparison adds allocation to this integer lower-bound search.
		var low = 0
		var high = comments.size

		while (low < high) {
			val middle = (low + high) ushr 1

			if (comments[middle].start < offset) {
				low = middle + 1
			} else {
				high = middle
			}
		}

		return low
	}

	/**
	Skips spaces, tabs, comments, and also line feeds when `includesLineFeeds` is true. One binary search starts the scan, and each comment is visited once.
	*/
	private fun skipTrivia(offset: Int, includesLineFeeds: Boolean = true): Int {
		var position = commentIndexAt(offset)
		var current = offset

		while (current < text.length) {
			val character = text[current]

			when {
				character.code.isSpaceCode() || (includesLineFeeds && character == '\n') -> current++
				position < comments.size && comments[position].start == current -> current = comments[position++].end
				else -> return current
			}
		}

		return current
	}

	private fun hasCommentAt(offset: Int): Boolean {
		val position = commentIndexAt(offset)
		return position < comments.size && comments[position].start == offset
	}

	/**
	The offset of the line break that ends the line at `offset`, or the end of the text, when only whitespace and comments come before it. Otherwise -1.
	*/
	private fun findLineEndAfterTrivia(offset: Int): Int {
		val end = skipTrivia(offset, includesLineFeeds = false)
		return if (end == text.length || code(end) == '\n'.code) end else -1
	}

	/**
	Skips spaces, tabs, and line feeds, but not comments.
	*/
	private fun skipWhitespace(offset: Int): Int {
		var position = offset

		while (code(position).isSpaceCode() || code(position) == '\n'.code) {
			position++
		}

		return position
	}

	private fun skipSpaces(offset: Int): Int {
		var position = offset

		while (code(position).isSpaceCode()) {
			position++
		}

		return position
	}

	private fun skipSpacesBack(offset: Int): Int {
		var position = offset

		while (position > 0 && text[position - 1].code.isSpaceCode()) {
			position--
		}

		return position
	}

	private fun code(offset: Int): Int = if (offset in text.indices) text[offset].code else -1

	/**
	`newValue` inside new objects for the keys of the path from `index` on. An index there names an item of an array that does not exist yet.
	*/
	private fun nest(index: Int, newValue: SomlValue): SomlValue {
		if (path.size > MAX_DEPTH) {
			throw fail("the change would nest the document more than $MAX_DEPTH levels deep")
		}

		var nested = newValue

		// Note: Not using asReversed() because its views allocated more in the measured public nested edits.
		for (position in (path.size - 1) downTo index) {
			when (val element = path[position]) {
				is Int -> throw fail("${describeParent(position)} does not exist, so it has no index $element")
				else -> nested = SomlObject.trusted(mapOf(element as String to nested))
			}
		}

		return nested
	}

	/**
	The value that holds the path element at `index`.
	*/
	private fun describeParent(index: Int): String = if (index == 0) "the document" else describePath(path, index)

	companion object {
		/**
		Sets `path` to `value`, or removes it when `value` is null, in a text that the parser has already validated.

		@throws SomlException When the path does not fit the document.
		*/
		fun edit(text: String, path: List<Any>, value: SomlValue?): Result {
			require(path.isNotEmpty()) { "The path is empty. Give the keys and indexes of the value to change" }
			val tree = SyntaxTree.ofValid(text)
			val editor = Editor(tree, path, value)
			editor.descend(tree.body, 0, 1)
			val replacement = editor.replacement ?: return Result(text, null)

			// An edit follows one path and replaces one range, including any new objects on that path.
			// Note: Not using replaceRange() because its unsized builder grows and copies buffers, while this replacement reserves the final length.
			// Note: Not using StringBuilder.setRange() because copying the entire input before replacing a range also moves its suffix, while this construction copies each retained range once.
			val edited = buildString(text.length - (replacement.end - replacement.start) + replacement.text.length) {
				append(text, 0, replacement.start)
				append(replacement.text)
				append(text, replacement.end, text.length)
			}

			try {
				return Result(edited, Parser.parse(edited))
			} catch (error: SomlException) {
				throw IllegalStateException("The edit of ${describePath(path, path.size)} made a document that is not valid. This is a bug in the SOML library", error)
			}
		}

		/**
		The first `length` elements of a path, as in `servers[0].port`, cut short when long.
		*/
		fun describePath(path: List<Any>, length: Int): String {
			val description = StringBuilder()

			// Note: Not using subList() because the indexed loop already expresses this prefix without another view.
			for (index in 0..<length) {
				when (val element = path[index]) {
					is Int -> description.append('[').append(element).append(']')
					else -> {
						if (index > 0) {
							description.append('.')
						}

						description.append(describeKey(element as String))
					}
				}

				if (description.length > 200) {
					break
				}
			}

			return abbreviate(description.toString(), 200)
		}
	}
}
