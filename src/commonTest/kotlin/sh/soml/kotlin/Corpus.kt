package sh.soml.kotlin

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
A file of the conformance suite, by its path from the suite's directory without the extension, such as `valid/array/empty`.
*/
class CorpusFile(val name: String, val path: Path) {
	fun readBytes(): ByteArray = SystemFileSystem.source(path).buffered().use { it.readByteArray() }

	fun readText(): String = readBytes().decodeToString()

	/**
	The file next to this one with the same name and `suffix`, such as `.canonical.soml`.
	*/
	fun sibling(suffix: String): CorpusFile = CorpusFile(name, Path(path.parent!!, path.name.substringBefore('.') + suffix))
}

/**
Every file of the conformance suite under `directory` with the extension `extension`, sorted by name, without the canonical and formatted forms.
*/
fun corpusFiles(directory: String, extension: String = "soml"): List<CorpusFile> {
	val root = Path(CONFORMANCE_DIRECTORY, directory)
	val files = ArrayList<CorpusFile>()

	fun walk(path: Path, prefix: String) {
		for (child in SystemFileSystem.list(path)) {
			val name = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"

			if (SystemFileSystem.metadataOrNull(child)?.isDirectory == true) {
				walk(child, name)
			} else if (child.name.endsWith(".$extension") && !child.name.endsWith(".canonical.soml") && !child.name.endsWith(".formatted.soml")) {
				files.add(CorpusFile("$directory/" + name.removeSuffix(".$extension"), child))
			}
		}
	}

	walk(root, "")
	return files.sortedBy { it.name }
}

fun corpusFile(name: String): CorpusFile = CorpusFile(name, Path(CONFORMANCE_DIRECTORY, name))

/**
Runs `check` for each item, and fails once with every item that failed, so that one run shows them all.
*/
fun <T> checkEach(items: List<T>, name: (T) -> String, check: (T) -> Unit) {
	val failures = ArrayList<String>()

	for (item in items) {
		try {
			check(item)
		} catch (error: Throwable) {
			failures.add("${name(item)}: ${error.message?.lines()?.take(6)?.joinToString("\n\t")}")
		}
	}

	if (failures.isNotEmpty()) {
		throw AssertionError("${failures.size} of ${items.size} failed:\n" + failures.take(40).joinToString("\n"))
	}
}
