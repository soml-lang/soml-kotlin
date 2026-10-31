package sh.soml.kotlin

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.internal.AbstractPolymorphicSerializer
import kotlinx.serialization.modules.SerializersModule

/**
A decoder of the SOML format. A serializer can use it to read a value as a `SomlValue`, as `JsonDecoder` does for JSON:

```kotlin
override fun deserialize(decoder: Decoder): Color {
	val value = (decoder as SomlDecoder).decodeSomlValue()
	…
}
```
*/
public interface SomlDecoder : Decoder {
	/**
	The instance that is decoding.
	*/
	public val soml: Soml

	/**
	The value being decoded, as it is in the document.
	*/
	public fun decodeSomlValue(): SomlValue
}

/**
A path to a value, as a linked list from the value to the top, so that a child costs one small object. It becomes a list only for an error.
*/
internal class Path(private val parent: Path?, private val element: Any) {
	// Note: Not using generateSequence() because depth checks only need a loop without sequence and iterator allocations.
	val depth: Int
		get() {
			var depth = 0
			var node: Path? = this

			while (node != null) {
				depth++
				node = node.parent
			}

			return depth
		}

	fun toList(): List<Any> {
		// Note: Not using ArrayDeque.addFirst() because the error's defensive snapshot adds another JVM array copy for paths longer than one element.
		val elements = ArrayList<Any>(depth)
		var node: Path? = this

		while (node != null) {
			elements.add(node.element)
			node = node.parent
		}

		elements.reverse()
		return elements
	}
}

internal fun Path?.toList(): List<Any> = this?.toList() ?: emptyList()

/**
Decodes one value of the tree.
*/
@OptIn(ExperimentalSerializationApi::class)
internal class ValueDecoder(
	override val soml: Soml,
	val value: SomlValue,
	private val path: Path?,
	// The class discriminator, which the object of a polymorphic value skips, as the members of its subtype.
	private val discriminator: String? = null,
) : SomlDecoder {
	override val serializersModule: SerializersModule
		get() = soml.serializersModule

	override fun decodeSomlValue(): SomlValue = value

	private fun fail(reason: String, cause: Throwable? = null): SomlException = SomlException.atPath(reason, path.toList(), cause)

	private fun mismatch(expected: String): SomlException = fail("Expected $expected, but found ${describeValue(value)}")

	private fun integer(expected: String, minimum: Long, maximum: Long): Long {
		val number = (value as? SomlInt ?: throw mismatch(expected)).value

		if (number !in minimum..maximum) {
			throw fail("Expected $expected, but found $number, which is outside its range ($minimum to $maximum)")
		}

		return number
	}

	override fun decodeNotNullMark(): Boolean = value != SomlNull

	override fun decodeNull(): Nothing? {
		if (value != SomlNull) {
			throw mismatch("null")
		}

		return null
	}

	override fun decodeBoolean(): Boolean = (value as? SomlBool ?: throw mismatch("a bool")).value

	override fun decodeByte(): Byte = integer("an int", Byte.MIN_VALUE.toLong(), Byte.MAX_VALUE.toLong()).toByte()

	override fun decodeShort(): Short = integer("an int", Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()

	override fun decodeInt(): Int = integer("an int", Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

	override fun decodeLong(): Long = (value as? SomlInt ?: throw mismatch("an int")).value

	override fun decodeFloat(): Float {
		if (value is SomlInt) {
			return value.value.toFloat()
		}

		val number = decodeDouble()

		// The range is checked on the double, because on JavaScript, a `Float` is a double too, and does not overflow. From halfway between the largest `Float` and 2^128 on, a `Float` rounds to infinity.
		if (number.isFinite() && kotlin.math.abs(number) >= 3.4028235677973366E38) {
			throw fail("Expected a float, but found $number, which is too large for a 32-bit float")
		}

		return number.toFloat()
	}

	override fun decodeDouble(): Double = when (value) {
		is SomlFloat -> value.value
		// An int is a float too when the float holds it exactly. Above 2^53, not every int is exact.
		// Note: Not using countLeadingZeroBits()/countTrailingZeroBits() because public decoding probes showed no reliable benefit over this exact conversion check.
		is SomlInt if value.value.toDouble().let { it.toLong() == value.value && it != 9.223372036854775807E18 } -> value.value.toDouble()
		is SomlInt -> throw fail("Expected a float, but found the int ${value.value}, which a float cannot hold exactly")
		else -> throw mismatch("a float")
	}

	override fun decodeChar(): Char {
		// Note: Not using singleOrNull() because its nullable Char result may box on Native, while this decoder returns a primitive.
		val string = decodeString()

		if (string.length != 1) {
			throw fail("Expected a string of one character, but found ${describeValue(value)}")
		}

		return string[0]
	}

	override fun decodeString(): String = (value as? SomlString ?: throw mismatch("a string")).value

	override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
		val name = decodeString()
		val index = enumDescriptor.getElementIndex(name)

		if (index == CompositeDecoder.UNKNOWN_NAME) {
			val names = (0..<enumDescriptor.elementsCount).joinToString { "'${enumDescriptor.getElementName(it)}'" }
			throw fail("Expected one of $names, but found ${describeValue(value)}")
		}

		return index
	}

	override fun decodeInline(descriptor: SerialDescriptor): Decoder = unsignedRange(descriptor)?.let { UnsignedDecoder(this, it) } ?: this

	/**
	The value as an int in `range`, for an unsigned type, whose serializer reinterprets the bits of the signed type of its size.
	*/
	fun decodeUnsigned(range: LongRange): Long = integer("an int that is not negative", range.first, range.last)

	override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
		if ((path?.depth ?: 0) >= MAX_DEPTH) {
			throw fail("A document cannot nest more than $MAX_DEPTH levels deep")
		}

		return when (descriptor.kind) {
			StructureKind.LIST -> ArrayDecoder(soml, value as? SomlArray ?: throw mismatch("an array"), path)
			StructureKind.MAP -> MapDecoder(soml, value as? SomlObject ?: throw mismatch("an object"), path)
			// A polymorphic value is decoded in `decodeSerializableValue()`, so only a custom serializer of that kind gets here, and it reads the subtype and the value as two members.
			else -> {
				val members = value as? SomlObject ?: throw mismatch("an object")

				if (discriminator != null) {
					soml.keysOf(descriptor).checkNotKey(discriminator, descriptor, path.toList())
				}

				ObjectDecoder(soml, members, path, descriptor, discriminator)
			}
		}
	}

	@OptIn(InternalSerializationApi::class)
	override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
		// Note: Not using runCatching() because preserving these typed failures needs extra dispatch and a branch to rethrow other Throwables.
		try {
			@Suppress("UNCHECKED_CAST")
			return when (deserializer.descriptor.serialName) {
				DURATION_SERIAL_NAME if deserializer.descriptor.kind == PrimitiveKind.STRING -> {
					val duration = value as? SomlDuration ?: throw mismatch("a duration")

					try {
						duration.toDuration() as T
					} catch (error: IllegalStateException) {
						throw fail(error.message ?: "The duration is too long", error)
					}
				}
				INSTANT_SERIAL_NAME if deserializer.descriptor.kind == PrimitiveKind.STRING -> (value as? SomlInstant ?: throw mismatch("an instant")).value as T
				else if deserializer is AbstractPolymorphicSerializer<*> -> decodePolymorphic(deserializer) as T
				else -> deserializer.deserialize(this)
			}
		} catch (error: SomlException) {
			throw error.placed(path.toList())
		} catch (error: IllegalArgumentException) {
			// The error of a serializer or of a class's own validation, such as a `require()` in its `init` block, which is reported at the value.
			throw fail(error.message ?: error.toString(), error)
		}
	}

	@OptIn(InternalSerializationApi::class)
	private fun decodePolymorphic(deserializer: AbstractPolymorphicSerializer<*>): Any? {
		val members = value as? SomlObject ?: throw mismatch("an object")
		val discriminator = soml.configuration.classDiscriminator
		// Note: Not using getOrElse() because the Elvis fallback already expresses the same lazy missing-key failure more simply.
		val type = members[discriminator] ?: throw fail("Expected the member ${describeKey(discriminator)}, which names the type of the object")
		val typePath = Path(path, discriminator).toList()
		val typeName = (type as? SomlString)?.value ?: throw SomlException.atPath("Expected a string that names the type, but found ${describeValue(type)}", typePath)

		@Suppress("UNCHECKED_CAST")
		val subtype = (deserializer as AbstractPolymorphicSerializer<Any>).findPolymorphicSerializerOrNull(ObjectDecoder(soml, members, path, deserializer.descriptor, discriminator), typeName)
			?: throw SomlException.atPath("${describeValue(type)} is not a known type of ${deserializer.baseClass.simpleName ?: deserializer.descriptor.serialName}", typePath)

		// The discriminator is skipped by the decoder of the subtype's members, also when its serializer hands this decoder to another one.
		return subtype.deserialize(ValueDecoder(soml, value, path, discriminator))
	}

	private class UnsignedDecoder(private val decoder: ValueDecoder, private val range: LongRange) : SomlDecoder by decoder {
		override fun decodeByte(): Byte = decoder.decodeUnsigned(range).toByte()

		override fun decodeShort(): Short = decoder.decodeUnsigned(range).toShort()

		override fun decodeInt(): Int = decoder.decodeUnsigned(range).toInt()

		override fun decodeLong(): Long = decoder.decodeUnsigned(range)
	}
}

/**
The common part of the decoders of an object, an array, and a map, which hand each element to a `ValueDecoder`.
*/
@OptIn(ExperimentalSerializationApi::class)
internal abstract class StructureDecoder(protected val soml: Soml, protected val path: Path?) : CompositeDecoder {
	override val serializersModule: SerializersModule
		get() = soml.serializersModule

	/**
	The decoder of the element at `index`.
	*/
	protected abstract fun element(descriptor: SerialDescriptor, index: Int): SomlDecoder

	override fun endStructure(descriptor: SerialDescriptor) {
	}

	override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean = element(descriptor, index).decodeBoolean()

	override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte = element(descriptor, index).decodeByte()

	override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char = element(descriptor, index).decodeChar()

	override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short = element(descriptor, index).decodeShort()

	override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int = element(descriptor, index).decodeInt()

	override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long = element(descriptor, index).decodeLong()

	override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float = element(descriptor, index).decodeFloat()

	override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double = element(descriptor, index).decodeDouble()

	override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String = element(descriptor, index).decodeString()

	override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder = element(descriptor, index).decodeInline(descriptor.getElementDescriptor(index))

	override fun <T> decodeSerializableElement(descriptor: SerialDescriptor, index: Int, deserializer: DeserializationStrategy<T>, previousValue: T?): T = element(descriptor, index).decodeSerializableValue(deserializer)

	override fun <T : Any> decodeNullableSerializableElement(descriptor: SerialDescriptor, index: Int, deserializer: DeserializationStrategy<T?>, previousValue: T?): T? = element(descriptor, index).decodeNullableSerializableValue(deserializer)
}

/**
Decodes the members of an object into a class, skipping the class discriminator when `discriminator` is not null.
*/
@OptIn(ExperimentalSerializationApi::class)
internal class ObjectDecoder(
	soml: Soml,
	private val members: SomlObject,
	path: Path?,
	descriptor: SerialDescriptor,
	private val discriminator: String?,
) : StructureDecoder(soml, path) {
	private val keys = soml.keysOf(descriptor)
	private val iterator = members.entries.iterator()
	// The value of each element by its index, filled as the members are read.
	private val values = arrayOfNulls<SomlValue>(descriptor.elementsCount)
	// The element after which the decoder looks for missing elements, once every member is read.
	private var missingIndex = -1

	override fun element(descriptor: SerialDescriptor, index: Int): ValueDecoder = ValueDecoder(soml, values[index] ?: SomlNull, Path(path, keys.keys[index]))

	override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
		while (iterator.hasNext()) {
			val (key, value) = iterator.next()

			if (key == discriminator) {
				continue
			}

			val index = keys.indexOf(key)

			if (index == -1) {
				if (soml.configuration.ignoreUnknownKeys) {
					continue
				}

				throw SomlException.atPath(unknownKeyReason(key, descriptor), Path(path, key).toList()).also { it.isAboutKey = true }
			}

			values[index] = value
			return index
		}

		// Every member is read. A missing element that is not optional is an error, except a nullable one when nulls need not be written, which is null.
		while (++missingIndex < values.size) {
			if (values[missingIndex] != null || descriptor.isElementOptional(missingIndex)) {
				continue
			}

			if (!soml.configuration.explicitNulls && descriptor.getElementDescriptor(missingIndex).isNullable) {
				return missingIndex
			}

			throw SomlException.atPath("Missing the member ${describeKey(keys.keys[missingIndex])}", path.toList())
		}

		return CompositeDecoder.DECODE_DONE
	}

	private fun unknownKeyReason(key: String, descriptor: SerialDescriptor): String {
		// Note: Not using minByOrNull() because losing the improving-distance pruning measured slower and allocated more for similar-length keys.
		var suggestion: String? = null
		var bestDistance = maxOf(1, key.length / 3) + 1

		if (key.length <= 64) {
			for ((index, candidate) in keys.keys.withIndex()) {
				if (values[index] != null || kotlin.math.abs(candidate.length - key.length) >= bestDistance) {
					continue
				}

				val distance = editDistance(candidate, key)

				if (distance < bestDistance) {
					suggestion = candidate
					bestDistance = distance
				}
			}
		}

		val hint = if (suggestion == null) "" else ". Did you mean ${describeKey(suggestion)}?"
		return "Unknown key ${describeKey(key)} for ${descriptor.serialName.substringAfterLast('.')}$hint"
	}
}

/**
The number of single-character insertions, deletions, substitutions, and swaps of two neighbors that turn `left` into `right` (the optimal string alignment distance), for the suggestion of an unknown key. Long keys are not compared, so that a suggestion never costs much.
*/
private fun editDistance(left: String, right: String): Int {
	if (left.length > 64 || right.length > 64) {
		return Int.MAX_VALUE
	}

	if (left == right) {
		return 0
	}

	if (left.isEmpty()) {
		return right.length
	}

	if (right.isEmpty()) {
		return left.length
	}

	var previousPrevious = IntArray(right.length + 1)
	var previous = IntArray(right.length + 1) { it }
	var current = IntArray(right.length + 1)

	for (leftIndex in 1..left.length) {
		current[0] = leftIndex

		for (rightIndex in 1..right.length) {
			val cost = if (left[leftIndex - 1] == right[rightIndex - 1]) 0 else 1
			var distance = minOf(previous[rightIndex - 1] + cost, previous[rightIndex] + 1, current[rightIndex - 1] + 1)

			if (leftIndex > 1 && rightIndex > 1 && left[leftIndex - 1] == right[rightIndex - 2] && left[leftIndex - 2] == right[rightIndex - 1]) {
				distance = minOf(distance, previousPrevious[rightIndex - 2] + 1)
			}

			current[rightIndex] = distance
		}

		val reusable = previousPrevious
		previousPrevious = previous
		previous = current
		current = reusable
	}

	return previous[right.length]
}

@OptIn(ExperimentalSerializationApi::class)
internal class ArrayDecoder(soml: Soml, private val items: SomlArray, path: Path?) : StructureDecoder(soml, path) {
	private var index = -1

	override fun element(descriptor: SerialDescriptor, index: Int): ValueDecoder = ValueDecoder(soml, items[index], Path(path, index))

	override fun decodeSequentially(): Boolean = true

	override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = items.size

	override fun decodeElementIndex(descriptor: SerialDescriptor): Int = if (++index < items.size) index else CompositeDecoder.DECODE_DONE
}

/**
Decodes the members of an object into a map. A map's elements alternate: an even index is a key, and the odd index after it is its value.
*/
@OptIn(ExperimentalSerializationApi::class)
internal class MapDecoder(soml: Soml, members: SomlObject, path: Path?) : StructureDecoder(soml, path) {
	// Note: Not using toTypedArray() because empty-map decoding allocated more in the public JVM probes.
	private val entries = members.entries.toList()
	private var index = -1

	override fun element(descriptor: SerialDescriptor, index: Int): SomlDecoder {
		val (key, value) = entries[index / 2]
		val elementPath = Path(path, key)
		return if (index % 2 == 0) KeyDecoder(ValueDecoder(soml, SomlString.trusted(key), elementPath), key, elementPath) else ValueDecoder(soml, value, elementPath)
	}

	override fun decodeSequentially(): Boolean = true

	override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = entries.size

	override fun decodeElementIndex(descriptor: SerialDescriptor): Int = if (++index < entries.size * 2) index else CompositeDecoder.DECODE_DONE
}

/**
The decoder of a map key, which is a string in the document and can be decoded as a string, an enum, a char, or an int written in decimal without leading zeros, as in `404: 'Not found'`.
*/
private class KeyDecoder(
	private val decoder: ValueDecoder,
	private val key: String,
	private val path: Path,
	// The range of an unsigned type, whose serializer reinterprets the bits of the signed type of its size.
	private val unsignedRange: LongRange? = null,
) : SomlDecoder by decoder {
	private fun fail(reason: String): SomlException = SomlException.atPath(reason, path.toList()).also { it.isAboutKey = true }

	/**
	Runs `decode`, and makes an error from it point at the key, rather than at the value of the member.
	*/
	private inline fun <T> aboutKey(decode: () -> T): T = try {
		decode()
	} catch (error: SomlException) {
		throw error.also { it.isAboutKey = true }
	}

	override fun decodeString(): String = aboutKey { decoder.decodeString() }

	override fun decodeChar(): Char = aboutKey { decoder.decodeChar() }

	override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = aboutKey { decoder.decodeEnum(enumDescriptor) }

	override fun decodeBoolean(): Boolean = key.toBooleanStrictOrNull() ?: throw fail("Expected a key that is true or false, but found ${describeKey(key)}")

	private fun integer(signedMinimum: Long, signedMaximum: Long): Long {
		val minimum = unsignedRange?.first ?: signedMinimum
		val maximum = unsignedRange?.last ?: signedMaximum
		val number = parseDecimal(key) ?: throw fail("Expected a key that is an int in decimal, such as 404, but found ${describeKey(key)}")

		if (number !in minimum..maximum) {
			throw fail("Expected a key that is an int from $minimum to $maximum, but found $key")
		}

		return number
	}

	override fun decodeByte(): Byte = integer(Byte.MIN_VALUE.toLong(), Byte.MAX_VALUE.toLong()).toByte()

	override fun decodeShort(): Short = integer(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()

	override fun decodeInt(): Int = integer(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

	override fun decodeLong(): Long = integer(Long.MIN_VALUE, Long.MAX_VALUE)

	override fun decodeInline(descriptor: SerialDescriptor): Decoder = unsignedRange(descriptor)?.let { KeyDecoder(decoder, key, path, it) } ?: this

	override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T = aboutKey {
		try {
			deserializer.deserialize(this)
		} catch (error: SomlException) {
			throw error.placed(path.toList())
		} catch (error: IllegalArgumentException) {
			// The error of a custom serializer of a key, which is reported at the key.
			throw SomlException.atPath(error.message ?: error.toString(), path.toList(), error)
		}
	}

	companion object {
		/**
		The value of a decimal int written as canonical form writes it, with no leading zeros and no `+`, or null.
		*/
		fun parseDecimal(text: String): Long? {
			val number = text.toLongOrNull() ?: return null
			return number.takeIf { it.toString() == text }
		}
	}
}

/**
The range of an unsigned type, which is written as its value, for a descriptor of one, or null. The serializer of an unsigned type reinterprets the bits of the signed type of its size, so `ULong` reaches only the int64 range of SOML.
*/
internal fun unsignedRange(descriptor: SerialDescriptor): LongRange? = when (descriptor.serialName) {
	"kotlin.UByte" -> 0..UByte.MAX_VALUE.toLong()
	"kotlin.UShort" -> 0..UShort.MAX_VALUE.toLong()
	"kotlin.UInt" -> 0..UInt.MAX_VALUE.toLong()
	"kotlin.ULong" -> 0..Long.MAX_VALUE
	else -> null
}
