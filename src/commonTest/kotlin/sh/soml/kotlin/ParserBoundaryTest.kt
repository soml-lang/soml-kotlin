package sh.soml.kotlin

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
Cross-checks optimized parsing and writing at the boundaries between their fast and general paths.
*/
class ParserBoundaryTest {
	@Test
	fun `short decimal floats preserve exact binary fractions`() {
		val random = Random(0x50A1)

		repeat(5000) {
			val integer = random.nextLong(0, 100_000_000)
			val fraction = random.nextInt(0, 32)
			val sign = if (random.nextBoolean()) -1 else 1
			val fractionText = (fraction * 3125).toString().padStart(5, '0')
			val text = "${if (sign < 0) "-" else ""}$integer.$fractionText"
			val value = (Parser.parse("[$text]") as SomlArray).single() as SomlFloat
			// Both the integer and the fraction are exactly representable, independently of a platform's decimal parser.
			assertEquals((integer.toDouble() + fraction / 32.0) * sign + 0.0, value.value, text)
		}
	}

	@Test
	fun `short nonbinary decimal fractions round to independently verified bits`() {
		// Python's decimal arithmetic independently checked that each selected float is closer than both neighbors.
		val cases = listOf(
			"26272490.41842" to 0x41790E2EA6B1D92BL,
			"0.1" to 0x3FB999999999999AL,
			"0.3" to 0x3FD3333333333333L,
			"99999999.99999" to 0x4197D783FFFFFD61L,
			"999999999999.99" to 0x426D1A94A1FFFFAEL,
			"9999999999999.9" to 0x42A2309CE53FFFCDL,
			"0.0000000000001" to 0x3D3C25C268497682L,
			"0.0000000000002" to 0x3D4C25C268497682L,
			"9007199254740.1" to 0x42A0624DD2F1A833L,
			"8388608.000001" to 0x4160000000000219L,
			"16777216.00001" to 0x4170000000000A7CL,
			"0.10000000000001" to 0x3FB9999999999C6AL,
			"1.0000000000001" to 0x3FF00000000001C2L,
			"12345678.901234" to 0x41678C29DCD6E8B0L,
			"9999999999999.5" to 0x42A2309CE53FFF00L,
		)

		for ((text, bits) in cases) {
			val positive = (Parser.parse("[$text]") as SomlArray).single() as SomlFloat
			val negative = (Parser.parse("[-$text]") as SomlArray).single() as SomlFloat
			assertEquals(bits, positive.value.toRawBits(), text)
			assertEquals(bits or Long.MIN_VALUE, negative.value.toRawBits(), "-$text")
		}
	}

	@Test
	fun `integer fast path and full range keep their signed values`() {
		for (value in listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -1_000_000_000_000_000_000L, -999_999_999_999_999_999L, -1L, 0L, 1L, 999_999_999_999_999_999L, 1_000_000_000_000_000_000L, Long.MAX_VALUE)) {
			assertEquals(SomlInt(value), (Parser.parse("[$value]") as SomlArray).single())
		}
	}

	@Test
	fun `instant offsets normalize across day year and leap boundaries`() {
		for ((text, normalized) in listOf("0001-01-01T23:59:59.000000001+23:59" to "0001-01-01T00:00:59.000000001Z", "1900-03-01T00:00:00+00:01" to "1900-02-28T23:59:00Z", "2000-03-01T00:00:00+00:01" to "2000-02-29T23:59:00Z", "2026-01-01T00:00:00+23:59" to "2025-12-31T00:01:00Z", "1969-12-31T23:59:59.999999999Z" to "1969-12-31T23:59:59.999999999Z", "9999-12-31T00:00:00-23:59" to "9999-12-31T23:59:00Z")) {
			val expected = Instant.parse(normalized)
			val actual = (Parser.parse("[$text]") as SomlArray).single() as SomlInstant
			assertEquals(expected, actual.value, text)
			assertEquals(normalized, actual.toString(), text)
		}
	}

	@Test
	fun `nested comment opener cannot overlap its closing delimiter`() {
		for (comment in listOf("/**/", "/*/ */", "/* /*/", "/* note /*/")) {
			assertEquals(SomlArray(SomlInt(1)), Parser.parse("[$comment 1]"), comment)
		}

		assertFailsWith<SomlException> {
			Parser.parse("[/* /* */ 1]")
		}
	}

	@Test
	fun `unterminated comments keep priority over a nested opener`() {
		val error = assertFailsWith<SomlException> {
			Parser.parse("[/* /*")
		}
		assertEquals("Unterminated block comment", error.reason)
		assertEquals(2, error.column)
	}

	@Test
	fun `block strings validate controls even on otherwise blank lines`() {
		for (quote in listOf("'''", "\"\"\"")) {
			val error = assertFailsWith<SomlException> {
				Parser.parse("a: $quote\n\t\u0000\n\t$quote")
			}
			assertEquals(2, error.line)
			assertEquals(2, error.column)
		}
	}

	@Test
	fun `blank block strings do not retain indentation`() {
		for (quote in listOf("'''", "\"\"\"")) {
			for (content in listOf("", "\n", " \t\n\t\n")) {
				assertEquals(SomlObject("a" to SomlString("")), Parser.parse("a: $quote\n$content\t$quote"))
			}
		}
	}

	@Test
	fun `long duration fractions ignore zero tails without losing precision`() {
		assertEquals(SomlArray(SomlDuration(1)), Parser.parse("[0.000000001${"0".repeat(20_000)}s]"))
		assertEquals(SomlArray(SomlDuration(1)), Parser.parse("[0.0_0_0_0_0_0_0_0_1${"_0".repeat(10_000)}s]"))
		val error = assertFailsWith<SomlException> {
			Parser.parse("[0.${"1".repeat(20_000)}s]")
		}
		assertTrue(error.reason.endsWith("it is not a whole number of nanoseconds"))
	}

	@Test
	fun `a fractional duration keeps unit validation ahead of precision errors`() {
		val error = assertFailsWith<SomlException> {
			Parser.parse("[0.${"1".repeat(20_000)}msms]")
		}
		assertTrue(error.reason.contains("unit"))
	}

	@Test
	fun `depth limit counts empty containers and allows scalar leaves`() {
		assertEquals("[".repeat(100) + "1" + "]".repeat(100), Parser.parse("[".repeat(100) + "1" + "]".repeat(100)).let { Writer.writeValueAt(it, 1, "", isOneLine = true) })
		for (leaf in listOf("[]", "{}")) {
			val error = assertFailsWith<SomlException> {
				Parser.parse("[".repeat(100) + leaf + "]".repeat(100))
			}
			assertEquals(101, error.column)
		}
	}

	@Test
	fun `quoted keys use the same escapes as string values`() {
		for (key in listOf("plain", "with\ttab", "with\nline", "quote\"and'apostrophe", "\\", "astral 🦄")) {
			val encoded = SomlString(key).toString()
			val source = "$encoded: $encoded"
			assertEquals(SomlObject(key to SomlString(key)), Parser.parse(source))
			val tree = SyntaxTree.parse(source)
			assertEquals(encoded.length + 2, tree.offsetOf(listOf(key)))
		}
	}

	@Test
	fun `scalar order compares supplementary keys after the high BMP`() {
		val keys = listOf("🦄", "\uE000", "\uFFFF", "a", "🀀", "😀")
		val sorted = keys.sortedWith(::compareKeys)
		assertEquals(listOf("a", "\uE000", "\uFFFF", "🀀", "😀", "🦄"), sorted)
	}

	@Test
	fun `indentation chunks have exact lengths at every boundary`() {
		for (count in listOf(0, 1, 100, 101, 102, 103, 204, 205, 1000)) {
			assertEquals("\t".repeat(count), buildString { appendTabs(count) })
		}
	}

	@Test
	fun `float layout handles alternate platform spellings`() {
		for ((value, spelling) in listOf(1.0 to "1.0", 100.0 to "100.0", 0.00001 to "0.00001000", 1e21 to "1e+21", 1.2345e-7 to "1.234500E-7", 1000000.0 to "1000000")) {
			assertEquals(formatFloat(value), formatFloat(value, spelling), spelling)
		}
	}
}
