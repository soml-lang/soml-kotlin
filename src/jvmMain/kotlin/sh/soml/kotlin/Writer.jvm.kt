package sh.soml.kotlin

/**
`Double.toString()` gives the shortest digits since JDK 19, except that it may choose two closer digits when one suffices, such as `4.9E-324` for the smallest subnormal instead of `5e-324`, so that case is corrected.
*/
internal actual fun formatFloat(value: Double): String {
	if (value.isInfinite()) {
		return if (value < 0) "-infinity" else "infinity"
	}

	if (value == 0.0) {
		return "0.0"
	}

	val magnitude = Math.abs(value)

	// From 10^-3 to 10^7, `Double.toString()` writes plain notation with the shortest digits, which is the canonical text there.
	if (magnitude >= 1e-3 && magnitude < 1e7) {
		return value.toString()
	}

	val javaText = magnitude.toString()
	val exponentIndex = javaText.indexOf('E')
	// Outside the plain range, the JDK always writes a nonzero digit, a decimal point, more digits, and an exponent.
	val exponent = javaText.substring(exponentIndex + 1).toInt()
	var last = exponentIndex

	while (javaText[last - 1] == '0' || javaText[last - 1] == '.') {
		last--
	}

	// The digits of the JDK's text without its decimal point, for the value 0.<digits> × 10^n.
	val digits = if (last == 1) javaText.substring(0, 1) else javaText[0] + javaText.substring(2, last)
	val n = exponent + 1

	return buildString(32) {
		if (value < 0) {
			append('-')
		}

		// A one-digit decimal and another two-digit one can only both read back as a value with few bits of precision, which is a subnormal. For any other value, the interval that reads back is far narrower than the step between them.
		if (digits.length == 2 && magnitude < java.lang.Double.MIN_NORMAL) {
			val shorter = oneDigit(magnitude, digits, n)

			if (shorter != 0) {
				// A round up to 10 is the digit 1 one place up.
				val carries = shorter == 10
				append(if (carries) 1 else shorter).append('e').append(exponent + (if (carries) 1 else 0))
				return@buildString
			}
		}

		layoutFloat(digits, n)
	}
}

/**
The one-digit decimal that reads back as `magnitude`, given its closest two digits at 0.<digits> × 10^n, or 0 when there is none. When both neighbors read back, the closer one wins, and then the even one. Returns 10 for a round up past 9.
*/
private fun oneDigit(magnitude: Double, digits: String, n: Int): Int {
	val down = digits[0] - '0'
	val up = down + 1
	val isDownValid = "${down}e${n - 1}".toDouble() == magnitude
	val isUpValid = "${up}e${n - 1}".toDouble() == magnitude

	if (isDownValid && isUpValid) {
		// The closest two digits decide the closer neighbor unless they fall on its midpoint.
		val secondDigit = digits[1] - '0'

		if (secondDigit != 5) {
			return if (secondDigit < 5) down else up
		}

		val midpoint = java.math.BigDecimal.valueOf((down * 10L) + 5, 2 - n)
		val comparison = java.math.BigDecimal(magnitude).compareTo(midpoint)

		return when {
			comparison == 0 -> if (down % 2 == 0) down else up
			comparison < 0 -> down
			else -> up
		}
	}

	return when {
		isDownValid -> down
		isUpValid -> up
		else -> 0
	}
}

internal actual fun parseFloat(text: String): Double = text.toDouble()
