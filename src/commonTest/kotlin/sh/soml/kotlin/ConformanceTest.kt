package sh.soml.kotlin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
The language-neutral conformance suite of the spec, in `src/commonTest/resources/conformance`, on every platform. Each case is read as UTF-8 bytes, as a file is read.
*/
class ConformanceTest {
	private fun parseBytes(file: CorpusFile): SomlValue = Parser.parse(Parser.decodeUtf8(file.readBytes()))

	@Test
	fun valid() = checkEach(corpusFiles("valid"), { it.name }) { file ->
		val value = parseBytes(file)
		val expected = Json.parseToJsonElement(file.sibling(".json").readText())
		assertEquals(expected, value.toTagged(), "value")

		val canonical = file.sibling(".canonical.soml").readText()
		assertEquals(canonical, Writer.writeDocument(value, isCanonical = true), "canonical form")
		assertEquals(value, Parser.parse(canonical), "value of the canonical form")
	}

	@Test
	fun formatted() = checkEach(corpusFiles("valid"), { it.name }) { file ->
		val formatted = file.sibling(".formatted.soml").readText()
		assertEquals(formatted, Formatter.format(Parser.decodeUtf8(file.readBytes())), "formatted form")
		assertEquals(formatted, Formatter.format(formatted), "formatting the formatted form again")
	}

	@Test
	fun edit() = checkEach(corpusFiles("edit", "json"), { it.name }) { file ->
		val case = Json.parseToJsonElement(file.readText()).jsonObject
		val document = SomlDocument.parse(case.getValue("document").jsonPrimitive.content)
		val elements = case.getValue("path").jsonArray.map<JsonElement, Any> { element -> element.jsonPrimitive.intOrNull ?: element.jsonPrimitive.content }.toTypedArray()
		val value = case["value"]?.fromTagged()

		val change = {
			if (value == null) {
				document.remove(*elements)
			} else {
				document.set(*elements, value = value)
			}
		}

		if (case["error"]?.jsonPrimitive?.boolean == true) {
			assertFailsWith<SomlException> {
				change()
			}
		} else {
			change()
			assertEquals(case.getValue("expected").jsonPrimitive.content, document.toString())
			assertEquals(Parser.parse(document.toString()), document.value, "value after the edit")
		}
	}

	@Test
	fun invalid() {
		val reasons = Json.parseToJsonElement(corpusFile("invalid-reasons.json").readText()).jsonObject

		checkEach(corpusFiles("invalid"), { it.name }) { file ->
			val error = assertFailsWith<SomlException> {
				parseBytes(file)
			}

			val expected = reasons.getValue(file.name.removePrefix("invalid/")).jsonObject
			assertEquals(expected.getValue("reason").jsonPrimitive.content, error.reason, "reason")
			assertEquals(expected.getValue("line").jsonPrimitive.int, error.line, "line")
			assertEquals(expected.getValue("column").jsonPrimitive.int, error.column, "column")
		}
	}
}

/**
A value from the tagged JSON of the conformance suite.
*/
private fun JsonElement.fromTagged(): SomlValue = when (this) {
	is JsonArray -> SomlArray(map { it.fromTagged() })
	is JsonObject if this["type"] is JsonPrimitive && this["value"] is JsonPrimitive && size == 2 -> {
		val content = getValue("value").jsonPrimitive.content

		when (val type = getValue("type").jsonPrimitive.content) {
			"string" -> SomlString(content)
			"int" -> SomlInt(content.toLong())
			"float" -> SomlFloat(
				when (content) {
					"infinity" -> Double.POSITIVE_INFINITY
					"-infinity" -> Double.NEGATIVE_INFINITY
					else -> content.toDouble()
				},
			)
			"bool" -> SomlBool.of(content.toBooleanStrict())
			"null" -> SomlNull
			"date" -> SomlDate(content.substring(0, 4).toInt(), content.substring(5, 7).toInt(), content.substring(8, 10).toInt())
			"instant" -> Parser.parse("a: $content").let { (it as SomlObject).getValue("a") }
			"duration" -> SomlDuration(content.toLong())
			else -> error("Unknown type $type")
		}
	}
	is JsonObject -> SomlObject(mapValues { it.value.fromTagged() })
	else -> error("Unexpected $this")
}

/**
A value as the tagged JSON of the conformance suite, as in toml-test.
*/
private fun SomlValue.toTagged(): JsonElement {
	fun tagged(type: String, value: String) = JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

	return when (this) {
		is SomlString -> tagged("string", value)
		is SomlInt -> tagged("int", value.toString())
		is SomlFloat -> tagged("float", toString())
		is SomlBool -> tagged("bool", value.toString())
		SomlNull -> tagged("null", "null")
		is SomlDate -> tagged("date", toString())
		is SomlInstant -> tagged("instant", toString())
		is SomlDuration -> tagged("duration", nanoseconds.toString())
		is SomlArray -> JsonArray(map { it.toTagged() })
		is SomlObject -> JsonObject(mapValues { it.value.toTagged() })
	}
}
