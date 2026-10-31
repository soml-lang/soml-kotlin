package sh.soml.kotlin

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Instant

/**
The examples of the readme, with the results it shows.
*/
class ReadmeTest {
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

	@Serializable
	data class Server(val port: Int, val hosts: List<String>)

	@Serializable
	data class Pool(val size: Int) {
		init {
			require(size > 0) { "The size must be positive" }
		}
	}

	@Serializable
	sealed interface Check

	@Serializable
	@SerialName("http")
	data class Http(val url: String) : Check

	@Serializable
	@SerialName("command")
	data class Command(val arguments: List<String>) : Check

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

	@Test
	fun read() {
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

		assertEquals(SomlDate(2027, 9, 19), config.launchDate)
		assertEquals(5432, config.postgres.port)
		assertEquals("1m 30s", config.timeout.toString())
	}

	@Test
	fun write() {
		assertEquals("port: 8080\nhosts: [\n\t'a'\n\t'b'\n]\n", Soml.encodeToString(Server(8080, listOf("a", "b"))))
		assertEquals("a: 2\nb: 1\n", Soml { canonical = true }.encodeToString(mapOf("b" to 1, "a" to 2)))
	}

	@Test
	fun `values of unknown shape`() {
		val document = Soml.parseToSomlValue("port: 8080\nhosts: ['a', 'b']") as SomlObject
		assertEquals(8080L, (document["port"] as? SomlInt)?.value)

		val hostCount = when (val hosts = document["hosts"]) {
			is SomlArray -> hosts.size
			is SomlString -> 1
			else -> 0
		}

		assertEquals(2, hostCount)
	}

	@Test
	fun `change a document`() {
		val document = SomlDocument.parse("# The edge service.\nport: 0x1F90 # The default.\nserver: {host: 'a'}\n")

		document.set("port", value = 8080)
		document.set("server", "port", value = 443)
		document.set("replicas", value = 3)
		document.remove("server", "host")

		assertEquals("# The edge service.\nport: 8080 # The default.\nserver: {port: 443}\nreplicas: 3\n", document.toString())
		assertEquals(SomlInt(443), document["server", "port"])
	}

	@Test
	fun format() {
		assertEquals("pool: {min: 2, max: 16} # Connections\n", Soml.format("pool: {min: 2,   max: 16,} # Connections"))
		assertEquals("pool: {\n\tmin: 2\n\tmax: 16\n}\n", Soml.format("pool: {\nmin: 2, max: 16}"))
	}

	@Test
	fun errors() {
		val error = assertFailsWith<SomlException> {
			Soml.decodeFromString<Postgres>("host: 'a'\nport: 'eighty'")
		}

		assertEquals("port: Expected an int, but found the string 'eighty' at line 2, column 7\n\n  1 | host: 'a'\n> 2 | port: 'eighty'\n    |       ^", error.message)
		assertEquals("Expected an int, but found the string 'eighty'", error.reason)
		assertEquals(listOf("port"), error.path)

		val validation = assertFailsWith<SomlException> {
			Soml.decodeFromString<Pool>("size: 0")
		}

		assertEquals("The size must be positive at line 1, column 1\n\n> 1 | size: 0\n    | ^", validation.message)
	}

	@Test
	fun `sealed types`() {
		val checks = Soml.decodeFromString<List<Check>>(
			"""
			[
				{type: 'http', url: 'https://example.com/health'}
				{type: 'command', arguments: ['pg_isready']}
			]
			""",
		)

		assertEquals(listOf(Http("https://example.com/health"), Command(listOf("pg_isready"))), checks)
	}

	@Test
	fun `custom types`() {
		assertEquals(Theme(Color("#FF8800")), Soml.decodeFromString<Theme>("accent: '#FF8800'"))

		val error = assertFailsWith<SomlException> {
			Soml.decodeFromString<Theme>("accent: 'red'")
		}

		assertEquals("accent: Expected a color, such as '#FF8800' at line 1, column 9\n\n> 1 | accent: 'red'\n    |         ^", error.message)
	}
}
