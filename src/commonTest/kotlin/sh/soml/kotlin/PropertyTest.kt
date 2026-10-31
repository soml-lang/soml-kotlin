package sh.soml.kotlin

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Instant

/**
Properties that hold for every input, checked on seeded random inputs.
*/
class PropertyTest {
	private val corpus: List<String> = corpusFiles("valid").map { it.readText() } + corpusFiles("invalid").map { it.readText() }
	private val mutationAlphabet = buildString {
		append("\n \t,:{}[]'\"#/*\\_.-+e0919hmsTZé😀\u0000\r")
		append(0xD800.toChar())
	}

	/**
	Runs `check` on each text, and turns any exception other than `SomlException` into a failure that names the text.
	*/
	private fun forEachText(texts: Sequence<String>, check: (String) -> Unit) {
		for (text in texts) {
			try {
				check(text)
			} catch (_: SomlException) {
				// An invalid document is expected among random inputs.
			} catch (error: Throwable) {
				fail("Failed for ${quoteJson(abbreviate(text, 400))}", error)
			}
		}
	}

	private fun mutations(random: Random): Sequence<String> = corpus.asSequence().flatMap { text ->
		sequenceOf(text) + generateSequence { 0 }.take(4).map { _ ->
			val builder = StringBuilder(text)

			repeat(random.nextInt(1, 4)) {
				val position = random.nextInt(builder.length + 1)

				when (random.nextInt(3)) {
					0 -> builder.insert(position, mutationAlphabet[random.nextInt(mutationAlphabet.length)])
					1 -> if (position < builder.length) {
						builder.deleteAt(position)
					}
					else -> if (builder.isNotEmpty()) {
						val start = random.nextInt(builder.length)
						builder.insertRange(position, builder.toString(), start, minOf(builder.length, start + random.nextInt(1, 20)))
					}
				}
			}

			builder.toString()
		}
	}

	@Test
	fun `formatting keeps the value and is idempotent`() {
		forEachText(mutations(Random(1))) { text ->
			val value = Parser.parse(text)
			val formatted = Formatter.format(text)
			assertEquals(value, Parser.parse(formatted), "the value of the formatted text")
			assertEquals(formatted, Formatter.format(formatted), "formatting twice")
		}
	}

	@Test
	fun `canonical form reads back as the same value and is a fixed point`() {
		forEachText(mutations(Random(2))) { text ->
			val value = Parser.parse(text)
			val canonical = Writer.writeDocument(value, isCanonical = true)
			assertEquals(value, Parser.parse(canonical))
			assertEquals(canonical, Writer.writeDocument(Parser.parse(canonical), isCanonical = true))
			assertEquals(canonical, Formatter.format(canonical), "canonical form is formatted")
		}
	}

	@Test
	fun `only SomlException escapes for random text`() {
		val random = Random(3)
		val alphabet = "\n \t,:{}[]'\"#/*\\_.-+abcdefxzinfty0123456789eEhmsuTZé😀 \u0000\r𐀀"

		val texts = generateSequence {
			buildString {
				repeat(random.nextInt(0, 40)) {
					append(alphabet[random.nextInt(alphabet.length)])
				}
			}
		}.take(30_000)

		forEachText(texts) { text ->
			Parser.parse(text)
			Formatter.format(text)
			SomlDocument.parse(text).set("a", "b", value = SomlInt(1))
		}
	}

	@Test
	fun `only SomlException escapes for random bytes`() {
		val random = Random(4)

		for (attempt in 0..<20_000) {
			val bytes = random.nextBytes(random.nextInt(0, 24))

			try {
				Parser.parse(Parser.decodeUtf8(bytes))
			} catch (_: SomlException) {
				// Expected for most random bytes.
			} catch (error: Throwable) {
				fail("Failed for ${bytes.joinToString()}", error)
			}
		}
	}

	@Test
	fun `a float reads back as the same value from its canonical spelling`() {
		val random = Random(5)
		val special = listOf(Double.MIN_VALUE, 2.2250738585072014E-308, Double.MAX_VALUE, 1e21, 1e-7, 1e-6, 9.999999999999999e20, 123456789012345680000.0, 0.1, 5e-324, 1.7976931348623157e308)

		for (value in special + List(50_000) { Double.fromBits(random.nextLong()) }) {
			if (value.isNaN()) {
				continue
			}

			val text = formatFloat(value)
			val parsed = (Parser.parse("a: $text") as SomlObject).getValue("a")
			assertEquals(SomlFloat(value), parsed, "$value written as $text")
		}
	}

	@Test
	fun `the exact algorithm agrees with the platform on random floats`() {
		val random = Random(7)

		repeat(50_000) {
			val value = kotlin.math.abs(Double.fromBits(random.nextLong()))

			if (value.isFinite() && value != 0.0) {
				val (digits, n) = shortestDecimal(value)
				assertEquals(formatFloat(value), formatFloat(value, digits, n), value.toString())
			}
		}
	}

	@Test
	fun `a float has the shortest spelling with an even last digit on a tie`() {
		// From the spec: one digit when one digit reads back, and the layout of canonical rule 11.
		val expected = mapOf(
			5e-324 to "5e-324",
			1e21 to "1e21",
			1e20 to "100000000000000000000.0",
			1.5e300 to "1.5e300",
			1e-7 to "1e-7",
			0.000001 to "0.000001",
			3.14 to "3.14",
			30.0 to "30.0",
			-0.5 to "-0.5",
			2e-323 to "2e-323",
		)

		for ((value, text) in expected) {
			assertEquals(text, formatFloat(value), value.toString())
		}
	}

	@Test
	fun `random values round-trip through canonical and human text`() {
		val random = Random(6)

		fun randomValue(depth: Int): SomlValue = when (if (depth > 4) random.nextInt(8) else random.nextInt(10)) {
			0 -> SomlString(buildString { repeat(random.nextInt(6)) { append(listOf("a", "'", "\"", "\\", "\n", "\t", "\u0001", "\u007F", "é", "😀", " ", "#").random(random)) } })
			1 -> SomlInt(random.nextLong())
			2 -> SomlFloat(Double.fromBits(random.nextLong()).takeUnless { it.isNaN() } ?: 0.0)
			3 -> SomlBool.of(random.nextBoolean())
			4 -> SomlNull
			5 -> SomlInstant(Instant.fromEpochSeconds(random.nextLong(SomlInstant.MIN.epochSeconds, SomlInstant.MAX.epochSeconds), random.nextInt(1_000_000_000)))
			6 -> SomlDuration(random.nextLong())
			7 -> SomlDate(random.nextInt(1, 10000), random.nextInt(1, 13), random.nextInt(1, 29))
			8 -> SomlArray(List(random.nextInt(4)) { randomValue(depth + 1) })
			else -> SomlObject(List(random.nextInt(4)) { "k${random.nextInt(5)}${listOf("é", ".", "😀", " ").random(random)}" to randomValue(depth + 1) }.toMap())
		}

		repeat(5_000) {
			val value = if (random.nextBoolean()) SomlArray(List(random.nextInt(4)) { randomValue(1) }) else SomlObject(List(random.nextInt(4)) { "key$it" to randomValue(1) }.toMap())

			for (isCanonical in listOf(true, false)) {
				val text = Writer.writeDocument(value, isCanonical)
				assertEquals(value, Parser.parse(text), text)
			}
		}
	}
}
