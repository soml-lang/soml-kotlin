package sh.soml.kotlin

import kotlinx.serialization.SerializationException

/**
Thrown when a document is not valid, when a valid document does not fit the type it is decoded to, or when a value cannot be encoded.

The message has the path, the reason, the position, and a code frame:

```
servers[1].port: Expected an int, but found the string 'eighty' at line 4, column 9

  3 | servers: [
> 4 | 	{port: 'eighty'}
    | 	       ^
```

It is a `SerializationException`, so code that catches the errors of every kotlinx.serialization format catches it too.

A document can hold characters that a terminal acts on, so the reason and the code frame replace control characters, bidirectional controls, and lone surrogates with U+FFFD.
*/
public class SomlException private constructor(
	/**
	What is wrong, without the path and the position.
	*/
	public val reason: String,
	/**
	The path to the value the error is about, as keys (`String`) and array indexes (`Int`), from the top of the document. It is empty for a syntax error, and for an error about the document as a whole.
	*/
	public val path: List<Any>,
	/**
	The 1-based line of the error, or 0 when it has no position, as when encoding.
	*/
	public val line: Int,
	/**
	The 1-based column of the error, counted in Unicode scalar values, so an emoji is one column, or 0 when it has no position.
	*/
	public val column: Int,
	/**
	Up to three lines ending at the error, with a caret under its position, or an empty string when it has no position.
	*/
	public val codeFrame: String,
	/**
	The UTF-16 index of the error in the text, or -1 when it has no position.
	*/
	internal val offset: Int,
	cause: Throwable?,
) : SerializationException(createMessage(reason, path, line, column, codeFrame), cause) {
	/**
	An error with a reason and no position, as for a custom serializer that rejects a value. The decoder adds the path and the position of the value.
	*/
	public constructor(reason: String, cause: Throwable? = null) : this(sanitize(reason), emptyList(), 0, 0, "", -1, cause) {
		isPlaced = false
	}

	/**
	Whether the error has the path of its value. One made with the public constructor, as by a serializer, does not, and the decoder or encoder of the value gives it its path.
	*/
	internal var isPlaced: Boolean = true
		private set

	/**
	This error at `path`, when it has no path yet.
	*/
	internal fun placed(path: List<Any>): SomlException = if (isPlaced) this else atPath(reason, path, this)

	/**
	Whether the error is about the key at the end of `path` rather than its value, as for an unknown key, so that its position is the key's.
	*/
	internal var isAboutKey: Boolean = false

	/**
	The path as the message shows it, such as `servers[1].port`, or an empty string for the top of the document.
	*/
	public val pathString: String
		get() = sanitize(formatPath(path))

	/**
	This error with a position in `source` at `offset`, for an error of the decoder.
	*/
	internal fun located(source: String, offset: Int): SomlException = at(reason, source, offset, path, cause).also {
		it.isAboutKey = isAboutKey
	}

	internal companion object {
		private const val CONTEXT_LINES = 2
		private const val MAX_LINE_WIDTH = 100

		/**
		An error at `offset` in `source`, a UTF-16 index, with the path to the value it is about, which is empty for a syntax error.
		*/
		fun at(reason: String, source: String, offset: Int, path: List<Any> = emptyList(), cause: Throwable? = null): SomlException {
			var line = 1
			var lineStart = 0
			var index = source.indexOfWithin('\n', 0, offset)

			while (index != -1 && index < offset) {
				line++
				lineStart = index + 1
				index = source.indexOfWithin('\n', lineStart, offset)
			}

			val column = source.codePointCountBetween(lineStart, offset) + 1
			return SomlException(sanitize(reason), path.toList(), line, column, createCodeFrame(source, offset, line), offset, cause)
		}

		/**
		An error with a path and no position, as when encoding.
		*/
		fun atPath(reason: String, path: List<Any>, cause: Throwable? = null): SomlException = SomlException(sanitize(reason), path.toList(), 0, 0, "", -1, cause)

		private fun createMessage(reason: String, path: List<Any>, line: Int, column: Int, codeFrame: String): String {
			val pathString = sanitize(formatPath(path))
			val message = if (pathString.isEmpty()) reason else "$pathString: $reason"
			return if (line == 0) message else "$message at line $line, column $column\n\n$codeFrame"
		}

		private fun createCodeFrame(source: String, offset: Int, line: Int): String {
			// Scan backward from the error for its line and up to two lines before it, rather than splitting the whole document.
			val lineStarts = IntArray(CONTEXT_LINES + 1)
			lineStarts[0] = source.lastIndexOf('\n', offset - 1) + 1
			var count = 1

			while (count < lineStarts.size && lineStarts[count - 1] > 0) {
				// The line feed that ends the line before is just before this line's start, so the search starts before it.
				lineStarts[count] = source.lastIndexOf('\n', lineStarts[count - 1] - 2) + 1
				count++
			}

			val gutterWidth = line.toString().length

			return buildString {
				for (index in (count - 1) downTo 0) {
					val lineStart = lineStarts[index]
					val number = line - index
					val isErrorLine = index == 0
					// Only the displayed window matters, so the rest of a minified document is not scanned for its line end.
					val searchStart = if (isErrorLine) offset else lineStart
					val searchEnd = searchStart + minOf(source.length - searchStart, MAX_LINE_WIDTH + 1)
					val lineEnd = source.indexOfWithin('\n', searchStart, searchEnd).takeUnless { it == -1 } ?: searchEnd
					val lineLength = lineEnd - lineStart
					var start = 0
					var end = lineLength
					var pointerOffset = if (isErrorLine) offset - lineStart else 0

					// A long line, such as a minified document, is cut to a window around the pointer. The cut never splits a surrogate pair.
					if (lineLength > MAX_LINE_WIDTH) {
						start = (pointerOffset - (MAX_LINE_WIDTH / 2)).coerceIn(0, lineLength - MAX_LINE_WIDTH)
						end = start + MAX_LINE_WIDTH

						if (start > 0 && isSurrogatePair(source[lineStart + start - 1], source[lineStart + start])) {
							start++
						}

						if (end < lineLength && isSurrogatePair(source[lineStart + end - 1], source[lineStart + end])) {
							end--
						}
					}

					val prefix = if (start > 0) "…" else ""
					val suffix = if (end < lineLength) "…" else ""
					pointerOffset = pointerOffset - start + prefix.length
					val lineText = sanitize(source.substring(lineStart + start, lineStart + end))

					if (isNotEmpty()) {
						append('\n')
					}

					append(if (isErrorLine) '>' else ' ').append(' ')
					append(number.toString().padStart(gutterWidth)).append(" |")

					if (lineText.isNotEmpty() || prefix.isNotEmpty() || suffix.isNotEmpty()) {
						append(' ').append(prefix).append(lineText).append(suffix)
					}

					if (isErrorLine) {
						// Tabs are kept, so the caret lines up whatever the tab width is.
						append("\n  ").append(" ".repeat(gutterWidth)).append(" | ")
						val paddingEnd = minOf(pointerOffset - prefix.length, lineText.length)

						if (prefix.isNotEmpty() && pointerOffset > 0) {
							append(' ')
						}

						// One space per code point before the caret, so that an emoji takes one.
						var position = 0

						while (position < paddingEnd) {
							append(if (lineText[position] == '\t') '\t' else ' ')
							position += charCountOf(lineText.codePointAtOffset(position))
						}

						append('^')
					}
				}
			}
		}
	}
}
