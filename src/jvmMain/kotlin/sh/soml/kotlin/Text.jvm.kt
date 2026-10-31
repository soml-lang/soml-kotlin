package sh.soml.kotlin

// The JDK's bounded searches are intrinsics, which scan many characters at a time.
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
internal actual fun String.indexOfWithin(character: Char, start: Int, end: Int): Int = (this as java.lang.String).indexOf(character.code, start, end)
