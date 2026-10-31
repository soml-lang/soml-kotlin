package sh.soml.kotlin

import kotlinx.serialization.descriptors.SerialDescriptor

/**
Turns the name of a property into its key, for every class that a `Soml` instance reads and writes.

```kotlin
val soml = Soml { namingStrategy = SomlNamingStrategy.KebabCase }

@Serializable
data class Pool(val maxConnections: Int)

soml.encodeToString(Pool(16))
//=> "max-connections: 16\n"
```

It applies to the names of properties, not to the names of enum constants or of the subtypes of a polymorphic value. A property with `@SerialName` gets that name, which the strategy then changes too, as in `Json`.
*/
public fun interface SomlNamingStrategy {
	/**
	The key of the property at `elementIndex` of `descriptor`, whose name is `serialName`.
	*/
	public fun serialNameForSoml(descriptor: SerialDescriptor, elementIndex: Int, serialName: String): String

	public companion object {
		/**
		Words in lowercase, separated by `-`, the usual style of SOML keys: `maxConnections` becomes `max-connections`, and `httpURL` becomes `http-url`.
		*/
		public val KebabCase: SomlNamingStrategy = object : SomlNamingStrategy {
			override fun serialNameForSoml(descriptor: SerialDescriptor, elementIndex: Int, serialName: String): String = splitWords(serialName, '-')

			override fun toString(): String = "SomlNamingStrategy.KebabCase"
		}

		/**
		Words in lowercase, separated by `_`: `maxConnections` becomes `max_connections`.
		*/
		public val SnakeCase: SomlNamingStrategy = object : SomlNamingStrategy {
			override fun serialNameForSoml(descriptor: SerialDescriptor, elementIndex: Int, serialName: String): String = splitWords(serialName, '_')

			override fun toString(): String = "SomlNamingStrategy.SnakeCase"
		}
	}
}

/**
A camel case name as lowercase words with `separator` between them. A run of capitals is one word, whose last capital starts the next word when a lowercase letter follows it, so `httpURLValue` becomes `http-url-value`. This is the rule of `JsonNamingStrategy`.
*/
// Note: Not using JsonNamingStrategy because it would add the JSON format module as a production dependency just for name conversion.
// Note: Not using regex word splitting with lowercase() because it changes separator boundaries and one-to-many Unicode casing.
private fun splitWords(name: String, separator: Char): String {
	if (name.none(Char::isUpperCase)) {
		return name
	}

	return buildString(name.length + 4) {
		var bufferedCapital: Char? = null

		for (character in name) {
			if (character.isUpperCase()) {
				if (bufferedCapital == null && isNotEmpty() && last() != separator) {
					append(separator)
				}

				bufferedCapital?.let(::append)
				bufferedCapital = character.lowercaseChar()
				continue
			}

			val capital = bufferedCapital

			if (capital != null) {
				// A lowercase letter after a run of capitals starts a word with the last capital of the run.
				if (character.isLowerCase() && isNotEmpty() && last() != separator) {
					append(separator)
				}

				append(capital)
				bufferedCapital = null
			}

			append(character)
		}

		bufferedCapital?.let(::append)
	}
}

/**
The SOML keys of the elements of a class, with their indexes by key.

@throws SomlException When two elements have the same key, which a naming strategy can cause, as `maxConnections` and `max_connections` do with `SnakeCase`.
*/
internal class ElementKeys(descriptor: SerialDescriptor, namingStrategy: SomlNamingStrategy?) {
	// Note: Not using elementNames.mapIndexed().toTypedArray() because its intermediate list measured slower and allocated more than the indexed array.
	val keys: Array<String> = Array(descriptor.elementsCount) { index ->
		val name = descriptor.getElementName(index)
		namingStrategy?.serialNameForSoml(descriptor, index, name) ?: name
	}

	// Note: Not using associateBy() because duplicate keys must report both properties instead of silently keeping the last one.
	private val indexByKey: Map<String, Int> = HashMap<String, Int>(keys.size * 2).also { map ->
		// Note: Not using forEachIndexed() because it showed no consistent benefit in complete descriptor-key validation.
		for ((index, key) in keys.withIndex()) {
			val previous = map.put(key, index)

			if (previous != null) {
				throw SomlException("The properties ${descriptor.getElementName(previous)} and ${descriptor.getElementName(index)} of ${descriptor.serialName} both have the key ${describeKey(key)}")
			}

			findUnrepresentable(key)?.let {
				throw SomlException("The key of the property ${descriptor.getElementName(index)} of ${descriptor.serialName} cannot be written: $it")
			}
		}
	}

	/**
	The index of the element with `key`, or -1 when there is none.
	*/
	// Note: Not using keys.indexOf() because repeated linear searches measured slower for larger descriptors.
	fun indexOf(key: String): Int = indexByKey[key] ?: -1

	/**
	Throws when a property has the key of the class discriminator, whose member names the subtype of a polymorphic value, so that the two cannot be told apart.
	*/
	fun checkNotKey(discriminator: String, descriptor: SerialDescriptor, path: List<Any>) {
		if (discriminator in indexByKey) {
			throw SomlException.atPath("The class ${descriptor.serialName} has a property with the key ${describeKey(discriminator)}, which is the class discriminator", path)
		}
	}
}
