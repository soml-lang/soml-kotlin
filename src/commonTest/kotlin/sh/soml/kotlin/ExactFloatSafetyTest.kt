package sh.soml.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExactFloatSafetyTest {
	@Test
	fun `shared decimal digits stay exact at carry and exponent boundaries`() {
		val cases = listOf(
			0.9999999999999999 to "0.9999999999999999",
			1.0000000000000002 to "1.0000000000000002",
			9.999999999999998 to "9.999999999999998",
			10.000000000000002 to "10.000000000000002",
			99.99999999999999 to "99.99999999999999",
			100.00000000000001 to "100.00000000000001",
			999.9999999999999 to "999.9999999999999",
			1000.0000000000001 to "1000.0000000000001",
			Double.MIN_VALUE to "5e-324",
			Double.fromBits(2) to "1e-323",
			Double.fromBits(3) to "1.5e-323",
			Double.fromBits(1L shl 52) to "2.2250738585072014e-308",
			Double.MAX_VALUE to "1.7976931348623157e308",
		)

		for ((value, text) in cases) {
			assertEquals(text, formatFloatExactly(value))
			assertEquals("-$text", formatFloatExactly(-value))
		}
	}

	@Test
	fun `public float documents round trip through carry boundaries and the smallest exponents`() {
		val value = SomlObject(
			"below" to SomlFloat(9.999999999999998),
			"above" to SomlFloat(10.000000000000002),
			"tiny" to SomlFloat(Double.fromBits(2)),
			"maximum" to SomlFloat(Double.MAX_VALUE),
		)
		val text = Soml.encodeToString(SomlValue.serializer(), value)
		assertEquals("below: 9.999999999999998\nabove: 10.000000000000002\ntiny: 1e-323\nmaximum: 1.7976931348623157e308\n", text)
		assertEquals(value, Soml.parseToSomlValue(text))
	}

	@Test
	fun `underflow exponents still correct a nearby estimate`() {
		assertEquals(0.0.toRawBits(), nearestFloat("1e-2147483649", Double.MIN_VALUE).toRawBits())
		assertEquals((-0.0).toRawBits(), nearestFloat("-1e-2147483649", -Double.MIN_VALUE).toRawBits())
	}

	@Test
	fun `zero does not depend on its exponent or estimate`() {
		assertEquals(0.0.toRawBits(), nearestFloat("0e2147483648", Double.MIN_VALUE).toRawBits())
		assertEquals((-0.0).toRawBits(), nearestFloat("-0.0e-2147483649", -Double.MIN_VALUE).toRawBits())
	}

	@Test
	fun `extreme exponents take bounded work without integer wraparound`() {
		for (exponent in listOf("2147483647", "2147483648", "9223372036854775807", "999999999999999999999999")) {
			assertEquals(Double.POSITIVE_INFINITY, nearestFloat("1e$exponent", Double.POSITIVE_INFINITY))
			assertEquals(Double.NEGATIVE_INFINITY, nearestFloat("-1.5e$exponent", Double.NEGATIVE_INFINITY))
			assertEquals(0.0.toRawBits(), nearestFloat("1.5e-$exponent", 0.0).toRawBits())
			assertEquals((-0.0).toRawBits(), nearestFloat("-1.5e-$exponent", -0.0).toRawBits())
		}

		assertEquals(0.0.toRawBits(), nearestFloat("0.1e-2147483648", 0.0).toRawBits())
	}

	@Test
	fun `public parsing handles extreme float exponents safely`() {
		assertEquals(SomlObject("value" to SomlFloat(0.0)), Soml.parseToSomlValue("value: 0.0e2147483647"))

		for (text in listOf("value: 1.0e2147483647", "value: 0.1e-2147483648")) {
			assertFailsWith<SomlException> {
				Soml.parseToSomlValue(text)
			}
		}
	}

	@Test
	fun `long zero suffixes do not grow the exact integers`() {
		val zeros = "0".repeat(100_000)
		assertEquals(1.25, nearestFloat("1.25$zeros", 1.25))
		assertEquals(1.0, nearestFloat("1${zeros}e-100000", 1.0))
		assertEquals(Double.MIN_VALUE, nearestFloat("5${zeros}e-100324", 0.0))
	}

	@Test
	fun `distant nonzero digits preserve the direction at a rounding midpoint`() {
		val midpoint = "1.00000000000000011102230246251565404236316680908203125"
		val zeros = "0".repeat(100_000)
		val next = Double.fromBits(1.0.toRawBits() + 1)
		assertEquals(1.0, nearestFloat(midpoint + zeros, next))
		assertEquals(next, nearestFloat(midpoint + zeros + "1", 1.0))
		assertEquals(-next, nearestFloat("-" + midpoint + zeros + "1", -1.0))
		assertEquals(1.0, nearestFloat(midpoint.dropLast(1) + "4" + "9".repeat(100_000), next))
	}

	@Test
	fun `the longest binary64 midpoint keeps its distant rounding tail`() {
		// Halfway between the minimum normal float and the next one: (2^53 + 1) / 2^1075, with 768 significant decimal digits.
		val coefficient = "222507385850720163012305563795567615250361241457301801308322872404958664760675944619203679411688695321398552054903200090343478188441232557218436756334761702051817599892294139362996674259828589999483014897143355557856769327930601597818316214242506796246078529588519927249357768832073249247992481686923224716596493432925878395010225097395757951057160073834364573849432419299709217920738991976169431413149717326525502008499797367678374315520581880443916381057236779117517775622749741380425338708447819365553307386742083452616251302946202273010905482006765402020154711200202813970014157525912344017736224427371246815175018974555997865323425588621961151633592416795802960447706494647018477736093430045142168360701364747951396213837722826145437693412532098591327667236328125"
		val minimumNormal = Double.fromBits(1L shl 52)
		val next = Double.fromBits(minimumNormal.toRawBits() + 1)
		val tailLength = 10_000
		assertEquals(minimumNormal, nearestFloat("${coefficient}e-1075", next))
		assertEquals(next, nearestFloat(coefficient + "0".repeat(tailLength) + "1e-${1076 + tailLength}", minimumNormal))
		assertEquals(minimumNormal, nearestFloat(coefficient.dropLast(1) + "4" + "9".repeat(tailLength) + "e-${1075 + tailLength}", next))
	}

	@Test
	fun `exact arithmetic keeps zero and carry representations normalized`() {
		val zero = BigNatural.of(0)
		assertEquals(0, zero.shiftLeft(1024).compareTo(zero))
		assertTrue(zero.plus(zero).isZero)

		for (bits in listOf(0, 1, 31, 32, 33, 60)) {
			val number = BigNatural.of(3)
			assertEquals(0, number.shiftLeft(bits).compareTo(BigNatural.of(3L shl bits)))
		}

		val number = BigNatural.of(0xFFFF_FFFFL)
		number.add(1)
		assertEquals(0, number.compareTo(BigNatural.of(0x1_0000_0000L)))
		assertEquals(0, number.copy().compareTo(number))
		number.subtract(number.copy())
		assertTrue(number.isZero)
		number.multiplyByPowerOfTen(100_000)
		assertTrue(number.isZero)
	}
}
