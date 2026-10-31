package sh.soml.kotlin

import kotlinx.serialization.KSerializer
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class SerializationHardeningTest {
	@Test
	@OptIn(ExperimentalSerializationApi::class)
	fun `contextual time serializers keep their registered string representation`() {
		val instantSerializer = object : KSerializer<Instant> {
			override val descriptor = PrimitiveSerialDescriptor("ContextualInstantOverride", PrimitiveKind.STRING)
			override fun serialize(encoder: Encoder, value: Instant) {
				encoder.encodeString("seconds:${value.epochSeconds}")
			}
			override fun deserialize(decoder: Decoder): Instant = Instant.fromEpochSeconds(decoder.decodeString().substringAfter(':').toLong())
		}
		val durationSerializer = object : KSerializer<Duration> {
			override val descriptor = PrimitiveSerialDescriptor("ContextualDurationOverride", PrimitiveKind.STRING)
			override fun serialize(encoder: Encoder, value: Duration) {
				encoder.encodeString("millis:${value.inWholeMilliseconds}")
			}
			override fun deserialize(decoder: Decoder): Duration = decoder.decodeString().substringAfter(':').toLong().milliseconds
		}
		val soml = Soml {
			serializersModule = SerializersModule {
				contextual(Instant::class, instantSerializer)
				contextual(Duration::class, durationSerializer)
			}
		}
		val instantMap = MapSerializer(String.serializer(), ContextualSerializer(Instant::class))
		val durationMap = MapSerializer(String.serializer(), ContextualSerializer(Duration::class))
		val instants = mapOf("instant" to Instant.fromEpochSeconds(42))
		val durations = mapOf("duration" to 1500.milliseconds)
		assertEquals("instant: 'seconds:42'\n", soml.encodeToString(instantMap, instants))
		assertEquals(instants, soml.decodeFromString(instantMap, "instant: 'seconds:42'"))
		assertEquals("duration: 'millis:1500'\n", soml.encodeToString(durationMap, durations))
		assertEquals(durations, soml.decodeFromString(durationMap, "duration: 'millis:1500'"))
	}

	@Test
	fun `float properties round integers that are not exact doubles`() {
		for (number in listOf(9_007_199_254_740_993L, Long.MAX_VALUE, Long.MIN_VALUE, (1L shl 62) + (1L shl 38) + 1)) {
			assertEquals(number.toFloat(), Soml.decodeFromString<List<Float>>("[$number]").single())
		}
	}

	@Test
	fun `typed encoding stops at the document depth limit`() {
		var node = RecursiveNode()

		repeat(MAX_DEPTH) {
			node = RecursiveNode(node)
		}

		val error = assertFailsWith<SomlException> {
			Soml.encodeToSomlValue(node)
		}

		assertEquals(MAX_DEPTH, error.path.size)
	}

	@Test
	fun `typed decoding stops at the document depth limit`() {
		var value: SomlValue = SomlObject()

		repeat(MAX_DEPTH) {
			value = SomlObject("next" to value)
		}

		val error = assertFailsWith<SomlException> {
			Soml.decodeFromSomlValue<RecursiveNode>(value)
		}

		assertEquals(MAX_DEPTH, error.path.size)
	}

	@Test
	fun `typed encoding rejects cycles before exhausting the stack`() {
		val node = CyclicNode()
		node.next = node

		val error = assertFailsWith<SomlException> {
			Soml.encodeToSomlValue(node)
		}

		assertEquals(MAX_DEPTH, error.path.size)
	}

	@Test
	fun `polymorphic serializers that write trees keep their discriminator`() {
		val values = listOf<TreeBase>(TreeSubtype(7))
		val text = Soml.encodeToString(values)
		assertEquals(values, Soml.decodeFromString<List<TreeBase>>(text))
		assertTrue(text.contains("type: 'tree'"), text)
	}

	@Test
	fun `polymorphic tree writers reject a discriminator collision`() {
		assertFailsWith<SomlException> {
			Soml.encodeToString(listOf<TreeBase>(TreeSubtype(7, collision = true)))
		}
	}

	@Test
	fun `polymorphic tree writers reject scalar output`() {
		assertFailsWith<SomlException> {
			Soml.encodeToString(listOf<TreeBase>(TreeSubtype(7, scalar = true)))
		}
	}

	@Test
	fun `collection decoding preserves nested map keys and item positions`() {
		val text = "first: [{x: [1, 2]}, {}]\nsecond: []"
		val value = Soml.decodeFromString<Map<String, List<Map<String, List<Int>>>>>(text)
		assertEquals(mapOf("first" to listOf(mapOf("x" to listOf(1, 2)), emptyMap()), "second" to emptyList()), value)

		val error = assertFailsWith<SomlException> {
			Soml.decodeFromString<Map<String, List<Map<String, List<Int>>>>>("first: [{x: [1, 'wrong']}]")
		}

		assertEquals(listOf("first", 0, "x", 1), error.path)
	}

	@Test
	fun `empty collections keep their value types`() {
		assertEquals(SomlArray(), Soml.encodeToSomlValue(emptyList<Int>()))
		assertEquals(SomlObject(), Soml.encodeToSomlValue(emptyMap<String, Int>()))
		assertEquals(emptyList(), Soml.decodeFromString<List<Int>>("[]"))
		assertEquals(emptyMap(), Soml.decodeFromString<Map<String, Int>>("{}"))
	}

	@Test
	fun `key suggestions recognize transposition and omit already consumed keys`() {
		val transposed = assertFailsWith<SomlException> {
			Soml.decodeFromString<Postgres>("host: 'local'\nprot: 80")
		}

		assertTrue(transposed.reason.contains("Did you mean port?"), transposed.reason)

		val consumed = assertFailsWith<SomlException> {
			Soml.decodeFromString<Postgres>("host: 'local'\nhots: 'other'\nport: 80")
		}

		assertFalse(consumed.reason.contains("Did you mean host?"), consumed.reason)
	}

	@Test
	fun `long dissimilar keys do not produce misleading suggestions`() {
		val error = assertFailsWith<SomlException> {
			Soml.decodeFromString<Postgres>("${"a".repeat(10_000)}: 1")
		}

		assertFalse(error.reason.contains("Did you mean"), error.reason)
	}

	@Test
	fun `integer collection boundaries decode without truncation`() {
		val serializer = ListSerializer(Byte.serializer())
		assertEquals(listOf(Byte.MIN_VALUE, Byte.MAX_VALUE), Soml.decodeFromString(serializer, "[-128, 127]"))

		for (number in listOf(-129, 128)) {
			val error = assertFailsWith<SomlException> {
				Soml.decodeFromString(serializer, "[0, $number]")
			}

			assertEquals(listOf(1), error.path)
		}
	}

	@Test
	fun `integer map keys accept only their canonical decimal spelling`() {
		for (key in listOf("+1", "01", "-0", "-01", "１", " 1", "1 ", "9223372036854775808", "-9223372036854775809")) {
			val error = assertFailsWith<SomlException> {
				Soml.decodeFromSomlValue<Map<Long, Int>>(SomlObject(key to SomlInt(1)))
			}

			assertEquals(listOf(key), error.path)
			assertTrue(error.isAboutKey)
		}

		for (number in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)) {
			assertEquals(mapOf(number to 1), Soml.decodeFromSomlValue<Map<Long, Int>>(SomlObject(number.toString() to SomlInt(1))))
		}
	}

	@Test
	fun `container map keys fail with a format error`() {
		assertFailsWith<SomlException> {
			Soml.encodeToString(mapOf(listOf(1) to 2))
		}

		assertFailsWith<SomlException> {
			Soml.encodeToString(mapOf(mapOf("a" to 1) to 2))
		}
	}

	@Test
	fun `key suggestions match a full reference distance matrix`() {
		val random = kotlin.random.Random(712)

		repeat(500) { iteration ->
			val names = buildSet {
				while (size < 6) {
					add(buildString {
						repeat(random.nextInt(0, 24)) {
							append("abcd"[random.nextInt(4)])
						}
					})
				}
			}.toList()

			val original = names[random.nextInt(names.size)]
			val key = when {
				original.isEmpty() -> "x"
				else -> original.reversed() + if (random.nextBoolean()) "a" else ""
			}

			if (key in names) {
				return@repeat
			}

			val serialName = "SuggestionProbe$iteration"
			val descriptor = buildClassSerialDescriptor(serialName) {
				for (name in names) {
					element<Int>(name)
				}
			}

			val deserializer = object : kotlinx.serialization.DeserializationStrategy<Unit> {
				override val descriptor = descriptor

				override fun deserialize(decoder: Decoder) {
					decoder.beginStructure(descriptor).decodeElementIndex(descriptor)
				}
			}

			val error = assertFailsWith<SomlException> {
				Soml.decodeFromSomlValue(deserializer, SomlObject(key to SomlInt(1)))
			}

			val expected = names.minBy { referenceDistance(it, key) }.takeIf { referenceDistance(it, key) <= maxOf(1, key.length / 3) }
			val hint = if (expected == null) "" else ". Did you mean ${describeKey(expected)}?"
			assertEquals("Unknown key ${describeKey(key)} for $serialName$hint", error.reason)
		}
	}

	/**
	The full optimal string alignment matrix, kept as an independent reference for the rolling-row implementation.
	*/
	private fun referenceDistance(left: String, right: String): Int {
		val distances = Array(left.length + 1) { IntArray(right.length + 1) }

		for (index in 0..left.length) {
			distances[index][0] = index
		}

		for (index in 0..right.length) {
			distances[0][index] = index
		}

		for (leftIndex in 1..left.length) {
			for (rightIndex in 1..right.length) {
				val cost = if (left[leftIndex - 1] == right[rightIndex - 1]) 0 else 1
				distances[leftIndex][rightIndex] = minOf(distances[leftIndex - 1][rightIndex] + 1, distances[leftIndex][rightIndex - 1] + 1, distances[leftIndex - 1][rightIndex - 1] + cost)

				if (leftIndex > 1 && rightIndex > 1 && left[leftIndex - 1] == right[rightIndex - 2] && left[leftIndex - 2] == right[rightIndex - 1]) {
					distances[leftIndex][rightIndex] = minOf(distances[leftIndex][rightIndex], distances[leftIndex - 2][rightIndex - 2] + 1)
				}
			}
		}

		return distances[left.length][right.length]
	}
}

@Serializable
private data class RecursiveNode(val next: RecursiveNode? = null)

@Serializable
private class CyclicNode(var next: CyclicNode? = null)

@Serializable
private sealed interface TreeBase

@Serializable(with = TreeSubtypeSerializer::class)
private data class TreeSubtype(val number: Int, val collision: Boolean = false, val scalar: Boolean = false) : TreeBase

private object TreeSubtypeSerializer : KSerializer<TreeSubtype> {
	override val descriptor = buildClassSerialDescriptor("tree") {
		element<Int>("number")
	}

	override fun serialize(encoder: Encoder, value: TreeSubtype) {
		val tree = when {
			value.scalar -> SomlInt(value.number.toLong())
			value.collision -> SomlObject("type" to SomlString("wrong"), "number" to SomlInt(value.number.toLong()))
			else -> SomlObject("number" to SomlInt(value.number.toLong()))
		}

		(encoder as SomlEncoder).encodeSomlValue(tree)
	}

	override fun deserialize(decoder: Decoder): TreeSubtype {
		val tree = (decoder as SomlDecoder).decodeSomlValue() as SomlObject
		return TreeSubtype((tree.getValue("number") as SomlInt).value.toInt())
	}
}
