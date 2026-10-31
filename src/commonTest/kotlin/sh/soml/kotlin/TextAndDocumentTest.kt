package sh.soml.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TextAndDocumentTest {
	@Test
	fun `validates the entire lookup path before traversing missing values`() {
		val document = SomlDocument.parse("a: 1")

		for (path in listOf(arrayOf<Any>("missing", true), arrayOf<Any>("a", false))) {
			assertFailsWith<IllegalArgumentException> {
				document.get(*path)
			}
		}
	}

	@Test
	fun `placing a serializer error keeps its wrapper and underlying cause`() {
		val cause = IllegalArgumentException("underlying failure")
		val original = SomlException("serializer context", cause)
		val placed = original.placed(listOf("settings"))

		assertSame(original, placed.cause)
		assertSame(cause, placed.cause?.cause)
		assertEquals(listOf("settings"), placed.path)
	}

	@Test
	fun `hexadecimal padding is a minimum width`() {
		assertEquals("ABCDEF", buildString { appendHex(0xABCDEF, 2) })
		assertEquals("00000a", buildString { appendHex(10, 6, uppercase = false) })
		assertEquals("0", buildString { appendHex(0, 0) })
	}

	@Test
	fun `character searches never inspect text outside their range`() {
		val text = "\nxx,\\comment\n尾😀"

		for (start in 0..text.length) {
			for (end in start..text.length) {
				for (character in "\n,\\尾") {
					val relative = text.substring(start, end).indexOf(character)
					val expected = if (relative == -1) -1 else start + relative
					assertEquals(expected, text.indexOfWithin(character, start, end))
				}
			}
		}
	}

	@Test
	fun `code point labels share padded uppercase hexadecimal spelling`() {
		for ((value, expected) in mapOf(0 to "U+0000", 0x85 to "U+0085", 0xFFFF to "U+FFFF", 0x1F600 to "U+1F600", 0x10FFFF to "U+10FFFF")) {
			assertEquals(expected, formatCodePoint(value))
		}
	}

	@Test
	fun `failed edits preserve both the text and value snapshot`() {
		val text = "# preserved\na: [1, 2]\n"
		val document = SomlDocument.parse(text)
		val original = document.value

		assertFailsWith<SomlException> {
			document.set("a", 4, value = SomlInt(3))
		}

		assertEquals(text, document.toString())
		assertSame(original, document.value)
		assertTrue(document.remove("a", 0))
		assertEquals(SomlInt(2), document["a", 0])
	}
}
