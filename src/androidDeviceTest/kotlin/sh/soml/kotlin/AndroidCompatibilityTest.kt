package sh.soml.kotlin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
Runs on ART, including API 33, so the checks exercise Android's actual floating-point estimate and available Java APIs.
*/
class AndroidCompatibilityTest {
	@Test
	fun canonicalFloats() {
		resourceLines("floats/canonical.txt") { line ->
			val (bits, text) = line.split(' ')
			val value = Double.fromBits(bits.toULong(16).toLong())
			assertEquals(text, formatFloat(value), bits)
			assertEquals("-$text", formatFloat(-value), bits)
			assertEquals(value.toRawBits(), parseFloat(text).toRawBits(), text)
		}
	}

	@Test
	fun halfwayFloats() {
		resourceLines("floats/halfway.txt") { line ->
			val (text, bits) = line.split(' ')
			assertEquals(bits.toULong(16).toLong(), parseFloat(text).toRawBits(), text.take(80))
		}
	}

	@Test
	fun platformDecimalParsingBoundaries() {
		for ((text, bits) in listOf("1.7976931348623158e308" to 0x7FEF_FFFF_FFFF_FFFFL, "2.2250738585072011e-308" to 0x000F_FFFF_FFFF_FFFFL, "3e-324" to 1L)) {
			assertEquals(bits, text.toDouble().toRawBits(), text)
			assertEquals(SomlObject("value" to SomlFloat(Double.fromBits(bits))), Soml.parseToSomlValue("value: $text"))
		}

		// Exact decimal arithmetic gives the tie at half the smallest subnormal, independently of the platform parser.
		val midpoint = java.math.BigDecimal(Double.MIN_VALUE).divide(java.math.BigDecimal.valueOf(2)).toPlainString()
		val aboveMidpoint = midpoint + "0".repeat(2000) + "1"
		assertEquals(0L, midpoint.toDouble().toRawBits())
		assertEquals(1L, aboveMidpoint.toDouble().toRawBits())
		assertEquals(Long.MIN_VALUE or 1L, "-$aboveMidpoint".toDouble().toRawBits())
		assertEquals(SomlObject("value" to SomlFloat(Double.MIN_VALUE)), Soml.parseToSomlValue("value: $aboveMidpoint"))
		assertEquals(SomlObject("value" to SomlFloat(-Double.MIN_VALUE)), Soml.parseToSomlValue("value: -$aboveMidpoint"))
		assertFailsWith<SomlException> {
			Soml.parseToSomlValue("value: $midpoint")
		}

		val balancedExponent = "1" + "0".repeat(2000) + "e-2000"
		assertEquals(SomlObject("value" to SomlFloat(1.0)), Soml.parseToSomlValue("value: $balancedExponent"))
	}

	@Test
	fun nestedWritingFormattingAndEditing() {
		val value = SomlObject("items" to SomlArray(SomlObject("emoji" to SomlString("😀"), "value" to SomlFloat(Double.MIN_VALUE))))
		val text = Soml.encodeToString(value)
		assertEquals(value, Soml.parseToSomlValue(text))
		assertEquals(text, Soml.format(text))

		val document = SomlDocument.parse("# Keep this comment.\nitems: [{value: 1}]\n")
		document.set("items", 0, "value", value = SomlFloat(Double.MIN_VALUE))
		assertEquals(SomlFloat(Double.MIN_VALUE), document["items", 0, "value"])
		assertEquals("# Keep this comment.\nitems: [{value: 5e-324}]\n", document.toString())
	}

	@Test
	fun javaTimeAndStreams() {
		val expected = Times(java.time.Instant.parse("2026-11-01T00:00:00.123456789Z"), java.time.Duration.ofNanos(Long.MIN_VALUE), java.time.LocalDate.of(1582, 10, 10))
		val text = Soml.encodeToString(expected)
		assertEquals(expected, Soml.decodeFromString<Times>(text))

		val output = ByteArrayOutputStream()
		Soml.encodeToStream(expected, output)
		val input = ByteArrayInputStream(byteArrayOf(0) + output.toByteArray())
		assertEquals(0, input.read())
		assertEquals(expected, Soml.decodeFromStream<Times>(input))
		assertEquals(-1, input.read())
		assertEquals(expected.duration, SomlDuration.of(expected.duration).toJavaDuration())
		assertEquals(expected.instant, SomlInstant(expected.instant).toJavaInstant())
		assertEquals(expected.date, SomlDate(expected.date).toJavaLocalDate())
	}

	@Test
	fun durationBoundaries() {
		for (nanoseconds in listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -1_000_000_000L, -1L, 0L, 1L, Long.MAX_VALUE - 1, Long.MAX_VALUE)) {
			val duration = java.time.Duration.ofNanos(nanoseconds)
			assertEquals(nanoseconds, SomlDuration.of(duration).nanoseconds)
			assertEquals(duration, SomlDuration(nanoseconds).toJavaDuration())
		}

		for (duration in listOf(java.time.Duration.ofNanos(Long.MIN_VALUE).minusNanos(1), java.time.Duration.ofNanos(Long.MAX_VALUE).plusNanos(1), java.time.Duration.ofSeconds(Long.MIN_VALUE), java.time.Duration.ofSeconds(Long.MAX_VALUE))) {
			assertFailsWith<IllegalArgumentException> {
				SomlDuration.of(duration)
			}
		}
	}

	@Test
	fun strictUtf8AndFloatRanges() {
		assertFailsWith<SomlException> {
			Soml.parseToSomlValue(byteArrayOf(0x61, 0x3A, 0x20, 0xC0.toByte(), 0xAF.toByte()))
		}

		for (text in listOf("value: 1.0e2147483647", "value: 0.1e-2147483648")) {
			assertFailsWith<SomlException> {
				Soml.parseToSomlValue(text)
			}
		}

		assertEquals(SomlObject("value" to SomlFloat(0.0)), Soml.parseToSomlValue("value: 0.0e2147483647"))
	}

	/**
	Reads reference fixtures packaged in the test APK, independently of the host filesystem.
	*/
	private fun resourceLines(name: String, check: (String) -> Unit) {
		val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "Missing packaged fixture: $name" }

		stream.bufferedReader(Charsets.UTF_8).use { reader ->
			for (line in reader.lineSequence()) {
				if (line.isNotEmpty()) {
					check(line)
				}
			}
		}
	}

	@Serializable
	private data class Times(@Contextual val instant: java.time.Instant, @Contextual val duration: java.time.Duration, @Contextual val date: java.time.LocalDate)
}
