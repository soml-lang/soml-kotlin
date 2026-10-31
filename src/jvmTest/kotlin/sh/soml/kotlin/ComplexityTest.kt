package sh.soml.kotlin

import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
Checks that the time of each operation grows linearly with the size of its input, for the shapes where a scan that is not bounded by its range would make it quadratic: 8 times the input must take about 8 times as long, not 64 times.
*/
class ComplexityTest {
	private fun assertLinear(name: String, makeInput: (Int) -> String, work: (String) -> Unit) {
		val small = makeInput(2_000)
		val large = makeInput(16_000)

		fun bestTime(input: String): Long = (1..5).minOf {
			val start = System.nanoTime()
			work(input)
			System.nanoTime() - start
		}

		// Warm up the JIT compiler on both sizes before measuring.
		repeat(3) {
			work(small)
			work(large)
		}

		val ratio = bestTime(large).toDouble() / bestTime(small)
		assertTrue(ratio < 24, "$name took ${"%.1f".format(ratio)} times as long for 8 times the input")
	}

	@Test
	fun `formats a one-line array of objects`() {
		assertLinear("format", { count -> "a: [" + "{b: 1, c: 'x'}, ".repeat(count) + "]\n" }) {
			Soml.format(it)
		}
	}

	@Test
	fun `formats a long line of block comments`() {
		assertLinear("format", { count -> "a: [" + "1 /* note */, ".repeat(count) + "]\n" }) {
			Soml.format(it)
		}
	}

	@Test
	fun `formats many lines`() {
		assertLinear("format", { count -> (0..<count).joinToString("\n") { "key$it: [1, 2] # note\n\n" } }) {
			Soml.format(it)
		}
	}

	@Test
	fun `locates a decoding error among many quoted keys`() {
		assertLinear("decode", { count -> (0..<count).joinToString("\n", postfix = "\n\"last\": 'x'") { "\"key\\t$it\": $it" } }) { text ->
			assertFailsWith<SomlException> {
				Soml.decodeFromString<Map<String, Int>>(text)
			}
		}
	}

	@Test
	fun `edits the last of many quoted keys`() {
		assertLinear("edit", { count -> (0..<count).joinToString("\n", postfix = "\n") { "\"key\\t$it\": $it" } }) { text ->
			SomlDocument.parse(text).set("key\t${text.count { it == '\n' } - 1}", value = SomlInt(0))
		}
	}

	@Test
	fun `parses long tokens and comments`() {
		assertLinear("parse", { count -> "a: '" + "x".repeat(count * 10) + "'\n/* " + "y".repeat(count * 10) + " */\nb: \"" + "\\t".repeat(count) + "\"\n" }) {
			Soml.parseToSomlValue(it)
		}
	}

	@Test
	fun `reports an error at the end of a long line`() {
		assertLinear("error", { count -> "a: [" + "1, ".repeat(count) + "x]\n" }) { text ->
			assertFailsWith<SomlException> {
				Soml.parseToSomlValue(text)
			}
		}
	}
}
