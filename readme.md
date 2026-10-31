# SOML for Kotlin

> Read and write [SOML](https://soml.sh), a config format for humans, in Kotlin

> [!NOTE]
> The format is a draft.

## Highlights

- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) format: `Soml.decodeFromString<Config>(text)`, as with `Json`
- Strict: every rule in the spec is checked, such as duplicate keys, the int64, date, instant, and duration ranges, raw control characters, and the nesting limit
- Errors have a line, a column, a path, and a code frame, with the same position and message as the JavaScript reference implementation
- Exact: an int is a `Long` and a float is a `Double`, so `3` and `3.0` keep their types, instants keep their nanoseconds, and durations are exact to the nanosecond across the whole range
- Calendar dates with exact year, month, and day components, without a timezone
- Native `java.time.LocalDate`, `kotlin.time.Duration`, `kotlin.time.Instant`, `java.time.Duration`, and `java.time.Instant`
- Writes members in the order of your properties, or in canonical form, where the same value always gives the same bytes
- A value tree for documents of unknown shape, made for `when`
- A formatter that changes layout and nothing else, by the normative rules of the spec
- Change one value in a file and keep its comments, order, and spelling
- Passes the language-neutral [conformance suite](https://github.com/soml-lang/soml/tree/main/conformance) of the spec, including its formatter and editing cases

## Install

Requires Kotlin 2.4. Supports Android 13 (API 33) or later, JVM (Java 21 or later), JavaScript, Wasm, macOS arm64, iOS arm64 and its arm64 simulator, Linux x64 and arm64, and Windows x64.

```kotlin
plugins {
	kotlin("plugin.serialization") version "2.4.21"
}

dependencies {
	implementation("sh.soml:soml-kotlin:0.0.0")
}
```

Android uses the same dependency coordinates. Gradle selects the Android artifact, which targets Java 8 bytecode and uses the shared exact float formatter, so canonical output does not depend on Android's `Double.toString()`. `java.time` support and stream functions are available on Android too. No core-library desugaring is needed at the minimum API level of 33.

## Usage

### Read

```kotlin
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import sh.soml.kotlin.Soml
import sh.soml.kotlin.SomlDate
import sh.soml.kotlin.SomlNamingStrategy
import kotlin.time.Duration
import kotlin.time.Instant

@Serializable
data class Config(
	val name: String,
	val replicas: Int,
	val timeout: Duration,
	val deployedAt: Instant,
	val launchDate: SomlDate,
	val labels: List<String>,
	val postgres: Postgres,
)

@Serializable
data class Postgres(val host: String, val port: Int)

val soml = Soml {
	namingStrategy = SomlNamingStrategy.KebabCase
}

val config = soml.decodeFromString<Config>(
	"""
	/* The edge service. */
	name: 'api-gateway'
	replicas: 3
	timeout: 1m30s # Including retries.
	deployed-at: 2026-09-19T14:00:00Z
	launch-date: 2027-09-19
	labels: ['prod', 'eu-west']
	postgres: {host: 'db.internal', port: 5432}
	""",
)

config.postgres.port
//=> 5432

config.timeout
//=> 1m 30s
```

Indentation never matters in SOML, so a document can be indented like the code around it.

`Soml` is the default instance, where a key is the Kotlin name, as in `deployedAt`. `SomlNamingStrategy.KebabCase` gives the usual SOML style, `deployed-at`. `@SerialName` sets the serial name, which a naming strategy also transforms. `decodeFromByteArray()` reads UTF-8 bytes, which is how to read a file on the JVM: `soml.decodeFromByteArray<Config>(Path("config.soml").readBytes())`. On the JVM and Android, `decodeFromStream()` reads an `InputStream` to its end, without closing it. Bytes that are not well-formed UTF-8 are an error, never replaced, which is why a file is best read as bytes rather than as text.

A member that the document leaves out takes the default value of its property, and a property without a default is an error. A key that no property has is an error too, which catches a typo such as `prot: 80`, with a suggestion of the key that was most likely meant. Use `ignoreUnknownKeys = true` to allow other keys.

### Write

```kotlin
@Serializable
data class Server(val port: Int, val hosts: List<String>)

Soml.encodeToString(Server(8080, listOf("a", "b")))
//=> "port: 8080\nhosts: [\n\t'a'\n\t'b'\n]\n"

Soml { canonical = true }.encodeToString(mapOf("b" to 1, "a" to 2))
//=> "a: 2\nb: 1\n"
```

`encodeToString()` writes one member or item per line, with tab indentation and no commas. Members keep the order of your properties, and of a map's iteration.

`encodeToByteArray()` gives UTF-8 bytes, and `encodeToStream()` writes them to an `OutputStream`, without flushing or closing it.

With `canonical = true`, it writes canonical form, which is the same with members sorted by key, so the same value always gives the same bytes. Use it to hash, sign, or compare documents, and to write a `HashMap`, which iterates in no particular order.

As in `Json`, a property whose value is its default is left out unless `encodeDefaults = true`, and a `null` is written as `null` unless `explicitNulls = false`.

### Values of unknown shape

```kotlin
import sh.soml.kotlin.*

val document = Soml.parseToSomlValue("port: 8080\nhosts: ['a', 'b']") as SomlObject

val port = (document["port"] as? SomlInt)?.value
//=> 8080

val hostCount = when (val hosts = document["hosts"]) {
	is SomlArray -> hosts.size
	is SomlString -> 1
	else -> 0
}
//=> 2
```

`SomlValue` is a sealed interface with one type per SOML type: `SomlString`, `SomlInt`, `SomlFloat`, `SomlBool`, `SomlNull`, `SomlDate`, `SomlInstant`, `SomlDuration`, `SomlArray` (a `List<SomlValue>`), and `SomlObject` (a `Map<String, SomlValue>` in document order). A document is always a `SomlObject` or a `SomlArray`.

Equality follows the spec: an int and a float are never equal, so `3` and `3.0` differ, `-0.0` is `0.0`, the order of an object's members does not matter, dates compare their year, month, and day, and two instants are equal when they are the same point in time. `toString()` gives the canonical spelling, such as `'text'`, `1.5`, or `1h30m`. A value is valid by construction, so `SomlString("\r")` and `SomlFloat(Double.NaN)` throw.

A property of type `SomlValue`, or one of its types, holds any value, and `encodeToSomlValue()` and `decodeFromSomlValue()` convert between a tree and your types.

### Change a document and keep its comments

```kotlin
val document = SomlDocument.parse("# The edge service.\nport: 0x1F90 # The default.\nserver: {host: 'a'}\n")

document.set("port", value = 8080)
document.set("server", "port", value = 443)
document.set("replicas", value = 3)
document.remove("server", "host")

document.toString()
//=> "# The edge service.\nport: 8080 # The default.\nserver: {port: 443}\nreplicas: 3\n"
```

An unchanged document prints the exact text it was parsed from. A changed one keeps the comments, blank lines, indentation, member order, and the spelling of every value outside the change. A path is the keys (`String`) and array indexes (`Int`) from the top. Changes follow the editing rules of the spec, as in the JavaScript reference, so a change to a formatted document leaves it formatted:

- A new value is written as `encodeToString()` writes it, and on one line inside a container that is on one line, so `{host: 'a'}` becomes `{host: 'a', port: 443}`.
- A new member goes after the last member of its object, and missing objects on the path are created. A new item goes at the end of its array, at the index that is its length.
- A removed member or item takes its comma and the comments it owns, such as one after it on its line. A comment on a line of its own stays.
- Removing a path that does not exist is not an error, and `remove()` returns whether it removed something. A path that does not fit the document, such as a key where an array is, is an error.

`document["server", "port"]` reads the value at a path, and `document.value` gives the whole document as a `SomlValue`.

### Format

```kotlin
Soml.format("pool: {min: 2,   max: 16,} # Connections")
//=> "pool: {min: 2, max: 16} # Connections\n"

Soml.format("pool: {\nmin: 2, max: 16}")
//=> "pool: {\n\tmin: 2\n\tmax: 16\n}\n"
```

The formatter changes layout and nothing else: one tab of indentation per level, every member and item on its own line, no commas, at most one blank line in a row, and no trailing spaces or tabs. An object or an array whose brackets are on one line stays on one line, with a comma and a space between its members or items. To give it one member or item per line, put a line break anywhere inside it. Comments stay where they are, and so do member order, block strings, and the spelling of every value, so `0o644` stays `0o644`. Formatting a formatted document gives the same text. The formatter rules of the spec are normative, so every conforming formatter, including this one, gives the same text.

### Errors

```kotlin
try {
	Soml.decodeFromString<Postgres>("host: 'a'\nport: 'eighty'")
} catch (error: SomlException) {
	error.message
	//=> "port: Expected an int, but found the string 'eighty' at line 2, column 7\n\n  1 | host: 'a'\n> 2 | port: 'eighty'\n    |       ^"

	error.reason
	//=> "Expected an int, but found the string 'eighty'"

	error.path
	//=> ["port"]
}
```

Every function throws `SomlException` for a document that is not valid or does not fit, and for a value that cannot be written. It is a `SerializationException`, like the errors of every kotlinx.serialization format. It has the `reason`, the `line` and `column` (in Unicode scalar values), the `path` of keys and indexes to the value, and a `codeFrame` for a terminal. An encoding error has a path and no position. A syntax error gives what was most likely meant, such as quoting a string, writing a duration without a space, or nesting with braces rather than a `.` in a key.

An `IllegalArgumentException` that your class throws while it is decoded, such as from a `require()` in its `init` block, is reported as a `SomlException` at the position of the object, with the exception as its cause:

```kotlin
@Serializable
data class Pool(val size: Int) {
	init {
		require(size > 0) { "The size must be positive" }
	}
}

Soml.decodeFromString<Pool>("size: 0")
//=> SomlException: "The size must be positive at line 1, column 1 …"
```

## Types

| SOML | Kotlin | Notes |
|---|---|---|
| string | `String`, `Char`, an enum, `Uuid` | An enum constant is its name, or its `@SerialName`. |
| int | `Long`, `Int`, `Short`, `Byte`, `ULong`, `UInt`, `UShort`, `UByte` | A value that does not fit the type is an error. A `Double` takes an int too, when it is exact, and a `Float` takes the nearest `Float` to it. |
| float | `Double`, `Float` | NaN is an error. A float never goes into an integer type. A `Float` is written as the exact value of its `Double`, such as `0.10000000149011612` for `0.1f`. |
| bool | `Boolean` | |
| null | `null` | |
| date | `SomlDate`, `java.time.LocalDate` | Exact proleptic Gregorian components, years 0001 to 9999. A `java.time.LocalDate` property needs `@Contextual`. |
| instant | `kotlin.time.Instant`, `java.time.Instant` | Read in UTC, because the offset is not part of the value. A `java.time.Instant` property needs `@Contextual`. |
| duration | `kotlin.time.Duration`, `java.time.Duration` | A `java.time.Duration` property needs `@Contextual`. |
| array | `List`, `Set`, `Array`, and other collections | |
| object | a `@Serializable` class, `Map` | A map key is a `String`, a `Char`, an enum, a `Boolean`, or an integer type written in decimal, as in `404: 'Not found'`. |
| any value | `SomlValue` or one of its types | |

A value class is its value. A sealed class or interface is an object whose `type` member names the subtype, which is its `@SerialName` or its full class name:

```kotlin
@Serializable
sealed interface Check

@Serializable
@SerialName("http")
data class Http(val url: String) : Check

@Serializable
@SerialName("command")
data class Command(val arguments: List<String>) : Check

Soml.decodeFromString<List<Check>>(
	"""
	[
		{type: 'http', url: 'https://example.com/health'}
		{type: 'command', arguments: ['pg_isready']}
	]
	""",
)
```

Use `classDiscriminator` to name the member something other than `type`.

A date is distinct from a string or an instant. `SomlDate(2027, 9, 19)` validates its components without a timezone and writes `2027-09-19`; quoted date text stays a string. On the JVM and Android, `SomlDate(localDate)` and `date.toJavaLocalDate()` convert the components directly. `JavaDateSerializer` supplies the native `LocalDate` binding and uses `YYYY-MM-DD` strings in JSON, with the same year limits. An explicit contextual serializer can override that binding.

### Custom types

A custom serializer works as with any format, and a `SomlException` or an `IllegalArgumentException` that it throws gets the path and the position of the value:

```kotlin
data class Color(val hex: String)

object ColorSerializer : KSerializer<Color> {
	override val descriptor = PrimitiveSerialDescriptor("Color", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: Color) {
		encoder.encodeString(value.hex)
	}

	override fun deserialize(decoder: Decoder): Color {
		val hex = decoder.decodeString()
		require(Regex("#[0-9A-F]{6}").matches(hex)) { "Expected a color, such as '#FF8800'" }
		return Color(hex)
	}
}

@Serializable
data class Theme(@Serializable(with = ColorSerializer::class) val accent: Color)
```

```kotlin
Soml.decodeFromString<Theme>("accent: 'red'")
//=> SomlException: "accent: Expected a color, such as '#FF8800' at line 1, column 9 …"
```

A serializer can also read and write a `SomlValue` directly, through `SomlDecoder.decodeSomlValue()` and `SomlEncoder.encodeSomlValue()`, as `JsonDecoder` and `JsonEncoder` do.

## File format

Documents follow the [SOML specification](https://soml.sh).

- File extension: `.soml`
- Encoding: UTF-8 without a byte order mark, with LF line endings
- Media type: `application/soml`
- Nesting is limited to 100 levels, as the spec requires. Every object and array counts, including the document's own.

## Performance

The CPU time per document on an Apple M4 Pro with JDK 27, against the most used JSON and TOML libraries for the JVM. Each cell is the median of three runs, and each run is the median of 15 rounds. The [fixtures](https://github.com/soml-lang/soml/tree/main/benchmark) are shared by every SOML implementation and have the same data in each format. *Read* parses a document into each library's tree of generic values, and *write* writes that tree back as a document for people, with JSON pretty-printed. *Decode* and *encode* read and write the earthquakes as `@Serializable` classes.

CPU time reduces interference from other work, but timings still vary with host load and JVM optimization. Compare results on the same machine and runtime.

| | SOML | kotlinx.serialization 1.12 (JSON) | Jackson 3.2 (JSON) | Jackson 3.2 (TOML) | tomlj 2.2 |
|---|---|---|---|---|---|
| Read service | 6.4 µs | 6.1 µs | 6.2 µs | 22.2 µs | 149 µs |
| Read platform | 47.3 µs | 36.7 µs | 41.3 µs | 227 µs | 1.39 ms |
| Read earthquakes | 1.22 ms | 1.20 ms | 1.51 ms | 4.80 ms | 33.61 ms |
| Write service | 5.4 µs | 6.1 µs | 3.5 µs | 4.0 µs | 10.2 µs |
| Write platform | 33.9 µs | 38.1 µs | 24.8 µs | 28.1 µs | 98.7 µs |
| Write earthquakes | 1.57 ms | 1.96 ms | 1.57 ms | 1.10 ms | 3.18 ms |
| Decode earthquakes | 1.74 ms | 1.60 ms |  |  |  |
| Encode earthquakes | 1.96 ms | 1.46 ms |  |  |  |
| Format service | 15.5 µs |  |  |  |  |
| Format platform | 120 µs |  |  |  |  |
| Format earthquakes | 3.19 ms |  |  |  |  |

SOML reads and writes at about the speed of the JSON libraries, while it also checks every rule of the spec, such as duplicate keys, ranges, and the nesting limit. The platform fixture is 35 KB as SOML and TOML, and 21 KB as JSON, which has no comments.

```sh
./gradlew :benchmark:run
```

## Limitations

- Android versions before 13 (API 33) are not supported. Android and native targets share exact float formatting; desktop JVM targets keep their JDK fast paths.
- A `Float` property is written as the exact value of its `Double`, such as `0.10000000149011612` for `0.1f`, because a SOML float is a binary64 value. Reading one rounds the decimal to a `Double` and then to a `Float`, which in rare cases differs by one unit in the last place from rounding the decimal to a `Float` directly. Use `Double`.
- `encodeToString()` writes a `HashMap` or a `HashSet` in iteration order, which can change between runs. Use a `LinkedHashMap`, or canonical form, which sorts map members but not set items.
- `toString()`, `equals()`, and `hashCode()` of a `SomlValue` tree built in code thousands of levels deep overflow the stack. A parsed tree is at most 100 levels deep.
- For untrusted documents, limit the size of the text before reading it. A document takes several times its size in memory while it is read.
- A `SomlDocument` is not safe to change from several threads at once.
