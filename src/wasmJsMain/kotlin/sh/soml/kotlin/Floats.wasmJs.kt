package sh.soml.kotlin

import kotlin.math.abs

/*
Kotlin/Wasm converts floats with its own code, which does not round every decimal correctly, so JavaScript's `String()` and `Number()`, which the JavaScript reference uses, do it instead.
*/

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("value => String(value)")
private external fun javaScriptString(value: Double): String

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("text => Number(text)")
private external fun javaScriptNumber(text: String): Double

internal actual fun formatFloat(value: Double): String = formatFloat(value, javaScriptString(abs(value)))

internal actual fun parseFloat(text: String): Double = javaScriptNumber(text)
