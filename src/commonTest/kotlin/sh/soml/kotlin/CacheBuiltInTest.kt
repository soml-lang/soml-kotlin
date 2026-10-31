package sh.soml.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CacheBuiltInTest {
	@Test
	fun `a cached value keeps its identity without invoking another factory`() {
		val cache = ConcurrentCache<String, Any>()
		val value = Any()
		assertSame(value, cache.getOrPut("key") { value })
		assertSame(value, cache.getOrPut("key") { error("A cached value must not be constructed again") })
	}

	@Test
	fun `a failed factory leaves the key available for retry`() {
		val cache = ConcurrentCache<String, Int>()

		assertFailsWith<IllegalStateException> {
			cache.getOrPut("key") { error("Construction failed") }
		}

		assertEquals(7, cache.getOrPut("key") { 7 })
		assertEquals(7, cache.getOrPut("key") { error("A successful retry must be cached") })
	}

	@Test
	fun `a value installed during construction wins over the factory result`() {
		val cache = ConcurrentCache<String, Any>()
		val winner = Any()
		val discarded = Any()
		val result = cache.getOrPut("key") {
			assertSame(winner, cache.getOrPut("key") { winner })
			discarded
		}

		assertSame(winner, result)
		assertSame(winner, cache.getOrPut("key") { error("The installed value must be retained") })
	}

	@Test
	fun `a new key keeps other values installed during construction`() {
		val cache = ConcurrentCache<String, Any>()
		val first = Any()
		val second = Any()
		assertSame(first, cache.getOrPut("first") {
			assertSame(second, cache.getOrPut("second") { second })
			first
		})

		assertSame(first, cache.getOrPut("first") { error("The outer value must be cached") })
		assertSame(second, cache.getOrPut("second") { error("The inner value must be cached") })
	}
}
