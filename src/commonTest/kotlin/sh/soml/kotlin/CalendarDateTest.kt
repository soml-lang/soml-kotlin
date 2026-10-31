package sh.soml.kotlin

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@Serializable
private data class CalendarSchedule(val date: SomlDate, val label: String, val instant: Instant, val dates: List<SomlDate>, val optional: SomlDate? = null)

class CalendarDateTest {
	@Test
	fun `native dates preserve components and exact spelling`() {
		for ((year, month, day) in listOf(Triple(1, 1, 1), Triple(9999, 12, 31), Triple(1582, 10, 10), Triple(2011, 12, 30), Triple(2000, 2, 29))) {
			val date = SomlDate(year, month, day)
			val text = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
			assertEquals(Triple(year, month, day), Triple(date.year, date.month, date.day))
			assertEquals(text, date.toString())
			assertEquals(SomlArray(date), Soml.parseToSomlValue("[$text]"))
			assertEquals(SomlArray(date), Soml.parseToSomlValue("[$text]".encodeToByteArray()))
			assertEquals("[\n\t$text\n]\n", Soml.encodeToString(listOf(date)))
			assertEquals(date.hashCode(), SomlDate(year, month, day).hashCode())
			assertNotEquals<SomlValue>(SomlString(text), date)
			assertNotEquals<SomlValue>(SomlInstant(Instant.parse("${text}T00:00:00Z")), date)
		}
	}

	@Test
	fun `invalid components are rejected without normalization`() {
		for ((year, month, day) in listOf(Triple(0, 1, 1), Triple(10000, 1, 1), Triple(-1, 1, 1), Triple(2026, 0, 1), Triple(2026, 13, 1), Triple(1900, 2, 29), Triple(2026, 2, 30), Triple(2026, 4, 31), Triple(2026, 1, 0))) {
			assertFailsWith<IllegalArgumentException> { SomlDate(year, month, day) }
		}
	}

	@Test
	fun `whole token grammar rejects malformed date extensions`() {
		for (text in listOf("0000-01-01", "1900-02-29", "2026-02-30", "2026-1-01", "10000-01-01", "2026-11-01T", "2026-11-01Z", "2026-11-01foo", "2026-11-01T12:00:00", "２０２６-11-01")) {
			assertFailsWith<SomlException> { Soml.parseToSomlValue("date: $text") }
			assertFailsWith<SomlException> { Soml.parseToSomlValue("date: $text".encodeToByteArray()) }
			assertFailsWith<SomlException> { Soml.format("date: $text") }
		}
		val error = assertFailsWith<SomlException> { Soml.parseToSomlValue("date: 2026-02-30") }
		assertEquals("Invalid date 2026-02-30: the day must be 01 to 28 in that month", error.reason)
		assertEquals(1, error.line)
		assertEquals(7, error.column)
		assertFailsWith<SomlException> { Soml.parseToSomlValue("2026-11-01") }
	}

	@Test
	fun `date boundaries comments and neighboring lines preserve the type`() {
		for (source in listOf("date: 2026-11-01", "date: 2026-11-01\nnext: 1", "date: 2026-11-01# comment\nnext: 1", "date: 2026-11-01/* comment */", "{date: 2026-11-01, next: 1}")) {
			assertEquals(SomlDate(2026, 11, 1), (Soml.parseToSomlValue(source) as SomlObject)["date"])
			assertEquals(Soml.parseToSomlValue(source), Soml.parseToSomlValue(Soml.format(source)))
		}
		assertEquals(SomlArray(SomlDate(2026, 11, 1), SomlDate(2026, 11, 2)), Soml.parseToSomlValue("[2026-11-01\n2026-11-02]"))
	}

	@Test
	fun `typed serialization distinguishes dates strings and instants`() {
		val date = SomlDate(1582, 10, 10)
		val expected = CalendarSchedule(date, "1582-10-10", Instant.parse("2026-11-01T00:00:00Z"), listOf(SomlDate(1, 1, 1), SomlDate(9999, 12, 31)), date)
		val source = "date: 1582-10-10\nlabel: '1582-10-10'\ninstant: 2026-11-01T00:00:00Z\ndates: [0001-01-01, 9999-12-31]\noptional: 1582-10-10"
		assertEquals(expected, Soml.decodeFromString<CalendarSchedule>(source))
		assertEquals(expected, Soml.decodeFromSomlValue<CalendarSchedule>(Soml.parseToSomlValue(source)))
		assertEquals(Soml.parseToSomlValue(source), Soml.encodeToSomlValue(expected))
		assertEquals(expected, Soml.decodeFromString<CalendarSchedule>(Soml.encodeToString(expected)))
		assertEquals(date, Soml.decodeFromSomlValue<SomlDate>(date))
		assertFailsWith<SomlException> { Soml.decodeFromString<List<SomlDate>>("['2026-11-01']") }
		assertFailsWith<SomlException> { Soml.decodeFromString<List<SomlDate>>("[2026-11-01T00:00:00Z]") }
		assertFailsWith<SomlException> { Soml.decodeFromString<List<String>>("[2026-11-01]") }
		assertFailsWith<SomlException> { Soml.decodeFromString<List<Instant>>("[2026-11-01]") }
	}

	@Test
	fun `untrusted date suffixes stay bounded and encoding errors take precedence`() {
		val suffix = "x".repeat(100_000)
		val error = assertFailsWith<SomlException> { Soml.parseToSomlValue("date: 2026-11-01$suffix") }
		assertTrue(error.reason.length < 250)
		assertEquals(7, error.column)
		val encodingError = assertFailsWith<SomlException> { Soml.parseToSomlValue("date: 2026-11-01$suffix\u0000") }
		assertTrue("U+0000" in encodingError.reason)
	}

	@Test
	fun `editing preserves native dates and surrounding comments`() {
		val source = "date: 2026-11-01 # keep\nlabel: '2026-11-01'\ndates: [1582-10-10]\n"
		val document = SomlDocument.parse(source)
		assertEquals(source, document.toString())
		document.set("date", value = SomlDate(2000, 2, 29))
		document.set("dates", 1, value = SomlDate(9999, 12, 31))
		assertEquals("date: 2000-02-29 # keep\nlabel: '2026-11-01'\ndates: [1582-10-10, 9999-12-31]\n", document.toString())
		assertIs<SomlDate>(document["date"])
		assertIs<SomlString>(document["label"])
		assertEquals(document.value, Soml.parseToSomlValue(document.toString()))
	}
}
