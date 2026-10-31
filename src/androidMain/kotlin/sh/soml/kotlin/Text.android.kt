package sh.soml.kotlin

internal actual fun String.indexOfWithin(character: Char, start: Int, end: Int): Int = indexOfWithinPortably(character, start, end)
