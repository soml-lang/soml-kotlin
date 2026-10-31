package sh.soml.kotlin

/*
Kotlin/Native's `Double.toString()` does not always write the shortest decimal, and sometimes writes one that reads back as another float, and its `toDouble()` rounds some long decimals to the wrong float, so the exact algorithms do both here.
*/

internal actual fun formatFloat(value: Double): String = formatFloatExactly(value)

// Kotlin/Native's own conversion is the estimate, which is at most a unit in the last place off.
internal actual fun parseFloat(text: String): Double = nearestFloat(text, text.toDouble())
