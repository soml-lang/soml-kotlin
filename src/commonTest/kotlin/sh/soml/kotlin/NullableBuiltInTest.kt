package sh.soml.kotlin

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class NullableBuiltInTest {
	@Test
	fun `a nullable property serializer handles null while encoding`() {
		val value = NullableSentinelProperty(null)
		assertEquals("{\"value\":\"unset\"}", Json.encodeToString(value))
		assertEquals("value: 'unset'\n", Soml.encodeToString(value))
	}

	@Test
	fun `a nullable property serializer handles null while decoding`() {
		assertEquals(NullableSentinelProperty("unset"), Json.decodeFromString<NullableSentinelProperty>("{\"value\":null}"))
		assertEquals(NullableSentinelProperty("unset"), Soml.decodeFromString<NullableSentinelProperty>("value: null"))
	}

	@Test
	fun `a nullable serializer rejection keeps its nested decoding path and cause`() {
		val error = assertFailsWith<SomlException> {
			Soml.decodeFromString<NullableValidationContainer>("nested: {value: null}")
		}

		assertEquals(listOf("nested", "value"), error.path)
		assertEquals("A null value is forbidden", error.reason)
		assertEquals(1, error.line)
		assertEquals(17, error.column)
		assertIs<IllegalArgumentException>(assertIs<SomlException>(error.cause).cause)
	}

	@Test
	fun `a nullable serializer rejection keeps its nested encoding path and cause`() {
		val error = assertFailsWith<SomlException> {
			Soml.encodeToString(NullableValidationContainer(NullableValidatedProperty(null)))
		}

		assertEquals(listOf("nested", "value"), error.path)
		assertEquals("A null value is forbidden", error.reason)
		assertIs<IllegalArgumentException>(assertIs<SomlException>(error.cause).cause)
	}
}

@Serializable
private data class NullableSentinelProperty(@Serializable(with = NullableSentinelSerializer::class) val value: String?)

@OptIn(ExperimentalSerializationApi::class)
private object NullableSentinelSerializer : KSerializer<String?> {
	override val descriptor = PrimitiveSerialDescriptor("NullableSentinel", PrimitiveKind.STRING).nullable

	override fun serialize(encoder: Encoder, value: String?) {
		encoder.encodeString(value ?: "unset")
	}

	override fun deserialize(decoder: Decoder): String {
		if (!decoder.decodeNotNullMark()) {
			decoder.decodeNull()
			return "unset"
		}

		return decoder.decodeString()
	}
}

@Serializable
private data class NullableValidatedProperty(@Serializable(with = NullableValidationSerializer::class) val value: String?)

@Serializable
private data class NullableValidationContainer(val nested: NullableValidatedProperty)

@OptIn(ExperimentalSerializationApi::class)
private object NullableValidationSerializer : KSerializer<String?> {
	override val descriptor = PrimitiveSerialDescriptor("NullableValidation", PrimitiveKind.STRING).nullable

	override fun serialize(encoder: Encoder, value: String?) {
		if (value == null) {
			throw SomlException("A null value is forbidden", IllegalArgumentException("The value must be present"))
		}

		encoder.encodeString(value)
	}

	override fun deserialize(decoder: Decoder): String {
		if (!decoder.decodeNotNullMark()) {
			throw SomlException("A null value is forbidden", IllegalArgumentException("The value must be present"))
		}

		return decoder.decodeString()
	}
}
