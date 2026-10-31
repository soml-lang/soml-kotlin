package sh.soml.kotlin

import kotlin.math.abs

/*
Conversions between floats and decimals that are exact, for the platforms whose own conversions are not: the shortest decimal that reads back as a float, and the float nearest to a decimal.

The shortest decimal:

It is the free-format algorithm of Burger and Dybvig, "Printing Floating-Point Numbers Quickly and Accurately" (1996), which is Steele and White's with exact integers. It keeps the float, the distance to the next float up, and the distance to the next one down as integers over a common scale, and writes one digit at a time until the digits so far read back as the float. Of the shortest such decimals, it gives the one closest to the float, and of two equally close, the one with an even last digit, as canonical form wants.
*/

/**
Formats a float with the shared exact algorithm, independently of the platform's decimal formatter.
*/
internal fun formatFloatExactly(value: Double): String {
	if (value.isInfinite() || value == 0.0) {
		return formatFloat(value, "", 0)
	}

	val (digits, exponent) = shortestDecimal(abs(value))
	return formatFloat(value, digits, exponent)
}

/**
The digits of the shortest decimal that reads back as `value`, a finite float above zero, as 0.<digits> × 10^n, with the digits and `n`.
*/
internal fun shortestDecimal(value: Double): Pair<String, Int> {
	val bits = value.toRawBits()
	// Note: Not using Math.getExponent() because that Java API is unavailable in common code on JS, Wasm, and Native.
	val biasedExponent = ((bits ushr 52) and 0x7FF).toInt()
	val fraction = bits and 0xF_FFFF_FFFF_FFFFL
	// The value is `mantissa` × 2^`exponent`.
	val mantissa = if (biasedExponent == 0) fraction else fraction or (1L shl 52)
	val exponent = if (biasedExponent == 0) -1074 else biasedExponent - 1075
	// The float reads back from a decimal at either end of its interval only when its mantissa is even, by round-half-to-even.
	val isEven = mantissa % 2 == 0L
	// At a power of two, the float below is closer than the float above, so the interval is narrower below.
	val isUnevenInterval = fraction == 0L && biasedExponent > 1

	// The value is `remainder` / `scale`, the interval is from `remainder - lower` to `remainder + upper`, all over the same scale.
	val remainder: BigNatural
	val scale: BigNatural
	val upper: BigNatural
	val lower: BigNatural

	if (exponent >= 0) {
		val unit = BigNatural.of(1).shiftLeft(exponent)

		if (isUnevenInterval) {
			remainder = BigNatural.of(mantissa).shiftLeft(exponent + 2)
			scale = BigNatural.of(4)
			upper = unit.shiftLeft(1)
			lower = unit
		} else {
			remainder = BigNatural.of(mantissa).shiftLeft(exponent + 1)
			scale = BigNatural.of(2)
			upper = unit
			lower = unit.copy()
		}
	} else if (isUnevenInterval) {
		remainder = BigNatural.of(mantissa).shiftLeft(2)
		scale = BigNatural.of(1).shiftLeft(2 - exponent)
		upper = BigNatural.of(2)
		lower = BigNatural.of(1)
	} else {
		remainder = BigNatural.of(mantissa).shiftLeft(1)
		scale = BigNatural.of(1).shiftLeft(1 - exponent)
		upper = BigNatural.of(1)
		lower = BigNatural.of(1)
	}

	// The first digit is at 10^(n - 1). The estimate from the binary exponent is the exact power or one below it, and the check after it corrects it.
	// Note: Not using log10() because its biased estimate measured slower for ordinary JVM floats and broad Native float formatting.
	var n = kotlin.math.ceil((exponent + 64 - mantissa.countLeadingZeroBits() - 1) * 0.30102999566398114 - 1e-10).toInt()

	if (n >= 0) {
		scale.multiplyByPowerOfTen(n)
	} else {
		remainder.multiplyByPowerOfTen(-n)
		upper.multiplyByPowerOfTen(-n)
		lower.multiplyByPowerOfTen(-n)
	}

	// While the top of the interval reaches 1 at this scale, the first digit is one place higher.
	while (remainder.plus(upper).compareTo(scale).let { it > 0 || (isEven && it == 0) }) {
		scale.multiplyBy(10)
		n++
	}

	val digits = StringBuilder(17)

	while (true) {
		remainder.multiplyBy(10)
		upper.multiplyBy(10)
		lower.multiplyBy(10)
		var digit = 0

		while (remainder >= scale) {
			remainder.subtract(scale)
			digit++
		}

		// Whether the digits so far, with this digit or with it one higher, read back as the float.
		val low = remainder.compareTo(lower)
		val high = remainder.plus(upper).compareTo(scale)
		val isLowEnough = low < 0 || (isEven && low == 0)
		val isHighEnough = high > 0 || (isEven && high == 0)

		// The upper stopping condition prevents rounding a nine up to ten, so every selected digit fits one character.
		when {
			!isLowEnough && !isHighEnough -> digits.append(digit.digitToChar())
			isLowEnough && !isHighEnough -> {
				digits.append(digit.digitToChar())
				break
			}
			!isLowEnough -> {
				digits.append((digit + 1).digitToChar())
				break
			}
			else -> {
				// Both read back, so the closer one wins, and of two equally close ones, the even one.
				val half = remainder.shiftLeft(1).compareTo(scale)
				digits.append((if (half < 0 || (half == 0 && digit % 2 == 0)) digit else digit + 1).digitToChar())
				break
			}
		}
	}

	return digits.toString() to n
}

/**
The float nearest to `text`, a decimal in the grammar of a SOML float without underscores, and of two equally near, the one with an even mantissa, given `estimate`, a float near it, such as a platform's own conversion that may be off by a unit in the last place.

It compares the decimal exactly with the midpoints between the estimate and the floats next to it, and moves to the next float while the decimal is past a midpoint.
*/
internal fun nearestFloat(text: String, estimate: Double): Double {
	val isNegative = text.startsWith('-')
	val exponentIndex = text.indexOf('e')
	val mantissa = text.substring(if (isNegative) 1 else 0, if (exponentIndex == -1) text.length else exponentIndex)
	val pointIndex = mantissa.indexOf('.')
	// Note: Not using removeRange() because its allocation savings did not give consistent Native decimal-conversion timings.
	val digits = if (pointIndex == -1) mantissa else mantissa.substring(0, pointIndex) + mantissa.substring(pointIndex + 1)
	val fractionLength = if (pointIndex == -1) 0 else mantissa.length - pointIndex - 1
	var significantDigits = digits.trimEnd('0')
	val trailingZeros = digits.length - significantDigits.length
	significantDigits = significantDigits.trimStart('0')

	if (significantDigits.isEmpty()) {
		return if (isNegative) -0.0 else 0.0
	}

	// An exponent larger than the whole input cannot be canceled by the decimal point or its significant digits. Saturation keeps all following arithmetic within `Long`.
	val exponentLimit = text.length.toLong() + 1000
	val exponentText = if (exponentIndex == -1) "0" else text.substring(exponentIndex + 1)
	val writtenExponent = exponentText.toLongOrNull()?.coerceIn(-exponentLimit, exponentLimit)
		?: if (exponentText.startsWith('-')) -exponentLimit else exponentLimit
	// The decimal is `significand` × 10^`decimalExponent`.
	var decimalExponent = writtenExponent - fractionLength + trailingZeros
	val decimalOrder = decimalExponent + significantDigits.length - 1

	if (decimalOrder > 308) {
		return if (isNegative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
	}

	if (decimalOrder < -324) {
		return if (isNegative) -0.0 else 0.0
	}

	// A binary64 rounding midpoint has at most 768 significant decimal digits. A nonzero tail beyond them only decides which side of an exact tie the decimal lies on, so keep one sticky digit instead. Rust uses the same bound: https://doc.rust-lang.org/src/core/num/imp/dec2flt/decimal_seq.rs.html
	if (significantDigits.length > 768) {
		decimalExponent += significantDigits.length - 769
		significantDigits = significantDigits.take(768) + "1"
	}

	val significand = BigNatural.of(0)

	// Note: Not using chunked() because it allocates a list and copied strings for the decimal groups instead of reading their ranges directly.
	for (start in significantDigits.indices step 9) {
		val end = minOf(start + 9, significantDigits.length)
		var chunk = 0

		for (index in start..<end) {
			chunk = chunk * 10 + (significantDigits[index] - '0')
		}

		significand.multiplyByPowerOfTen(end - start)
		significand.add(chunk.toLong())
	}

	if (decimalExponent > 0) {
		significand.multiplyByPowerOfTen(decimalExponent.toInt())
	}

	// Below, the float is positive, and infinity is the float above the largest one.
	var result = kotlin.math.abs(estimate).coerceAtMost(Double.MAX_VALUE)

	/*
	How the decimal compares with the midpoint between the float with `bits` and the next one up: below 0, the same 0, or above 0.
	*/
	fun compareWithMidpointAbove(bits: Long): Int {
		val biasedExponent = ((bits ushr 52) and 0x7FF).toInt()
		val fraction = bits and 0xF_FFFF_FFFF_FFFFL
		val floatMantissa = if (biasedExponent == 0) fraction else fraction or (1L shl 52)
		val binaryExponent = if (biasedExponent == 0) -1074 else biasedExponent - 1075
		// The midpoint is (2 × mantissa + 1) × 2^(exponent - 1).
		var left = significand.copy()
		var right = BigNatural.of(2 * floatMantissa + 1)
		val midpointExponent = binaryExponent - 1

		if (decimalExponent < 0) {
			right.multiplyByPowerOfTen((-decimalExponent).toInt())
		}

		if (midpointExponent >= 0) {
			right = right.shiftLeft(midpointExponent)
		} else {
			left = left.shiftLeft(-midpointExponent)
		}

		return left.compareTo(right)
	}

	// Note: Not using nextUp()/nextDown() because stepping these known positive floats directly measured faster on both JVM and Native.
	while (true) {
		val bits = result.toRawBits()
		val isEven = bits % 2 == 0L
		val above = compareWithMidpointAbove(bits)

		// Past the midpoint above, or on it with an odd mantissa, the next float up is nearer. Above the largest float, that is infinity.
		if (above > 0 || (above == 0 && !isEven)) {
			if (result == Double.MAX_VALUE) {
				result = Double.POSITIVE_INFINITY
				break
			}

			result = Double.fromBits(bits + 1)
			continue
		}

		if (bits == 0L) {
			break
		}

		val below = compareWithMidpointAbove(bits - 1)

		// Before the midpoint below, or on it with an odd mantissa, the next float down is nearer.
		if (below < 0 || (below == 0 && !isEven)) {
			result = Double.fromBits(bits - 1)
			continue
		}

		break
	}

	return if (isNegative) -result else result
}

/**
A natural number of any size, as little-endian 32-bit limbs, with only the operations that `shortestDecimal()` needs. The operations that change it change it in place.

Note: Not using java.math.BigInteger because it is unavailable in common code on JS, Wasm, and Native.
*/
internal class BigNatural private constructor(private var limbs: IntArray, private var size: Int) : Comparable<BigNatural> {
	fun copy(): BigNatural = BigNatural(limbs.copyOf(size), size)

	private fun ensureCapacity(capacity: Int) {
		if (capacity > limbs.size) {
			limbs = limbs.copyOf(maxOf(capacity, limbs.size * 2))
		}
	}

	fun multiplyBy(factor: Int) {
		var carry = 0L

		// Note: Not using toUInt().toLong() because exact-conversion timings were mixed, including slower long-decimal correction on Native.
		for (index in 0..<size) {
			val product = (limbs[index].toLong() and 0xFFFF_FFFFL) * factor + carry
			limbs[index] = product.toInt()
			carry = product ushr 32
		}

		if (carry != 0L) {
			ensureCapacity(size + 1)
			limbs[size++] = carry.toInt()
		}
	}

	fun multiplyByPowerOfTen(power: Int) {
		if (isZero || power == 0) {
			return
		}

		var rest = power

		// 10^9 is the largest power of ten that fits in 32 bits.
		while (rest >= 9) {
			multiplyBy(1_000_000_000)
			rest -= 9
		}

		if (rest > 0) {
			// Note: Not using pow() because reading these precomputed exact powers measured faster than floating-point exponentiation.
			multiplyBy(POWERS_OF_TEN[rest])
		}
	}

	/**
	This number times 2^`bits`, as a new number.
	*/
	fun shiftLeft(bits: Int): BigNatural {
		if (bits == 0 || isZero) {
			return copy()
		}

		val limbShift = bits / 32
		val bitShift = bits % 32

		if (bitShift == 0) {
			val result = IntArray(size + limbShift)
			limbs.copyInto(result, limbShift, endIndex = size)
			return BigNatural(result, result.size)
		}

		val result = IntArray(size + limbShift + 1)

		for (index in 0..<size) {
			val limb = limbs[index].toLong() and 0xFFFF_FFFFL
			val shifted = limb shl bitShift
			result[index + limbShift] = result[index + limbShift] or shifted.toInt()
			result[index + limbShift + 1] = (shifted ushr 32).toInt()
		}

		val resultSize = result.size - if (result.last() == 0) 1 else 0
		return BigNatural(result, resultSize)
	}

	/**
	The sum of this number and `other`, as a new number.
	*/
	fun plus(other: BigNatural): BigNatural {
		val result = IntArray(maxOf(size, other.size) + 1)
		var carry = 0L

		for (index in 0..<result.size - 1) {
			val sum = (limbAt(index).toLong() and 0xFFFF_FFFFL) + (other.limbAt(index).toLong() and 0xFFFF_FFFFL) + carry
			result[index] = sum.toInt()
			carry = sum ushr 32
		}

		result[result.lastIndex] = carry.toInt()
		return BigNatural(result, maxOf(size, other.size) + if (carry == 0L) 0 else 1)
	}

	/**
	Subtracts `other`, which is not larger than this number.
	*/
	fun subtract(other: BigNatural) {
		var borrow = 0L

		for (index in 0..<size) {
			val difference = (limbs[index].toLong() and 0xFFFF_FFFFL) - (other.limbAt(index).toLong() and 0xFFFF_FFFFL) - borrow
			limbs[index] = difference.toInt()
			borrow = if (difference < 0) 1 else 0
		}

		// Note: Not using indexOfLast() because it scans unused retained capacity and gave no consistent gain in Native canonical formatting.
		while (size > 0 && limbs[size - 1] == 0) {
			size--
		}
	}

	val isZero: Boolean
		get() = size == 0

	/**
	Adds `value`, which is below 2^32.
	*/
	fun add(value: Long) {
		var carry = value

		for (index in 0..<size) {
			if (carry == 0L) {
				return
			}

			val sum = (limbs[index].toLong() and 0xFFFF_FFFFL) + carry
			limbs[index] = sum.toInt()
			carry = sum ushr 32
		}

		if (carry != 0L) {
			ensureCapacity(size + 1)
			limbs[size++] = carry.toInt()
		}
	}

	// Note: Not using getOrElse() because complete exact conversions showed no consistent benefit over this live-size check.
	private fun limbAt(index: Int): Int = if (index < size) limbs[index] else 0

	override fun compareTo(other: BigNatural): Int {
		// Note: Not using UInt.compareTo() because it showed no consistent Native gain and measured slower for multi-limb comparisons on the JVM.
		// Note: Not using Arrays.compareUnsigned() because it compares the low limbs first and is unavailable in common code.
		if (size != other.size) {
			return size.compareTo(other.size)
		}

		for (index in (size - 1) downTo 0) {
			val left = limbs[index].toLong() and 0xFFFF_FFFFL
			val right = other.limbs[index].toLong() and 0xFFFF_FFFFL

			if (left != right) {
				return left.compareTo(right)
			}
		}

		return 0
	}

	companion object {
		private val POWERS_OF_TEN = intArrayOf(1, 10, 100, 1000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000)

		fun of(value: Long): BigNatural {
			val limbs = intArrayOf(value.toInt(), (value ushr 32).toInt())
			return BigNatural(limbs, if (limbs[1] != 0) 2 else if (limbs[0] != 0) 1 else 0)
		}
	}
}
