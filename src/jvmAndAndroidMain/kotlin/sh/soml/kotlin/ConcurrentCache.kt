package sh.soml.kotlin

internal actual class ConcurrentCache<K : Any, V : Any> actual constructor() {
	private val map = java.util.concurrent.ConcurrentHashMap<K, V>()

	// Note: Not using computeIfAbsent() because user naming strategies must run outside map update locks and can reenter the cache.
	// Note: Not using lazy(PUBLICATION) cache values because their wrappers added allocation to public cold decoding.
	actual fun getOrPut(key: K, makeValue: () -> V): V = map.getOrPut(key, makeValue)
}
