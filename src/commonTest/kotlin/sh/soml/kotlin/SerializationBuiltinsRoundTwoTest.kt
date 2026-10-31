package sh.soml.kotlin

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SerializationBuiltinsRoundTwoTest {
	@Test
	fun `boolean map keys stay strict and invalid nested keys retain their positions`() {
		val expected = mapOf("flags" to linkedMapOf(true to 1, false to 2))
		assertEquals(expected, Soml.decodeFromString<Map<String, Map<Boolean, Int>>>("flags: {true: 1, false: 2}"))
		assertEquals(expected, Soml.decodeFromString<Map<String, Map<Boolean, Int>>>(Soml.encodeToString(expected)))

		for (key in listOf("TRUE", "False", "true ", " false")) {
			val error = assertFailsWith<SomlException> {
				Soml.decodeFromString<Map<String, Map<Boolean, Int>>>("flags: {\n\t'$key': 1\n}")
			}

			assertEquals(listOf("flags", key), error.path)
			assertEquals(2, error.line)
			assertEquals(2, error.column)
			assertTrue(error.reason.contains("true or false"), error.reason)
		}
	}
}
