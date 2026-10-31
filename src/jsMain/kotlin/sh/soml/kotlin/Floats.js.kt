package sh.soml.kotlin

import kotlin.math.abs

internal actual fun formatFloat(value: Double): String = formatFloat(value, abs(value).toString())

internal actual fun parseFloat(text: String): Double = text.toDouble()
