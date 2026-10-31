package sh.soml.kotlin

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
Bugs found after the conformance suite passed, each with the input that showed it.
*/
class RegressionTest {
	@Test
	fun `shows a value nested deeper than the limit`() {
		var value: SomlValue = SomlInt(1)

		repeat(300) {
			value = SomlArray(value)
		}

		val text = value.toString()
		assertTrue(text.contains("\n" + "\t".repeat(300) + "1\n"), "the innermost item is indented 300 levels")
		assertTrue(text.endsWith("\n]"))

		assertFailsWith<SomlException> {
			Soml.encodeToString(SomlValue.serializer(), value)
		}
	}

	@Test
	fun `refuses a negative index in an edit`() {
		val document = SomlDocument.parse("a: [1, 2]\n")

		assertFailsWith<SomlException> {
			document.remove("a", -1)
		}

		assertFailsWith<SomlException> {
			document.set("a", -1, value = SomlInt(5))
		}

		assertEquals("a: [1, 2]\n", document.toString())
	}

	@Test
	fun `refuses a path key that a document cannot hold`() {
		val document = SomlDocument.parse("a: 1\n")

		// Construct the lone surrogate at runtime because the JS compiler replaces one in a string literal.
		val loneSurrogateKey = charArrayOf('x', 0xD800.toChar()).concatToString()
		assertEquals(0xD800, loneSurrogateKey.last().code)

		for (key in listOf("x\r", loneSurrogateKey)) {
			assertFailsWith<IllegalArgumentException> {
				document.set(key, value = SomlInt(5))
			}

			assertFailsWith<IllegalArgumentException> {
				document.set("n", key, value = SomlInt(5))
			}
		}
	}

	@Test
	fun `writes and reads unsigned map keys as their value`() {
		assertEquals("4294967295: 1\n", Soml.encodeToString(mapOf(UInt.MAX_VALUE to 1)))
		assertEquals(mapOf(UInt.MAX_VALUE to 1), Soml.decodeFromString<Map<UInt, Int>>("4294967295: 1"))
		assertEquals("9223372036854775807: 1\n", Soml.encodeToString(mapOf(Long.MAX_VALUE.toULong() to 1)))

		assertFailsWith<SomlException> {
			Soml.encodeToString(mapOf(ULong.MAX_VALUE to 1))
		}

		assertFailsWith<SomlException> {
			Soml.decodeFromString<Map<UInt, Int>>("-1: 1")
		}
	}

	@Test
	fun `reads bool map keys`() {
		val map = mapOf(true to 1, false to 2)
		assertEquals(map, Soml.decodeFromString<Map<Boolean, Int>>(Soml.encodeToString(map)))
	}

	@Test
	fun `decodes a subtype whose serializer delegates to another`() {
		assertEquals(Holder(Delegating(1)), Soml.decodeFromString<Holder>("value: {type: 'delegating', y: 1}"))
		assertEquals("value: {\n\ttype: 'delegating'\n\ty: 1\n}\n", Soml.encodeToString(Holder(Delegating(1))))
	}

	@Test
	fun `refuses a value class as a polymorphic subtype`() {
		val soml = Soml {
			serializersModule = SerializersModule {
				polymorphic(OpenBase::class) {
					subclass(Wrapped::class)
				}
			}
		}

		assertFailsWith<SomlException> {
			soml.encodeToString(OpenHolder(Wrapped(3)))
		}
	}

	@Test
	fun `points at the key for an error about a map key`() {
		val error = assertFailsWith<SomlException> {
			Soml.decodeFromString<Map<String, Map<Level, Int>>>("m: {Y: 1}")
		}

		assertEquals(5, error.column)
	}

	@Test
	fun `points at the key for an error from the serializer of a map key`() {
		val error = assertFailsWith<SomlException> {
			Soml.decodeFromString<Map<String, Map<kotlin.time.Instant, Int>>>("m: {\n\ta: 1\n}")
		}

		assertEquals(listOf("m", "a"), error.path)
		assertEquals(2, error.line)
		assertEquals(2, error.column)
	}

	@Test
	fun `keeps a serializer's own exception as the cause`() {
		val error = assertFailsWith<SomlException> {
			Soml.decodeFromString<Theme>("accent: 'red'")
		}

		assertEquals("bad color", error.reason)
		assertTrue(error.cause is SomlException, "the cause is the exception that the serializer threw")
	}

	@Test
	fun `checks the discriminator against the keys after the naming strategy when decoding`() {
		val soml = Soml {
			namingStrategy = SomlNamingStrategy.SnakeCase
			classDiscriminator = "class_type"
		}

		val error = assertFailsWith<SomlException> {
			soml.decodeFromString<ClashHolder>("value: {class_type: 'clash', class_type_value: 1}")
		}

		assertTrue(error.reason.contains("class discriminator"), error.reason)
	}
}

@Serializable
sealed class Base

@Serializable(with = DelegatingSerializer::class)
@SerialName("delegating")
data class Delegating(val y: Int) : Base()

@Serializable
@SerialName("delegating")
private data class DelegatingSurrogate(val y: Int)

object DelegatingSerializer : KSerializer<Delegating> {
	override val descriptor = DelegatingSurrogate.serializer().descriptor

	override fun serialize(encoder: Encoder, value: Delegating) {
		encoder.encodeSerializableValue(DelegatingSurrogate.serializer(), DelegatingSurrogate(value.y))
	}

	override fun deserialize(decoder: Decoder): Delegating = Delegating(decoder.decodeSerializableValue(DelegatingSurrogate.serializer()).y)
}

@Serializable
data class Holder(val value: Base)

interface OpenBase

@Serializable
@SerialName("wrapped")
@JvmInline
value class Wrapped(val number: Int) : OpenBase

@Serializable
data class OpenHolder(val value: OpenBase)

@Serializable
sealed interface ClashBase

@Serializable
@SerialName("clash")
data class Clash(val classType: Int = 0, val classTypeValue: Int) : ClashBase

@Serializable
data class ClashHolder(val value: ClashBase)

object StrictColorSerializer : KSerializer<String> {
	override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("StrictColor", kotlinx.serialization.descriptors.PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: String) {
		encoder.encodeString(value)
	}

	override fun deserialize(decoder: Decoder): String = decoder.decodeString().takeIf { it.startsWith("#") } ?: throw SomlException("bad color")
}

@Serializable
data class Theme(@Serializable(with = StrictColorSerializer::class) val accent: String)
