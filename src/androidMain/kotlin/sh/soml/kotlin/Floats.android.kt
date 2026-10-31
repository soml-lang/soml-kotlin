package sh.soml.kotlin

// Android uses the shared exact formatter, without depending on a particular libcore formatter.
internal actual fun formatFloat(value: Double): String = formatFloatExactly(value)

// Android's decimal parser already rounds correctly, including exact halfway values and long significands.
internal actual fun parseFloat(text: String): Double = text.toDouble()
