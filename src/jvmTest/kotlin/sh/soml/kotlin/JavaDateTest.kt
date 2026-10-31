package sh.soml.kotlin

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

@Serializable
private data class JavaCalendarDate(@Contextual val date: LocalDate)

class JavaDateTest {
	@Test
	fun `java dates preserve native calendar components in both directions`() {
		val json = Json { serializersModule = Soml.serializersModule }

		for (text in listOf("0001-01-01", "9999-12-31", "1582-10-10", "2011-12-30", "2000-02-29")) {
			val date = LocalDate.parse(text)
			assertEquals(date, SomlDate(date).toJavaLocalDate())
			assertEquals(JavaCalendarDate(date), Soml.decodeFromString<JavaCalendarDate>("date: $text"))
			assertEquals("date: $text\n", Soml.encodeToString(JavaCalendarDate(date)))
			assertEquals("{\"date\":\"$text\"}", json.encodeToString(JavaCalendarDate(date)))
			assertEquals(JavaCalendarDate(date), json.decodeFromString<JavaCalendarDate>("{\"date\":\"$text\"}"))
		}
	}

	@Test
	fun `java date bindings reject strings instants and unsupported years`() {
		for (value in listOf("'2026-11-01'", "2026-11-01T00:00:00Z", "1")) {
			val error = assertFailsWith<SomlException> { Soml.decodeFromString<JavaCalendarDate>("date: $value") }
			assertEquals(listOf("date"), error.path)
			assertEquals(7, error.column)
		}
		for (year in listOf(0, -1, 10000)) {
			val date = LocalDate.of(year, 1, 1)
			assertFailsWith<IllegalArgumentException> { SomlDate(date) }
			val error = assertFailsWith<SomlException> { Soml.encodeToString(JavaCalendarDate(date)) }
			assertEquals(listOf("date"), error.path)
		}
	}

	@Test
	fun `a full Gregorian cycle agrees with the independent JVM calendar`() {
		for (year in 1..400) {
			for (month in 1..12) {
				val lastDay = LocalDate.of(year, month, 1).lengthOfMonth()
				for (day in 0..32) {
					if (day in 1..lastDay) {
						val expected = LocalDate.of(year, month, day)
						val date = SomlDate(year, month, day)
						assertEquals(expected, date.toJavaLocalDate())
						assertEquals(expected.toString(), date.toString())
						assertEquals(SomlArray(date), Soml.parseToSomlValue("[$date]"))
					} else {
						assertFailsWith<IllegalArgumentException> { SomlDate(year, month, day) }
					}
				}
			}
		}
	}

	@Test
	fun `an explicit contextual date serializer can override the native binding`() {
		val soml = Soml {
			serializersModule = SerializersModule {
				contextual(LocalDate::class, TextDateSerializer)
			}
		}
		val date = JavaCalendarDate(LocalDate.of(2027, 9, 19))
		assertEquals("date: '2027-09-19'\n", soml.encodeToString(date))
		assertEquals(date, soml.decodeFromString<JavaCalendarDate>("date: '2027-09-19'"))
	}
}

private object TextDateSerializer : KSerializer<LocalDate> {
	override val descriptor = PrimitiveSerialDescriptor("TextDate", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: LocalDate) {
		encoder.encodeString(value.toString())
	}

	override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}
