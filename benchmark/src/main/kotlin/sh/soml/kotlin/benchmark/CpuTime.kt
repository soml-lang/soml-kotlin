package sh.soml.kotlin.benchmark

import java.lang.management.ManagementFactory
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.tomlj.TomlParseResult
import sh.soml.kotlin.Soml
import sh.soml.kotlin.SomlValue
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.dataformat.toml.TomlMapper
import kotlin.time.Instant

/*
CPU time per document for each library, for the table in the readme. CPU time changes much less with the load on the machine than wall time.

Each library is warmed up for two seconds, which also sizes a batch of about 20 ms. Then 15 rounds run one batch of each library, in an order that rotates each round, and the median of the rounds is reported.

	./gradlew :benchmark:run --args="read write decode encode format"

`--library=SOML` measures one library, as for a profile.
*/

// Every result is stored, so that the JIT compiler cannot discard the work.
@Volatile
private var result: Any? = null

private val fixtures = listOf("service", "platform", "earthquakes")

private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "\t" }
private val jacksonJson = JsonMapper.builder().build()
private val jacksonToml = TomlMapper.builder().build()

private class Fixture(name: String) {
	val soml = Path.of("fixtures/$name.soml").readText()
	val json = Path.of("fixtures/$name.json").readText()
	val toml = Path.of("fixtures/$name.toml").readText()
	val somlTree: SomlValue = Soml.parseToSomlValue(soml)
	val kotlinxTree: JsonElement = Json.parseToJsonElement(json)
	val jacksonJsonTree: JsonNode = jacksonJson.readTree(json)
	val jacksonTomlTree: JsonNode = jacksonToml.readTree(toml)
	val tomljTree: TomlParseResult = org.tomlj.Toml.parse(toml).also { check(!it.hasErrors()) { "tomlj could not read $name.toml: ${it.errors()}" } }
}

/**
The benchmarks of one kind, by library, for one fixture. A library that cannot do the job is left out.
*/
private fun benchmarks(kind: String, name: String, fixture: Fixture): Map<String, () -> Any?> = when (kind) {
	"read" -> mapOf(
		"SOML" to { Soml.parseToSomlValue(fixture.soml) },
		"kotlinx.serialization (JSON)" to { Json.parseToJsonElement(fixture.json) },
		"Jackson (JSON)" to { jacksonJson.readTree(fixture.json) },
		"Jackson (TOML)" to { jacksonToml.readTree(fixture.toml) },
		"tomlj" to { org.tomlj.Toml.parse(fixture.toml) },
	)
	"write" -> mapOf(
		"SOML" to { Soml.encodeToString(SomlValue.serializer(), fixture.somlTree) },
		"kotlinx.serialization (JSON)" to { prettyJson.encodeToString(JsonElement.serializer(), fixture.kotlinxTree) },
		"Jackson (JSON)" to { jacksonJson.writerWithDefaultPrettyPrinter().writeValueAsString(fixture.jacksonJsonTree) },
		"Jackson (TOML)" to { jacksonToml.writeValueAsString(fixture.jacksonTomlTree) },
		"tomlj" to { fixture.tomljTree.toToml() },
	)
	"decode" if name == "earthquakes" -> mapOf(
		"SOML" to { Soml.decodeFromString(FeatureCollection.serializer(), fixture.soml) },
		"kotlinx.serialization (JSON)" to { Json.decodeFromString(FeatureCollection.serializer(), fixture.json) },
	)
	"encode" if name == "earthquakes" -> {
		val value = Soml.decodeFromString(FeatureCollection.serializer(), fixture.soml)

		mapOf(
			"SOML" to { Soml.encodeToString(FeatureCollection.serializer(), value) },
			"kotlinx.serialization (JSON)" to { prettyJson.encodeToString(FeatureCollection.serializer(), value) },
		)
	}
	"format" -> mapOf("SOML" to { Soml.format(fixture.soml) })
	else -> emptyMap()
}

fun main(arguments: Array<String>) {
	val threads = ManagementFactory.getThreadMXBean()
	check(threads.isCurrentThreadCpuTimeSupported) { "This JVM does not measure the CPU time of a thread" }
	threads.isThreadCpuTimeEnabled = true
	val library = arguments.firstOrNull { it.startsWith("--library=") }?.substringAfter('=')
	val kinds = arguments.filterNot { it.startsWith("--") }.ifEmpty { listOf("read", "write", "decode", "encode", "format") }
	checkTypedDecoding()

	for (kind in kinds) {
		for (name in fixtures) {
			val libraries = benchmarks(kind, name, Fixture(name)).filterKeys { library == null || it == library }

			if (libraries.isEmpty()) {
				continue
			}

			val batchSizes = libraries.mapValues { (_, run) ->
				val end = System.nanoTime() + 2_000_000_000L
				val start = threads.currentThreadCpuTime
				var count = 0L

				while (System.nanoTime() < end) {
					result = run()
					count++
				}

				maxOf(1L, 20_000_000L / maxOf(1L, (threads.currentThreadCpuTime - start) / count))
			}

			val times = libraries.mapValues { ArrayList<Double>() }
			val entries = libraries.entries.toList()

			repeat(15) { round ->
				for (position in entries.indices) {
					val (library, run) = entries[(round + position) % entries.size]
					val batchSize = batchSizes.getValue(library)
					val start = threads.currentThreadCpuTime

					for (iteration in 0..<batchSize) {
						result = run()
					}

					times.getValue(library).add((threads.currentThreadCpuTime - start) / 1000.0 / batchSize)
				}
			}

			println("$kind $name: " + times.entries.joinToString("  ") { (library, values) -> "$library ${formatMicroseconds(values.sorted()[values.size / 2])}" })
		}
	}
}

private fun formatMicroseconds(value: Double): String = when {
	value >= 1000 -> "%.2f ms".format(java.util.Locale.ROOT, value / 1000)
	value >= 100 -> "%.0f µs".format(java.util.Locale.ROOT, value)
	else -> "%.1f µs".format(java.util.Locale.ROOT, value)
}

/**
Checks that every library reads the same typed value, so that no benchmark measures an error path or a partial read.
*/
private fun checkTypedDecoding() {
	val fixture = Fixture("earthquakes")
	val soml = Soml.decodeFromString(FeatureCollection.serializer(), fixture.soml)
	check(soml == Json.decodeFromString(FeatureCollection.serializer(), fixture.json)) { "SOML and JSON give different earthquakes" }
	check(soml.features.size == 986)
}

@Serializable
data class FeatureCollection(val type: String, val metadata: Metadata, val bbox: List<Double>, val features: List<Feature>)

@Serializable
data class Metadata(val generated: Instant, val url: String, val title: String, val status: Int, val api: String, val count: Int)

@Serializable
data class Feature(val type: String, val properties: Properties, val geometry: Geometry, val id: String)

@Serializable
data class Geometry(val type: String, val coordinates: List<Double>)

@Serializable
data class Properties(
	val mag: Double,
	val place: String,
	val time: Instant,
	val updated: Instant,
	val felt: Int? = null,
	val cdi: Double? = null,
	val mmi: Double? = null,
	val alert: String? = null,
	val url: String,
	val detail: String,
	val status: String,
	val tsunami: Int,
	val sig: Int,
	val net: String,
	val code: String,
	val ids: String,
	val sources: String,
	val types: String,
	val nst: Int,
	val dmin: Double,
	val rms: Double,
	val gap: Double,
	val magType: String,
	val type: String,
	val title: String,
)
