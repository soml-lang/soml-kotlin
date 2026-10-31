package sh.soml.kotlin

import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.StringFormat
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.overwriteWith
import kotlinx.serialization.serializer

/**
Reads and writes SOML with kotlinx.serialization, as `Json` does for JSON.

```kotlin
@Serializable
data class Server(val host: String, val port: Int, val timeout: Duration)

val server = Soml.decodeFromString<Server>("host: 'example.com'\nport: 8080\ntimeout: 30s")

Soml.encodeToString(server)
//=> "host: 'example.com'\nport: 8080\ntimeout: 30s\n"
```

Use the default instance, `Soml`, or make one with other settings with `Soml { … }`. An instance is immutable and safe to share between threads, and it caches what it learns about your classes, so keep one rather than making one per call.

A document is always an object or an array, so the value to encode or decode as a whole document is a class, a map, or a collection. `encodeToSomlValue` and `decodeFromSomlValue` take a value of any type.
*/
public sealed class Soml(
	/**
	The settings of this instance.
	*/
	public val configuration: SomlConfiguration,
	final override val serializersModule: SerializersModule,
) : StringFormat, BinaryFormat {
	// The SOML keys of each class, found once per descriptor.
	private val keysCache = ConcurrentCache<SerialDescriptor, ElementKeys>()

	internal fun keysOf(descriptor: SerialDescriptor): ElementKeys = keysCache.getOrPut(descriptor) { ElementKeys(descriptor, configuration.namingStrategy) }

	/**
	The default instance: unknown keys are an error, default values are not written, `null` is written, the class discriminator is `type`, and keys are the names of your properties.
	*/
	public companion object Default : Soml(SomlConfiguration(), defaultSerializersModule)

	/**
	Writes `value` as a document, with one member or item per line, and members in the order the serializer gives them, which is the order of a class's properties and of a map's iteration, or in canonical form when `SomlConfiguration.canonical` is set.

	@throws SomlException When the value is not an object or an array, or cannot be written, such as a float that is NaN or a string that holds U+000D.
	*/
	final override fun <T> encodeToString(serializer: SerializationStrategy<T>, value: T): String {
		val tree = encodeToSomlValue(serializer, value)

		if (tree !is SomlObject && tree !is SomlArray) {
			throw SomlException("A document is an object or an array, so ${Writer.describeType(tree)} cannot be written as one. Encode it with encodeToSomlValue(), or put it in a class")
		}

		return Writer.writeDocument(tree, configuration.canonical)
	}

	/**
	Reads a document into a value of type `T`.

	@throws SomlException When the document is not valid, or does not fit `T`. The exception has the line and column of the problem.
	*/
	final override fun <T> decodeFromString(deserializer: DeserializationStrategy<T>, string: String): T {
		val tree = Parser.parse(string)

		try {
			return decodeTree(deserializer, tree)
		} catch (error: SomlException) {
			throw locate(error, string)
		}
	}

	/**
	Writes `value` as a document in UTF-8.

	@throws SomlException When the value is not an object or an array, or cannot be written.
	*/
	final override fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray = encodeToString(serializer, value).encodeToByteArray()

	/**
	Reads a document from UTF-8 bytes into a value of type `T`. Bytes that are not well-formed UTF-8 are an error, never replaced with U+FFFD.

	@throws SomlException When the bytes are not valid UTF-8, the document is not valid, or it does not fit `T`.
	*/
	final override fun <T> decodeFromByteArray(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T = decodeFromString(deserializer, Parser.decodeUtf8(bytes))

	/**
	Converts `value` to a SOML value, which can be any SOML value, not only an object or an array.

	@throws SomlException When the value cannot be represented, such as a float that is NaN.
	*/
	public fun <T> encodeToSomlValue(serializer: SerializationStrategy<T>, value: T): SomlValue {
		var result: SomlValue? = null
		ValueEncoder(this, null) { result = it }.encodeSerializableValue(serializer, value)
		return checkNotNull(result) { "The serializer for ${serializer.descriptor.serialName} wrote nothing" }
	}

	/**
	Converts a SOML value to a value of type `T`.

	@throws SomlException When `value` does not fit `T`. The exception has the path of the problem, but no position, because a value has no text.
	*/
	public fun <T> decodeFromSomlValue(deserializer: DeserializationStrategy<T>, value: SomlValue): T = decodeTree(deserializer, value)

	/**
	Reads a document into its value, which is always a `SomlObject` or a `SomlArray`.

	@throws SomlException When the document is not valid.
	*/
	public fun parseToSomlValue(string: String): SomlValue = Parser.parse(string)

	/**
	Reads a document from UTF-8 bytes into its value, which is always a `SomlObject` or a `SomlArray`.

	@throws SomlException When the bytes are not valid UTF-8 or the document is not valid.
	*/
	public fun parseToSomlValue(bytes: ByteArray): SomlValue = Parser.parse(Parser.decodeUtf8(bytes))

	/**
	Formats a document by the normative formatting rules of the spec: one tab of indentation per level, every member and item of a container that spans lines on its own line, no commas there, at most one blank line in a row, and no trailing whitespace. A container on one line stays on one line. Comments, member order, block strings, and the spelling of every value stay as they are, so `0o644` stays `0o644`.

	The result does not depend on the settings of this instance, and formatting it again gives the same text.

	```kotlin
	Soml.format("pool: {min: 2,   max: 16,} # Connections")
	//=> "pool: {min: 2, max: 16} # Connections\n"
	```

	@throws SomlException When the document is not valid.
	*/
	public fun format(string: String): String = Formatter.format(string)

	private fun <T> decodeTree(deserializer: DeserializationStrategy<T>, value: SomlValue): T = ValueDecoder(this, value, null).decodeSerializableValue(deserializer)

	/**
	A decoding error with the position of the value it is about, found in the syntax tree of the document only now that there is an error.
	*/
	private fun locate(error: SomlException, text: String): SomlException {
		if (error.line != 0) {
			return error
		}

		val offset = SyntaxTree.ofValid(text).offsetOf(error.path, isKey = error.isAboutKey)
		return error.located(text, offset)
	}
}

/**
The settings of a `Soml` instance. Make an instance with them with `Soml { … }`.
*/
public class SomlConfiguration internal constructor(
	/**
	Whether a key that no property has is ignored, rather than an error. An error catches a typo, such as `prot: 80`.
	*/
	public val ignoreUnknownKeys: Boolean = false,
	/**
	Whether a property whose value is its default is written.
	*/
	public val encodeDefaults: Boolean = false,
	/**
	Whether a property whose value is `null` is written as `null`. When false, it is left out, and when decoding, a missing nullable property without a default is `null`.
	*/
	public val explicitNulls: Boolean = true,
	/**
	Whether documents are written in canonical form, with members sorted by key, so that the same value always gives the same bytes. Use it to hash, sign, or compare documents, and to write a `HashMap`, which iterates in no particular order.
	*/
	public val canonical: Boolean = false,
	/**
	The key of the member that names the subtype of a polymorphic value, such as a sealed class.
	*/
	public val classDiscriminator: String = "type",
	/**
	How serial names become keys, such as `SomlNamingStrategy.KebabCase`, which turns `maxConnections` into `max-connections`. The strategy also transforms names supplied by `@SerialName`.
	*/
	public val namingStrategy: SomlNamingStrategy? = null,
) {
	override fun toString(): String = "SomlConfiguration(ignoreUnknownKeys=$ignoreUnknownKeys, encodeDefaults=$encodeDefaults, explicitNulls=$explicitNulls, canonical=$canonical, classDiscriminator='$classDiscriminator', namingStrategy=$namingStrategy)"
}

/**
Makes a `Soml` instance with the settings of `from`, changed by `builderAction`.

```kotlin
val soml = Soml {
	namingStrategy = SomlNamingStrategy.KebabCase
	ignoreUnknownKeys = true
}
```
*/
public fun Soml(from: Soml = Soml.Default, builderAction: SomlBuilder.() -> Unit): Soml {
	val builder = SomlBuilder(from)
	builder.builderAction()
	// A serializer in the builder's module replaces a default one, such as that of `java.time.Instant`.
	return SomlImplementation(builder.build(), defaultSerializersModule overwriteWith builder.serializersModule)
}

/**
The settings that `Soml { … }` changes, which start as those of the instance it is made from.
*/
public class SomlBuilder internal constructor(from: Soml) {
	/**
	Whether a key that no property has is ignored, rather than an error. Off by default.
	*/
	public var ignoreUnknownKeys: Boolean = from.configuration.ignoreUnknownKeys

	/**
	Whether a property whose value is its default is written. Off by default.
	*/
	public var encodeDefaults: Boolean = from.configuration.encodeDefaults

	/**
	Whether a property whose value is `null` is written as `null`, and a missing nullable property without a default is an error. On by default.
	*/
	public var explicitNulls: Boolean = from.configuration.explicitNulls

	/**
	Whether documents are written in canonical form, with members sorted by key. Off by default.
	*/
	public var canonical: Boolean = from.configuration.canonical

	/**
	The key of the member that names the subtype of a polymorphic value. `type` by default.
	*/
	public var classDiscriminator: String = from.configuration.classDiscriminator

	/**
	How serial names become keys, including names supplied by `@SerialName`, such as `SomlNamingStrategy.KebabCase`. None by default, so a key is the property's serial name.
	*/
	public var namingStrategy: SomlNamingStrategy? = from.configuration.namingStrategy

	/**
	Serializers for contextual and polymorphic types. On the JVM and Android, serializers for `java.time.LocalDate`, `java.time.Instant`, and `java.time.Duration` are always included, and one here replaces them.
	*/
	public var serializersModule: SerializersModule = from.serializersModule

	internal fun build(): SomlConfiguration {
		require(classDiscriminator.isNotEmpty()) { "The class discriminator cannot be empty" }
		checkString(classDiscriminator)

		return SomlConfiguration(
			ignoreUnknownKeys = ignoreUnknownKeys,
			encodeDefaults = encodeDefaults,
			explicitNulls = explicitNulls,
			canonical = canonical,
			classDiscriminator = classDiscriminator,
			namingStrategy = namingStrategy,
		)
	}
}

private class SomlImplementation(configuration: SomlConfiguration, module: SerializersModule) : Soml(configuration, module)

/**
Converts a SOML value to a value of type `T`.

@throws SomlException When `value` does not fit `T`.
*/
public inline fun <reified T> Soml.decodeFromSomlValue(value: SomlValue): T = decodeFromSomlValue(serializersModule.serializer(), value)

/**
Converts `value` to a SOML value.

@throws SomlException When the value cannot be represented, such as a float that is NaN.
*/
public inline fun <reified T> Soml.encodeToSomlValue(value: T): SomlValue = encodeToSomlValue(serializersModule.serializer(), value)
