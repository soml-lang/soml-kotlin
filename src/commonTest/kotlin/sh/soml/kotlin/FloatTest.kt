package sh.soml.kotlin

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
The canonical spelling of 30,000 floats, written by soml-java (`scripts/FloatFixture.java`), an independent implementation: random bit patterns, the first 2,000 subnormals, every power of two, powers of ten, and the neighbors of the boundaries of the layout. Each platform must write each float with that spelling, and read the spelling back as the same float.
*/
class FloatTest {
	private val cases: List<Pair<Double, String>> = SystemFileSystem.source(Path(RESOURCES_DIRECTORY, "floats/canonical.txt")).buffered().use { it.readString() }
		.lineSequence()
		.filter { it.isNotEmpty() }
		.map { line ->
			val (bits, text) = line.split(' ')
			Double.fromBits(bits.toULong(16).toLong()) to text
		}
		.toList()

	@Test
	fun `writes the canonical spelling`() = checkEach(cases, { it.second }) { (value, text) ->
		assertEquals(text, formatFloat(value))
		assertEquals("-$text", formatFloat(-value))
	}

	@Test
	fun `the exact algorithm writes the canonical spelling`() = checkEach(cases, { it.second }) { (value, text) ->
		val (digits, n) = shortestDecimal(value)
		assertEquals(text, formatFloat(value, digits, n))
	}

	/**
	Decimals exactly halfway between two floats, with up to 775 digits, and decimals just above and below them, from `scripts/HalfwayFixture.java`, which rounds them with `BigDecimal`. Swift's and Go's own parsers round some of these wrongly.
	*/
	@Test
	fun `rounds long decimals to the nearest float and a tie to even`() {
		val halfway = SystemFileSystem.source(Path(RESOURCES_DIRECTORY, "floats/halfway.txt")).buffered().use { it.readString() }
			.lineSequence()
			.filter { it.isNotEmpty() }
			.map { it.split(' ') }
			.toList()

		checkEach(halfway, { it[0].take(40) + "…" }) { (text, bits) ->
			assertEquals(bits.toULong(16).toLong(), parseFloat(text).toRawBits())
		}
	}

	@Test
	fun `the exact algorithm rounds long decimals to the nearest float`() {
		val halfway = SystemFileSystem.source(Path(RESOURCES_DIRECTORY, "floats/halfway.txt")).buffered().use { it.readString() }
			.lineSequence()
			.filter { it.isNotEmpty() }
			.map { it.split(' ') }
			.toList()

		checkEach(halfway, { it[0].take(40) + "…" }) { (text, bits) ->
			val expected = bits.toULong(16).toLong()

			// From a good estimate, and from estimates a few floats away on either side.
			for (offset in -3L..3L) {
				assertEquals(expected, nearestFloat(text, Double.fromBits(expected + offset)).toRawBits(), "from an estimate $offset floats away")
			}
		}

		checkEach(cases, { it.second }) { (value, text) ->
			assertEquals(value.toRawBits(), nearestFloat(text, text.toDouble()).toRawBits())
		}

		assertEquals(Double.POSITIVE_INFINITY, nearestFloat("1e99999999999", Double.POSITIVE_INFINITY))
		assertEquals(Double.POSITIVE_INFINITY, nearestFloat("1.7976931348623159e308", Double.MAX_VALUE))
		assertEquals(Double.MAX_VALUE, nearestFloat("1.7976931348623158e308", Double.POSITIVE_INFINITY))
		assertEquals(0.0, nearestFloat("1e-400", Double.MIN_VALUE))
		assertEquals(Double.MIN_VALUE, nearestFloat("3e-324", 0.0))
	}

	@Test
	fun `reads the spelling back as the same float`() = checkEach(cases, { it.second }) { (value, text) ->
		assertEquals(value.toRawBits(), parseFloat(text).toRawBits())
		assertEquals(SomlFloat(value), (Parser.parse("a: $text") as SomlObject).getValue("a"))
	}
}
