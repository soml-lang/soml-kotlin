package sh.soml.kotlin

import kotlin.reflect.KClass
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule

/**
The serializers that every `Soml` instance has: on the JVM and Android, for the `java.time` types, which kotlinx.serialization has none for.
*/
internal expect val defaultSerializersModule: SerializersModule

/**
A value as an error message describes it, such as `the string 'eighty'`, `the int 3`, or `an object`.
*/
internal fun describeValue(value: SomlValue): String = when (value) {
	is SomlArray, is SomlObject, SomlNull -> Writer.describeType(value)
	// Bound the string before escaping it, because a diagnostic must not copy an entire untrusted value.
	is SomlString -> "the string ${abbreviate(SomlString.trusted(abbreviate(value.value, 60)).toString(), 60)}"
	else -> "the ${Writer.describeType(value).substringAfter(' ')} ${abbreviate(value.toString(), 60)}"
}

/**
The serializer of `SomlValue` and of each of its types, so that a class can hold a value of any shape. It works only with `Soml`, as `JsonElement` works only with `Json`.
*/
@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
internal open class SomlValueSerializer<T : SomlValue>(private val type: KClass<T>, private val expected: String) : KSerializer<T> {
	override val descriptor: SerialDescriptor = buildSerialDescriptor("sh.soml.kotlin.${type.simpleName}", SerialKind.CONTEXTUAL)

	override fun serialize(encoder: Encoder, value: T) {
		val somlEncoder = encoder as? SomlEncoder ?: throw SerializationException("${type.simpleName} can only be written by Soml")
		somlEncoder.encodeSomlValue(value)
	}

	override fun deserialize(decoder: Decoder): T {
		val somlDecoder = decoder as? SomlDecoder ?: throw SerializationException("${type.simpleName} can only be read by Soml")
		val value = somlDecoder.decodeSomlValue()

		// Note: Not using safeCast() because public decoding probes did not establish equal or better performance and allocation.
		if (!type.isInstance(value)) {
			throw SomlException("Expected $expected, but found ${describeValue(value)}")
		}

		@Suppress("UNCHECKED_CAST")
		return value as T
	}
}

internal object AnySomlValueSerializer : SomlValueSerializer<SomlValue>(SomlValue::class, "a value")
internal object SomlStringSerializer : SomlValueSerializer<SomlString>(SomlString::class, "a string")
internal object SomlIntSerializer : SomlValueSerializer<SomlInt>(SomlInt::class, "an int")
internal object SomlFloatSerializer : SomlValueSerializer<SomlFloat>(SomlFloat::class, "a float")
internal object SomlBoolSerializer : SomlValueSerializer<SomlBool>(SomlBool::class, "a bool")
internal object SomlNullSerializer : SomlValueSerializer<SomlNull>(SomlNull::class, "null")
internal object SomlDateSerializer : SomlValueSerializer<SomlDate>(SomlDate::class, "a date")
internal object SomlInstantSerializer : SomlValueSerializer<SomlInstant>(SomlInstant::class, "an instant")
internal object SomlDurationSerializer : SomlValueSerializer<SomlDuration>(SomlDuration::class, "a duration")
internal object SomlArraySerializer : SomlValueSerializer<SomlArray>(SomlArray::class, "an array")
internal object SomlObjectSerializer : SomlValueSerializer<SomlObject>(SomlObject::class, "an object")
