package sh.soml.kotlin

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.internal.AbstractPolymorphicSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Duration
import kotlin.time.Instant

/**
An encoder of the SOML format. A serializer can use it to write a `SomlValue` directly, as `JsonEncoder` does for JSON:

```kotlin
override fun serialize(encoder: Encoder, value: Color) {
	(encoder as SomlEncoder).encodeSomlValue(SomlString(value.hex))
}
```
*/
public interface SomlEncoder : Encoder {
	/**
	The instance that is encoding.
	*/
	public val soml: Soml

	/**
	Writes `value` as the value being encoded.
	*/
	public fun encodeSomlValue(value: SomlValue)
}

// Note: Not using capturedKClass because primitive time descriptors omit it and contextual class shortcuts can bypass registered serializers.
internal const val DURATION_SERIAL_NAME = "kotlin.time.Duration"
internal const val INSTANT_SERIAL_NAME = "kotlin.time.Instant"

/**
Encodes one value into the tree, and hands it to `consumer`.
*/
@OptIn(ExperimentalSerializationApi::class)
internal open class ValueEncoder(
	override val soml: Soml,
	protected val path: Path?,
	// The serial name of the subtype to write as the class discriminator of the object that this value begins, for a polymorphic value.
	private val discriminatorValue: String? = null,
	private val consumer: (SomlValue) -> Unit,
) : SomlEncoder {
	override val serializersModule: SerializersModule
		get() = soml.serializersModule

	protected fun fail(reason: String, cause: Throwable? = null): SomlException = SomlException.atPath(reason, path.toList(), cause)

	override fun encodeSomlValue(value: SomlValue) {
		val type = discriminatorValue

		if (type == null) {
			consumer(value)
			return
		}

		val members = value as? SomlObject ?: throw fail("A polymorphic value must be written as an object")
		val discriminator = soml.configuration.classDiscriminator

		if (discriminator in members) {
			throw fail("The polymorphic object has a member with the key ${describeKey(discriminator)}, which is the class discriminator")
		}

		consumer(SomlObject.trusted(buildMap(members.size + 1) {
			put(discriminator, SomlString(type))
			putAll(members)
		}))
	}

	override fun encodeNotNullMark() {
	}

	override fun encodeNull() {
		encodeSomlValue(SomlNull)
	}

	override fun encodeBoolean(value: Boolean) {
		encodeSomlValue(SomlBool.of(value))
	}

	override fun encodeByte(value: Byte) {
		encodeLong(value.toLong())
	}

	override fun encodeShort(value: Short) {
		encodeLong(value.toLong())
	}

	override fun encodeInt(value: Int) {
		encodeLong(value.toLong())
	}

	override fun encodeLong(value: Long) {
		encodeSomlValue(SomlInt(value))
	}

	override fun encodeFloat(value: Float) {
		// A float widens to the double that has its exact value, so `0.1f` is written as `0.10000000149011612`. Writing a 32-bit float's own shortest digits would be a different value.
		encodeDouble(value.toDouble())
	}

	override fun encodeDouble(value: Double) {
		if (value.isNaN()) {
			throw fail("NaN is not a SOML value. Use null for a missing value")
		}

		encodeSomlValue(SomlFloat(value))
	}

	override fun encodeChar(value: Char) {
		encodeString(value.toString())
	}

	override fun encodeString(value: String) {
		findUnrepresentable(value)?.let {
			throw fail(it)
		}

		encodeSomlValue(SomlString.trusted(value))
	}

	override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
		encodeString(enumDescriptor.getElementName(index))
	}

	override fun encodeInline(descriptor: SerialDescriptor): Encoder = if (unsignedRange(descriptor) != null) UnsignedEncoder(this) else this

	override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder = beginCollection(descriptor, 10)

	override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder {
		if ((path?.depth ?: 0) >= MAX_DEPTH) {
			throw fail("A document cannot nest more than $MAX_DEPTH levels deep")
		}

		if (discriminatorValue != null) {
			soml.keysOf(descriptor).checkNotKey(soml.configuration.classDiscriminator, descriptor, path.toList())
		}

		val consumer = if (discriminatorValue == null) consumer else ::encodeSomlValue

		return when (descriptor.kind) {
			StructureKind.LIST -> ArrayEncoder(soml, path, collectionSize, consumer)
			StructureKind.MAP -> MapEncoder(soml, path, collectionSize, consumer)
			else -> ObjectEncoder(soml, path, descriptor, consumer)
		}
	}

	override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
		try {
			encodeSerializableValueUnplaced(serializer, value)
		} catch (error: SomlException) {
			throw error.placed(path.toList())
		} catch (error: IllegalArgumentException) {
			// The error of a serializer, such as a value that SOML cannot represent, which is reported at the value.
			throw fail(error.message ?: error.toString(), error)
		}
	}

	@OptIn(InternalSerializationApi::class)
	private fun <T> encodeSerializableValueUnplaced(serializer: SerializationStrategy<T>, value: T) {
		val descriptor = serializer.descriptor

		// Note: Not using canonical descriptor equality because these name/kind checks need no canonical serializer instances.
		when (descriptor.serialName) {
			// A duration or an instant that SOML cannot hold throws `IllegalArgumentException`, which `encodeSerializableValue()` reports at the value.
			DURATION_SERIAL_NAME if descriptor.kind == PrimitiveKind.STRING -> encodeSomlValue(SomlDuration.of(value as Duration))
			INSTANT_SERIAL_NAME if descriptor.kind == PrimitiveKind.STRING -> encodeSomlValue(SomlInstant(value as Instant))
			else -> if (serializer is AbstractPolymorphicSerializer<*> && value != null) {
				// Note: Not using SerializersModule.getPolymorphic() directly because sealed serializers also resolve their own known subclasses.
				@Suppress("UNCHECKED_CAST")
				val subtype = (serializer as AbstractPolymorphicSerializer<Any>).findPolymorphicSerializerOrNull(this, value)
					?: throw fail("${value::class.simpleName} is not a registered subtype of ${serializer.baseClass.simpleName}")

				val kind = subtype.descriptor.kind

				// A value class has the kind of a class, but is written as its value, which has no room for the type.
				if ((kind != StructureKind.CLASS && kind != StructureKind.OBJECT) || subtype.descriptor.isInline) {
					throw fail("A polymorphic value is written as an object with its type in the member ${describeKey(soml.configuration.classDiscriminator)}, so ${subtype.descriptor.serialName} must be a class")
				}

				subtype.serialize(ValueEncoder(soml, path, subtype.descriptor.serialName, consumer), value)
			} else {
				serializer.serialize(this, value)
			}
		}
	}

	/**
	Writes an unsigned type, whose serializer gives the bits of the signed type of its size.
	*/
	private class UnsignedEncoder(private val encoder: ValueEncoder) : SomlEncoder by encoder {
		override fun encodeByte(value: Byte) {
			encoder.encodeLong(value.toUByte().toLong())
		}

		override fun encodeShort(value: Short) {
			encoder.encodeLong(value.toUShort().toLong())
		}

		override fun encodeInt(value: Int) {
			encoder.encodeLong(value.toUInt().toLong())
		}

		override fun encodeLong(value: Long) {
			if (value < 0) {
				throw encoder.fail("The int ${value.toULong()} is outside the 64-bit range of a SOML int (-9223372036854775808 to 9223372036854775807)")
			}

			encoder.encodeLong(value)
		}
	}
}

/**
The common part of the encoders of an object, an array, and a map, which hand each element to a `ValueEncoder`.
*/
// Note: Not using AbstractEncoder or AbstractDecoder because their final element methods would require mutable current-value state.
@OptIn(ExperimentalSerializationApi::class)
internal abstract class StructureEncoder(protected val soml: Soml, protected val path: Path?) : CompositeEncoder {
	override val serializersModule: SerializersModule
		get() = soml.serializersModule

	/**
	The encoder of the element at `index`.
	*/
	protected abstract fun element(descriptor: SerialDescriptor, index: Int): Encoder

	override fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean) {
		element(descriptor, index).encodeBoolean(value)
	}

	override fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte) {
		element(descriptor, index).encodeByte(value)
	}

	override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short) {
		element(descriptor, index).encodeShort(value)
	}

	override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char) {
		element(descriptor, index).encodeChar(value)
	}

	override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int) {
		element(descriptor, index).encodeInt(value)
	}

	override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long) {
		element(descriptor, index).encodeLong(value)
	}

	override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float) {
		element(descriptor, index).encodeFloat(value)
	}

	override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double) {
		element(descriptor, index).encodeDouble(value)
	}

	override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String) {
		element(descriptor, index).encodeString(value)
	}

	override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder = element(descriptor, index).encodeInline(descriptor.getElementDescriptor(index))

	override fun <T> encodeSerializableElement(descriptor: SerialDescriptor, index: Int, serializer: SerializationStrategy<T>, value: T) {
		element(descriptor, index).encodeSerializableValue(serializer, value)
	}

	override fun <T : Any> encodeNullableSerializableElement(descriptor: SerialDescriptor, index: Int, serializer: SerializationStrategy<T>, value: T?) {
		val encoder = element(descriptor, index)

		// Note: Not using encodeNullableSerializableValue() for non-null values because its descriptor check adds overhead to this hot path.
		if (value == null) {
			encoder.encodeNullableSerializableValue(serializer, value)
		} else {
			encoder.encodeSerializableValue(serializer, value)
		}
	}
}

/**
Encodes a class as an object, with the class discriminator first for a polymorphic value.
*/
@OptIn(ExperimentalSerializationApi::class)
internal class ObjectEncoder(
	soml: Soml,
	path: Path?,
	descriptor: SerialDescriptor,
	private val consumer: (SomlValue) -> Unit,
) : StructureEncoder(soml, path) {
	private val keys = soml.keysOf(descriptor)
	// Note: Not using buildMap() because members arrive in later serializer callbacks outside the builder lambda's lifetime.
	private val members = LinkedHashMap<String, SomlValue>(descriptor.elementsCount + descriptor.elementsCount / 3 + 1)

	override fun element(descriptor: SerialDescriptor, index: Int): Encoder {
		val key = keys.keys[index]
		return ValueEncoder(soml, Path(path, key)) { members[key] = it }
	}

	override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean = soml.configuration.encodeDefaults

	override fun <T : Any> encodeNullableSerializableElement(descriptor: SerialDescriptor, index: Int, serializer: SerializationStrategy<T>, value: T?) {
		if (value == null && !soml.configuration.explicitNulls) {
			return
		}

		super.encodeNullableSerializableElement(descriptor, index, serializer, value)
	}

	override fun endStructure(descriptor: SerialDescriptor) {
		consumer(if (members.isEmpty()) SomlObject.Empty else SomlObject.trusted(members))
	}
}

internal class ArrayEncoder(soml: Soml, path: Path?, size: Int, private val consumer: (SomlValue) -> Unit) : StructureEncoder(soml, path) {
	private val items = ArrayList<SomlValue>(size)

	override fun element(descriptor: SerialDescriptor, index: Int): Encoder = ValueEncoder(soml, Path(path, index)) { items.add(it) }

	override fun endStructure(descriptor: SerialDescriptor) {
		consumer(if (items.isEmpty()) SomlArray.Empty else SomlArray.trusted(items))
	}
}

/**
Encodes a map as an object. A map's elements alternate: an even index is a key, and the odd index after it is its value. A key is written as a string, so it must be a string, a char, an enum, a bool, or an int.
*/
internal class MapEncoder(soml: Soml, path: Path?, size: Int, private val consumer: (SomlValue) -> Unit) : StructureEncoder(soml, path) {
	// Note: Not using LinkedHashMap.newLinkedHashMap() because this encoder must also support non-JVM targets and Android API 33.
	private val members = LinkedHashMap<String, SomlValue>(size + size / 3 + 1)
	// Note: Not using Delegates.notNull() or lateinit because the former adds allocation and the latter keeps the same read guard without a measured encoding benefit.
	private var key: String? = null

	override fun element(descriptor: SerialDescriptor, index: Int): Encoder {
		if (index % 2 == 0) {
			return KeyEncoder(
				soml,
				path,
				keyConsumer = { key ->
					if (key in members) {
						throw SomlException.atPath("Two keys of the map are both written as ${describeKey(key)}", path.toList())
					}

					this.key = key
				},
			)
		}

		val key = checkNotNull(key) { "A map value came before its key" }
		return ValueEncoder(soml, Path(path, key)) { members[key] = it }
	}

	override fun endStructure(descriptor: SerialDescriptor) {
		consumer(if (members.isEmpty()) SomlObject.Empty else SomlObject.trusted(members))
	}
}

/**
Encodes a map key as the string a document writes it as.
*/
private class KeyEncoder(
	soml: Soml,
	path: Path?,
	private val keyConsumer: (String) -> Unit,
	// Whether the key is an unsigned type, whose serializer gives the bits of the signed type of its size.
	private val isUnsigned: Boolean = false,
) : ValueEncoder(soml, path, consumer = {}) {
	private fun key(value: String) {
		findUnrepresentable(value)?.let {
			throw fail(it)
		}

		keyConsumer(value)
	}

	override fun encodeBoolean(value: Boolean) {
		key(value.toString())
	}

	override fun encodeByte(value: Byte) {
		key(if (isUnsigned) value.toUByte().toString() else value.toString())
	}

	override fun encodeShort(value: Short) {
		key(if (isUnsigned) value.toUShort().toString() else value.toString())
	}

	override fun encodeInt(value: Int) {
		key(if (isUnsigned) value.toUInt().toString() else value.toString())
	}

	override fun encodeLong(value: Long) {
		if (isUnsigned && value < 0) {
			throw fail("The int ${value.toULong()} is outside the 64-bit range of a SOML int, so it cannot be a key that reads back as one")
		}

		key(value.toString())
	}

	override fun encodeString(value: String) {
		key(value)
	}

	override fun encodeInline(descriptor: SerialDescriptor): Encoder = if (unsignedRange(descriptor) != null) KeyEncoder(soml, path, keyConsumer, isUnsigned = true) else this

	override fun encodeNull() {
		throw fail("A map key cannot be null")
	}

	override fun encodeFloat(value: Float) {
		encodeDouble(value.toDouble())
	}

	override fun encodeDouble(value: Double) {
		throw fail("A map key cannot be a float, because a key is a string and a float has no exact spelling as one")
	}

	override fun encodeSomlValue(value: SomlValue) {
		when (value) {
			is SomlString -> key(value.value)
			is SomlInt -> key(value.value.toString())
			else -> throw fail("A map key cannot be ${Writer.describeType(value)}")
		}
	}

	override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder = throw fail("A map key cannot be ${if (descriptor.kind == StructureKind.LIST) "an array" else "an object"}, because a key is a string")

	override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder = beginStructure(descriptor)

	override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
		serializer.serialize(this, value)
	}
}
