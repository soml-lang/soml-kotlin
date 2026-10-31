package sh.soml.kotlin

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
Compares this implementation with soml-java, which is a port of the same JavaScript reference, on mutations of every conformance case: whether a document is accepted, the reason, line, and column of an error, the canonical and formatted text of a valid one, and the result of changing it.
*/
class DifferentialTest {
	// Fragments that the mutations insert, chosen to reach the edges of the grammar.
	private val fragments = listOf(
		"\n", " ", "\t", ",", ":", "{", "}", "[", "]", "'", "\"", "\"\"\"\n", "'''\n", "#", "/*", "*/", "\\", "\\u{e9}", "\\n", "_", ".", "-", "+", "e", "0", "1", "9", "0x", "0o", "0b", "h", "m", "s", "ms", "us", "ns",
		"T", "Z", "0001-01-01", "9999-12-31", "1582-10-10", "2000-02-29", "1900-02-29", "2026-11-01foo", "2026-09-19T14:00:00Z", "+02:00", "1h30m", "1.5", "infinity", "true", "null", "é", "😀", " ", "​", "\r", "\u0000", "\"a\\\"b\"", "\"k\\tey\"", "'a.b'", "a: 1\n", "x: [1, 2]\n",
	)

	private fun mutate(text: String, random: Random): String {
		val builder = StringBuilder(text)

		repeat(random.nextInt(1, 4)) {
			val position = if (builder.isEmpty()) 0 else random.nextInt(builder.length + 1)

			when (random.nextInt(4)) {
				0 -> builder.insert(position, fragments.random(random))
				1 -> if (position < builder.length) {
					builder.deleteCharAt(position)
				}
				2 -> if (builder.isNotEmpty()) {
					val start = random.nextInt(builder.length)
					val end = minOf(builder.length, start + random.nextInt(1, 12))
					builder.insert(position.coerceAtMost(builder.length), builder.substring(start, end))
				}
				else -> if (position < builder.length) {
					builder.setCharAt(position, fragments.random(random).first())
				}
			}
		}

		return builder.toString()
	}

	private sealed interface Outcome {
		data class Valid(val canonical: String, val formatted: String, val edits: List<String>) : Outcome
		data class Invalid(val reason: String, val line: Int, val column: Int) : Outcome
	}

	private fun kotlinOutcome(text: String, editPaths: List<List<Any>>): Outcome = try {
		val value = Parser.parse(text)

		Outcome.Valid(
			Writer.writeDocument(value, isCanonical = true),
			Formatter.format(text),
			editPaths.map { path ->
				try {
					val document = SomlDocument.parse(text)
					document.set(*path.toTypedArray(), value = SomlInt(7))
					document.remove(*path.dropLast(1).toTypedArray())
					document.toString()
				} catch (error: SomlException) {
					"error"
				}
			},
		)
	} catch (error: SomlException) {
		Outcome.Invalid(error.reason, error.line, error.column)
	}

	private fun javaOutcome(text: String, editPaths: List<List<Any>>): Outcome = try {
		val value = sh.soml.Soml.parse(text)

		Outcome.Valid(
			sh.soml.Soml.encodeCanonical(value),
			sh.soml.Soml.format(text),
			editPaths.map { path ->
				try {
					val document = sh.soml.SomlDocument.parse(text)
					document.set(path, 7L)
					document.remove(path.dropLast(1))
					document.toString()
				} catch (error: sh.soml.SomlException) {
					"error"
				}
			},
		)
	} catch (error: sh.soml.SomlException) {
		Outcome.Invalid(error.reason(), error.line(), error.column())
	}

	/**
	Paths into the document for the edits: under each of its first members or items, and a new key.
	*/
	private fun editPaths(text: String): List<List<Any>> {
		val value = try {
			Parser.parse(text)
		} catch (_: SomlException) {
			return emptyList()
		}

		return when (value) {
			is SomlObject -> value.keys.take(3).flatMap { listOf(listOf(it, "new"), listOf(it, 0)) } + listOf(listOf("new", "deeper"))
			is SomlArray -> value.indices.take(3).flatMap { listOf(listOf(it, "new"), listOf(it, 0)) }
			else -> emptyList()
		}
	}

	@Test
	fun `agrees with soml-java on mutated conformance cases`() {
		val random = Random(2026)
		val sources = Files.walk(Path.of("src/commonTest/resources/conformance")).use { paths -> paths.filter { it.extension == "soml" }.sorted().toList() }
		val differences = ArrayList<String>()
		var count = 0
		var valid = 0
		var edits = 0

		for (source in sources) {
			val original = String(source.readBytes(), Charsets.UTF_8)

			for (text in listOf(original) + List(12) { mutate(original, random) }) {
				count++
				val paths = editPaths(text)
				val kotlin = kotlinOutcome(text, paths)
				val java = javaOutcome(text, paths)

				if (kotlin is Outcome.Valid) {
					valid++
					edits += kotlin.edits.count { it != "error" }
				}

				if (kotlin != java && differences.size < 20) {
					differences.add("${source.name}: ${Json.quote(text)}\n  Kotlin: $kotlin\n  Java:   $java")
				}
			}
		}

		// Enough of the mutations must stay valid, and enough edits succeed, for the comparison to cover the writer, the formatter, and the editor.
		assertTrue(valid > count / 10 && edits > count / 10, "Only $valid of $count documents are valid, with $edits edits")
		assertTrue(differences.isEmpty(), "Differences in $count documents:\n" + differences.joinToString("\n"))
	}
}

private object Json {
	fun quote(text: String): String = quoteJson(abbreviate(text, 300))
}
