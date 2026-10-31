package sh.soml.kotlin

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.updateAndFetch

internal actual fun String.indexOfWithin(character: Char, start: Int, end: Int): Int = indexOfWithinPortably(character, start, end)

/*
A map that is replaced as a whole when it gets a key, which is safe on every thread with no lock, and cheap because a `Soml` instance learns about each class once.
*/
@OptIn(ExperimentalAtomicApi::class)
internal actual class ConcurrentCache<K : Any, V : Any> actual constructor() {
	private val map = AtomicReference(emptyMap<K, V>())

	actual fun getOrPut(key: K, makeValue: () -> V): V {
		map.load()[key]?.let {
			return it
		}

		val value = makeValue()

		map.updateAndFetch { current ->
			current[key]?.let {
				return it
			}

			current + (key to value)
		}

		return value
	}
}
