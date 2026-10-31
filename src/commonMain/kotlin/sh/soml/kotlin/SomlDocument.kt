package sh.soml.kotlin

/**
A document that you change one value at a time, keeping its comments, blank lines, indentation, member order, and the spelling of every value outside the change.

```kotlin
val document = SomlDocument.parse("""
	# The edge service.
	port: 0x1F90 # The default.
	server: {host: 'a'}
	""".trimIndent())

document.set("port", value = SomlInt(8080))
document.set("server", "port", value = SomlInt(443))
document.remove("server", "host")

document.toString()
//=> "# The edge service.\nport: 8080 # The default.\nserver: {port: 443}\n"
```

A path is the keys (`String`) and array indexes (`Int`) from the top of the document to a value. Changes follow the editing rules of the spec, so a change to a formatted document leaves it formatted:

- A new value is written as `Soml.encodeToString` writes it, and on one line inside a container that is on one line, so `{host: 'a'}` becomes `{host: 'a', port: 443}`.
- A new member goes after the last member of its object, and missing objects on the path are created. A new item goes at the end of its array, at the index that is its length.
- A removed member or item takes its comma and the comments it owns, such as one after it on its line. A comment on a line of its own stays.
- Removing a path that does not exist is not an error, and `remove()` returns whether it removed something. A path that does not fit the document, such as a key where an array is, is an error.

An unchanged document prints the exact text it was parsed from. A document is not safe to change from several threads at once.
*/
public class SomlDocument private constructor(
	private var text: String,
	value: SomlValue,
) {
	/**
	The whole document as a value, which is always a `SomlObject` or a `SomlArray`.
	*/
	public var value: SomlValue = value
		private set

	/**
	The value at `path`, or null when there is none. An empty path is the whole document.

	@throws IllegalArgumentException When an element of the path is not a `String` or an `Int`.
	*/
	public operator fun get(vararg path: Any): SomlValue? {
		path.checkPath()
		// Note: Not using fold() because the direct path loop measured faster despite the built-in being inline.
		var current: SomlValue = value

		for (element in path) {
			current = when (element) {
				is String -> (current as? SomlObject)?.get(element)
				is Int -> (current as? SomlArray)?.getOrNull(element)
				else -> throw IllegalArgumentException("A path holds keys (String) and indexes (Int), not $element")
			} ?: return null
		}

		return current
	}

	/**
	Sets the value at `path`, replacing the value that is there, or adding it with any missing objects on the way.

	@throws SomlException When the path does not fit the document, such as a key where an array is, or an index past the end of an array.
	@throws IllegalArgumentException When the path is empty, an element of it is not a `String` or an `Int`, or a key holds U+000D or a lone surrogate.
	*/
	public fun set(vararg path: Any, value: SomlValue) {
		edit(path, value)
	}

	/**
	Removes the value at `path`, and returns whether there was one.

	@throws SomlException When the path does not fit the document, such as a key where an array is.
	@throws IllegalArgumentException When the path is empty, or an element of it is not a `String` or an `Int`.
	*/
	public fun remove(vararg path: Any): Boolean = edit(path, null)

	private fun edit(path: Array<out Any>, newValue: SomlValue?): Boolean {
		path.checkPath()

		// Note: Not using filterIsInstance() because validating the keys directly avoids building a temporary list.
		for (element in path) {
			// A new key is written into the document, so it must be one that a document can hold.
			if (element is String) {
				checkString(element)
			}
		}

		val result = Editor.edit(text, path.asList(), newValue)
		val editedValue = result.value ?: return false
		text = result.text
		value = editedValue
		return true
	}

	/**
	The text of the document, with the changes.
	*/
	override fun toString(): String = text

	public companion object {
		/**
		Parses `text` into a document, which prints the exact text until it is changed.

		@throws SomlException When the document is not valid.
		*/
		public fun parse(text: String): SomlDocument = SomlDocument(text, Parser.parse(text))
	}
}

/**
Sets the value at `path` to `value`, converted to a SOML value by `soml`, as in `document.set("server", "port", value = 443)`.

@throws SomlException When the path does not fit the document, or the value cannot be converted.
*/
public inline fun <reified T> SomlDocument.set(vararg path: Any, value: T, soml: Soml = Soml) {
	set(*path, value = soml.encodeToSomlValue(value))
}

/**
Checks every component before a missing value can end path traversal early.
*/
private fun Array<out Any>.checkPath() {
	// Note: Not using firstOrNull() because this loop validates each component directly without an intermediate nullable result.
	for (element in this) {
		require(element is String || element is Int) { "A path holds keys (String) and indexes (Int), not $element" }
	}
}
