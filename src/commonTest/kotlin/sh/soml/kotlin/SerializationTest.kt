package sh.soml.kotlin

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Serializable
data class Postgres(val host: String, val port: Int)

@Serializable
data class Config(
	val name: String,
	val replicas: Int,
	val timeout: Duration,
	val deployedAt: Instant,
	val labels: List<String>,
	val postgres: Postgres,
)

@Serializable
data class Defaults(val host: String = "localhost", val port: Int = 8080, val note: String? = null)

@Serializable
sealed interface Check

@Serializable
@SerialName("http")
data class HttpCheck(val url: String) : Check

@Serializable
@SerialName("command")
data class CommandCheck(val arguments: List<String>) : Check

@Serializable
data class Service(val name: String, val checks: List<Check>)

@Serializable
enum class Level {
	@SerialName("debug")
	Debug,

	@SerialName("info")
	Info,
}

@Serializable
@JvmInline
value class Port(val number: Int)

@Serializable
data class Numbers(
	val byte: Byte = 0,
	val short: Short = 0,
	val long: Long = 0,
	val float: Float = 0f,
	val double: Double = 0.0,
	val unsigned: UInt = 0u,
	val unsignedLong: ULong = 0u,
	val char: Char = 'a',
)

@Serializable
data class Validated(val port: Int) {
	init {
		require(port > 0) { "The port must be positive" }
	}
}

@Serializable
data class Pool(val maxConnections: Int, val httpURL: String)

@Serializable
data class Dynamic(val name: String, val extra: SomlValue)

class SerializationTest {
	@Test
	fun `decodes a class`() {
		val config = Soml.decodeFromString<Config>(
			"""
			/* The edge service. */
			name: 'api-gateway'
			replicas: 3
			timeout: 1m30s # Including retries.
			deployedAt: 2026-09-19T14:00:00Z
			labels: ['prod', 'eu-west']
			postgres: {host: 'db.internal', port: 5432}
			""".trimIndent(),
		)

		assertEquals(Config("api-gateway", 3, 90.seconds, Instant.parse("2026-09-19T14:00:00Z"), listOf("prod", "eu-west"), Postgres("db.internal", 5432)), config)
	}

	@Test
	fun `encodes a class in property order`() {
		val config = Config("api", 2, 1.hours + 30.minutes, Instant.parse("2026-09-19T14:00:00.5Z"), listOf("a"), Postgres("db", 5432))

		assertEquals(
			"name: 'api'\nreplicas: 2\ntimeout: 1h30m\ndeployedAt: 2026-09-19T14:00:00.5Z\nlabels: [\n\t'a'\n]\npostgres: {\n\thost: 'db'\n\tport: 5432\n}\n",
			Soml.encodeToString(config),
		)
	}

	@Test
	fun `encodes in canonical form`() {
		val soml = Soml { canonical = true }
		assertEquals("host: 'db'\nport: 1\n", soml.encodeToString(Postgres("db", 1)))
		assertEquals("a: 1\nb: 2\n", soml.encodeToString(mapOf("b" to 2, "a" to 1)))
	}

	@Test
	fun `round trips through text and bytes`() {
		val config = Config("x", 1, 5.seconds, Instant.parse("0001-01-01T00:00:00Z"), emptyList(), Postgres("h", 1))
		assertEquals(config, Soml.decodeFromString<Config>(Soml.encodeToString(config)))
		assertEquals(config, Soml.decodeFromByteArray(Config.serializer(), Soml.encodeToByteArray(Config.serializer(), config)))
	}

	@Test
	fun `uses defaults for missing members and leaves them out when encoding`() {
		assertEquals(Defaults(), Soml.decodeFromString<Defaults>("{}"))
		assertEquals("{}\n", Soml.encodeToString(Defaults()))
		assertEquals("host: 'localhost'\nport: 8080\nnote: null\n", Soml { encodeDefaults = true }.encodeToString(Defaults()))
	}

	@Test
	fun `reports a missing member with its path and position`() {
		val error = assertFailsWith<SomlException> {
			Soml.decodeFromString<Config>("name: 'a'\nreplicas: 1\ntimeout: 1s\ndeployedAt: 2026-01-01T00:00:00Z\nlabels: []\npostgres: {host: 'h'}")
		}

		assertEquals("Missing the member port", error.reason)
		assertEquals(listOf("postgres"), error.path)
		assertEquals(6, error.line)
		assertEquals(11, error.column)
	}

	@Test
	fun `reports a type mismatch with its path line and column`() {
		val error = assertFailsWith<SomlException> {
			Soml.decodeFromString<Postgres>("host: 'a'\nport: 'eighty'")
		}

		assertEquals("Expected an int, but found the string 'eighty'", error.reason)
		assertEquals(listOf("port"), error.path)
		assertEquals(2, error.line)
		assertEquals(7, error.column)
		assertEquals("port: Expected an int, but found the string 'eighty' at line 2, column 7\n\n  1 | host: 'a'\n> 2 | port: 'eighty'\n    |       ^", error.message)
	}

	@Test
	fun `rejects unknown keys with a suggestion unless they are ignored`() {
		val error = assertFailsWith<SomlException> {
			Soml.decodeFromString<Postgres>("host: 'a'\nprot: 1\nport: 2")
		}

		assertEquals("Unknown key prot for Postgres. Did you mean port?", error.reason)
		assertEquals(2, error.line)
		assertEquals(1, error.column)
		assertEquals(Postgres("a", 2), Soml { ignoreUnknownKeys = true }.decodeFromString<Postgres>("host: 'a'\nprot: 1\nport: 2"))
	}

	@Test
	fun `decodes and encodes sealed types with a discriminator`() {
		val service = Service("api", listOf(HttpCheck("https://example.com"), CommandCheck(listOf("pg_isready"))))
		val text = Soml.encodeToString(service)
		assertTrue(text.contains("{\n\t\ttype: 'http'\n\t\turl: 'https://example.com'\n\t}"), text)
		assertEquals(service, Soml.decodeFromString<Service>(text))

		val error = assertFailsWith<SomlException> {
			Soml.decodeFromString<Service>("name: 'a'\nchecks: [{type: 'ftp'}]")
		}

		assertEquals(listOf("checks", 0, "type"), error.path)
		assertEquals(2, error.line)
		assertEquals(17, error.column)
	}

	@Test
	fun `uses a custom class discriminator`() {
		val soml = Soml { classDiscriminator = "kind" }
		val text = soml.encodeToString(Service("a", listOf(HttpCheck("u"))))
		assertTrue(text.contains("kind: 'http'"), text)
		assertEquals(Service("a", listOf(HttpCheck("u"))), soml.decodeFromString<Service>(text))
	}

	@Test
	fun `decodes enums value classes and maps with int keys`() {
		assertEquals(mapOf(404 to "Not found", 500 to "Error"), Soml.decodeFromString<Map<Int, String>>("404: 'Not found'\n500: 'Error'"))
		assertEquals(mapOf(Level.Debug to Port(1)), Soml.decodeFromString<Map<Level, Port>>("debug: 1"))
		assertEquals("debug: 1\n", Soml.encodeToString(mapOf(Level.Debug to Port(1))))

		val error = assertFailsWith<SomlException> {
			Soml.decodeFromString<Map<Int, String>>("007: 'Bond'")
		}

		assertEquals(1, error.line)
		assertEquals(1, error.column)
	}

	@Test
	fun `checks number ranges`() {
		assertEquals(Numbers(byte = -128, short = 300, long = Long.MIN_VALUE, float = 1.5f, double = 3.0, unsigned = 4_000_000_000u, unsignedLong = 1u, char = 'z'), Soml.decodeFromString<Numbers>("byte: -128\nshort: 300\nlong: -9223372036854775808\nfloat: 1.5\ndouble: 3\nunsigned: 4000000000\nunsignedLong: 1\nchar: 'z'"))

		for (document in listOf("byte: 128", "unsigned: -1", "unsigned: 4294967296", "double: 'x'", "long: 1.0", "float: 1e300", "char: 'ab'", "double: 9007199254740993")) {
			assertFailsWith<SomlException>(document) {
				Soml.decodeFromString<Numbers>(document)
			}
		}

		assertFailsWith<SomlException> {
			Soml.encodeToString(Numbers(unsignedLong = ULong.MAX_VALUE))
		}

		assertEquals("unsigned: 4000000000\n", Soml.encodeToString(Numbers(unsigned = 4_000_000_000u)).lines().first { it.startsWith("unsigned:") } + "\n")
	}

	@Test
	fun `refuses values that SOML cannot represent`() {
		val nan = assertFailsWith<SomlException> {
			Soml.encodeToString(Numbers(double = Double.NaN))
		}

		assertEquals(listOf("double"), nan.path)

		assertFailsWith<SomlException> {
			Soml.encodeToString(Postgres("a\rb", 1))
		}

		assertFailsWith<SomlException> {
			Soml.encodeToString(Postgres(charArrayOf(0xD800.toChar()).concatToString(), 1))
		}

		assertFailsWith<SomlException> {
			Soml.encodeToString(mapOf("time" to Duration.INFINITE))
		}

		assertFailsWith<SomlException> {
			Soml.encodeToString(5)
		}
	}

	@Test
	fun `reports the error of a class's own validation at the value`() {
		val error = assertFailsWith<SomlException> {
			Soml.decodeFromString<List<Validated>>("[{port: 1}, {port: 0}]")
		}

		assertEquals("The port must be positive", error.reason)
		assertEquals(listOf(1), error.path)
		assertEquals(13, error.column)
	}

	@Test
	fun `applies a naming strategy`() {
		val soml = Soml { namingStrategy = SomlNamingStrategy.KebabCase }
		assertEquals("max-connections: 16\nhttp-url: 'u'\n", soml.encodeToString(Pool(16, "u")))
		assertEquals(Pool(16, "u"), soml.decodeFromString<Pool>("max-connections: 16\nhttp-url: 'u'"))
		assertEquals("max_connections: 16\nhttp_url: 'u'\n", Soml { namingStrategy = SomlNamingStrategy.SnakeCase }.encodeToString(Pool(16, "u")))
	}

	@Test
	fun `leaves out nulls when they are not explicit`() {
		val soml = Soml { explicitNulls = false }

		@Serializable
		data class Optional(val value: String?)

		assertEquals("{}\n", soml.encodeToString(Optional(null)))
		assertEquals(Optional(null), soml.decodeFromString<Optional>("{}"))
		// As in `Json`, the setting is about properties, so a map keeps its nulls.
		assertEquals("a: null\n", soml.encodeToString(mapOf<String, Int?>("a" to null)))

		assertFailsWith<SomlException> {
			Soml.decodeFromString<Optional>("{}")
		}
	}

	@Test
	fun `holds values of any shape`() {
		val dynamic = Soml.decodeFromString<Dynamic>("name: 'a'\nextra: {b: [1, 2.0]}")
		assertEquals(SomlObject("b" to SomlArray(SomlInt(1), SomlFloat(2.0))), dynamic.extra)
		assertEquals("name: 'a'\nextra: {\n\tb: [\n\t\t1\n\t\t2.0\n\t]\n}\n", Soml.encodeToString(dynamic))
		assertEquals(Soml.parseToSomlValue("a: 1"), Soml.decodeFromString<SomlObject>("a: 1"))
	}

	@Test
	fun `converts to and from values`() {
		assertEquals(SomlObject("host" to SomlString("a"), "port" to SomlInt(1)), Soml.encodeToSomlValue(Postgres("a", 1)))
		assertEquals(Postgres("a", 1), Soml.decodeFromSomlValue<Postgres>(SomlObject("host" to SomlString("a"), "port" to SomlInt(1))))
		assertEquals(SomlInt(5), Soml.encodeToSomlValue(5))
		assertEquals(listOf(1, 2), Soml.decodeFromSomlValue(ListSerializer(Int.serializer()), SomlArray(SomlInt(1), SomlInt(2))))
	}

	@Test
	fun `reports a syntax error with its position`() {
		val error = assertFailsWith<SomlException> {
			Soml.decodeFromString<Postgres>("host: 'a'\nport: 08")
		}

		assertEquals(2, error.line)
		assertTrue(error.path.isEmpty())
	}

	@Test
	fun `refuses invalid UTF-8`() {
		val error = assertFailsWith<SomlException> {
			Soml.decodeFromByteArray(Postgres.serializer(), byteArrayOf('a'.code.toByte(), ':'.code.toByte(), ' '.code.toByte(), 0xFF.toByte()))
		}

		assertEquals("Invalid UTF-8 byte 0xFF", error.reason)
	}

	@Test
	fun `refuses two properties with the same key`() {
		@Serializable
		data class Clash(val maxValue: Int, val max_value: Int)

		assertFailsWith<SomlException> {
			Soml { namingStrategy = SomlNamingStrategy.SnakeCase }.encodeToString(Clash(1, 2))
		}
	}

	@Test
	fun `returns null for a null document member`() {
		assertNull(Soml.decodeFromString<Defaults>("note: null").note)
	}
}
