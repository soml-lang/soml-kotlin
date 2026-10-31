package sh.soml.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass


/**
The API of the JVM: `java.time` types and streams.
*/
class JavaTest {
	@Test
	fun `java durations preserve the complete nanosecond range`() {
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
	fun `reads java time types natively`() {
		val times = Soml.decodeFromString<JavaTimes>("instant: 2026-09-19T16:00:00+02:00\nduration: 1.5s")
		assertEquals(JavaTimes(java.time.Instant.parse("2026-09-19T14:00:00Z"), java.time.Duration.ofMillis(1500)), times)
		assertEquals("instant: 2026-09-19T14:00:00Z\nduration: 1.5s\n", Soml.encodeToString(times))
		// Other formats get ISO 8601 text.
		assertEquals("""{"instant":"2026-09-19T14:00:00Z","duration":"PT1.5S"}""", jsonWithJavaTime.encodeToString(times))
	}

	@Test
	fun `refuses a duration that a Kotlin duration cannot hold exactly`() {
		assertEquals(mapOf("a" to 1.hours), Soml.decodeFromString<Map<String, Duration>>("a: 1h"))

		assertFailsWith<SomlException> {
			Soml.decodeFromString<Map<String, Duration>>("a: 9223372036854775807ns")
		}

		assertEquals(java.time.Duration.ofNanos(Long.MAX_VALUE), Soml.decodeFromString<Map<String, @Contextual java.time.Duration>>("a: 9223372036854775807ns").getValue("a"))
	}

	@Test
	fun `places an error that a serializer throws at its value`() {
		val instant = assertFailsWith<SomlException> {
			Soml.decodeFromString<JavaTimes>("instant: 'x'\nduration: 1s")
		}

		assertEquals(listOf("instant"), instant.path)
		assertEquals(1, instant.line)
		assertEquals(10, instant.column)

		val dynamic = assertFailsWith<SomlException> {
			Soml.decodeFromString<Map<String, SomlObject>>("a: {}\nb: 1")
		}

		assertEquals(listOf("b"), dynamic.path)
		assertEquals("Expected an object, but found the int 1", dynamic.reason)
		assertEquals(2, dynamic.line)
		assertEquals(4, dynamic.column)
	}

	@Test
	fun `places an encoding error that a serializer throws at its value`() {
		val error = assertFailsWith<SomlException> {
			Soml.encodeToString(JavaTimes(java.time.Instant.MAX, java.time.Duration.ZERO))
		}

		assertEquals(listOf("instant"), error.path)

		val duration = assertFailsWith<SomlException> {
			Soml.encodeToString(JavaTimes(java.time.Instant.EPOCH, java.time.Duration.ofDays(300 * 365)))
		}

		assertEquals(listOf("duration"), duration.path)
	}

	@Test
	fun `reads and writes streams`() {
		val output = java.io.ByteArrayOutputStream()
		Soml.encodeToStream(Postgres("é", 1), output)
		assertEquals("host: 'é'\nport: 1\n", output.toString(Charsets.UTF_8))
		assertEquals(Postgres("é", 1), Soml.decodeFromStream<Postgres>(output.toByteArray().inputStream()))
	}

	@Test
	fun `reads remaining fragmented bytes without closing the stream`() {
		val source = "xvalue: 'é😀'".encodeToByteArray().inputStream()
		var closed = false
		val stream = object : java.io.InputStream() {
			override fun available(): Int = 0

			override fun read(): Int = source.read()

			override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
				if (length == 0) {
					return 0
				}

				return source.read(bytes, offset, minOf(length, 3))
			}

			override fun close() {
				closed = true
			}
		}

		assertEquals('x'.code, stream.read())
		assertEquals(mapOf("value" to "é😀"), Soml.decodeFromStream<Map<String, String>>(stream))
		assertEquals(-1, stream.read())
		assertFalse(closed)
	}

	@Test
	fun `stream read failures preserve their cause and leave ownership with the caller`() {
		val failure = java.io.IOException("The source is unavailable")
		var closed = false
		val stream = object : java.io.InputStream() {
			override fun read(): Int = throw failure

			override fun close() {
				closed = true
			}
		}

		assertSame(failure, assertFailsWith<java.io.IOException> {
			Soml.decodeFromStream<Map<String, String>>(stream)
		})
		assertFalse(closed)
	}

	@Test
	fun `lets a module replace the serializer of a java time type`() {
		val soml = Soml {
			serializersModule = SerializersModule {
				contextual(java.time.Instant::class, EpochSecondsSerializer)
			}
		}

		assertEquals("instant: 5\nduration: 1s\n", soml.encodeToString(JavaTimes(java.time.Instant.ofEpochSecond(5), java.time.Duration.ofSeconds(1))))
	}
}

@Serializable
data class JavaTimes(@Contextual val instant: java.time.Instant, @Contextual val duration: java.time.Duration)

private val jsonWithJavaTime = Json { serializersModule = Soml.serializersModule }

object EpochSecondsSerializer : KSerializer<java.time.Instant> {
	override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("EpochSeconds", kotlinx.serialization.descriptors.PrimitiveKind.LONG)

	override fun serialize(encoder: Encoder, value: java.time.Instant) {
		encoder.encodeLong(value.epochSecond)
	}

	override fun deserialize(decoder: Decoder): java.time.Instant = java.time.Instant.ofEpochSecond(decoder.decodeLong())
}
