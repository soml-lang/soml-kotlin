package sh.soml.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParserBuiltInTest {
	@Test
	fun `whole-token number diagnostics stop before a following line feed`() {
		val cases = listOf(
			"1E2" to "An exponent marker is a lowercase “e”",
			"12:30" to "A time of day is a string, so it must be quoted",
			"1:2" to "Invalid number “1:2”. A value that contains “:” must be quoted, as in '1:2'",
			"1e-0" to "“e-0” is not allowed, because an exponent of zero has one spelling: e0",
			"1e" to "Expected digits after the exponent marker “e”",
			"-1e-" to "Expected digits after the exponent marker “e”",
		)

		for ((token, reason) in cases) {
			for (ending in listOf("", "\n", "\nnext: 1")) {
				val error = assertFailsWith<SomlException> {
					Soml.parseToSomlValue("value: $token$ending")
				}
				assertEquals(reason, error.reason, token)
				assertEquals(1, error.line)
				assertEquals(8, error.column)
			}
		}
	}

	@Test
	fun `diagnostic character edges preserve negative numbers and lowercase UTC errors`() {
		for ((token, reason) in listOf("-01" to "Leading zeros are not allowed in a decimal number", "2026-01-01T00:00:00z" to "The UTC offset in an instant is an uppercase “Z”")) {
			for (ending in listOf("", "\n", "\nnext: 1")) {
				val error = assertFailsWith<SomlException> {
					Soml.parseToSomlValue("value: $token$ending")
				}
				assertEquals(reason, error.reason, token)
				assertEquals(1, error.line)
				assertEquals(8, error.column)
			}
		}
	}

	@Test
	fun `octal hints retain a zero digit and strip only the leading zeros`() {
		for ((text, octal) in listOf("00" to "0o0", "00000" to "0o0", "000750" to "0o750", "0".repeat(MAX_DIAGNOSED_LENGTH - 1) + "7" to "0o7", "0".repeat(MAX_DIAGNOSED_LENGTH) to "0o0")) {
			val error = assertFailsWith<SomlException> {
				Soml.parseToSomlValue("mode: $text")
			}
			assertTrue(error.reason.contains("as $octal, and"), error.reason)
			assertEquals(1, error.line)
			assertEquals(7, error.column)
		}
	}

	@Test
	fun `block line searches preserve long Unicode lines and a closing delimiter at EOF`() {
		val firstLine = "é😀" + "x".repeat(4096)
		val expected = SomlObject("value" to SomlString("$firstLine\nlast"))

		for (quote in listOf("'''", "\"\"\"")) {
			val source = "value: $quote\n\t$firstLine\n\tlast\n\t$quote"
			assertEquals(expected, Soml.parseToSomlValue(source))
			assertEquals(expected, Soml.parseToSomlValue("$source\n"))
			assertEquals(SomlObject("value" to SomlString("$firstLine\nlast"), "next" to SomlBool.True), Soml.parseToSomlValue("$source\nnext: true"))
		}
	}

	@Test
	fun `block strings preserve Unicode spaces as content`() {
		for (quote in listOf("'''", "\"\"\"")) {
			for (content in listOf("\u00A0", "\u2003", "\u202F")) {
				val source = "value: $quote\n \t\n\t$content\n \t\n\t$quote"
				assertEquals(SomlObject("value" to SomlString(content)), Soml.parseToSomlValue(source))
			}
		}
	}
}
