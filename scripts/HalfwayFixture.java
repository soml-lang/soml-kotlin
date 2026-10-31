import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Random;

// Writes `text bits` lines: decimals exactly halfway between two floats, and just above and below, with the float that each rounds to by round-half-to-even.
public class HalfwayFixture {
	static void write(BigDecimal value, double expected) {
		// As `digits` e `exponent`, which is a SOML float of up to about 770 digits.
		var unscaled = value.unscaledValue();
		var exponent = -value.scale();
		System.out.println(unscaled + "e" + exponent + " " + Long.toHexString(Double.doubleToLongBits(expected)));
	}

	public static void main(String[] arguments) {
		var random = new Random(2026);

		for (var count = 0; count < 600; count++) {
			// Half of them subnormal or near the bottom, where the digits are longest.
			var bits = count % 2 == 0 ? random.nextLong(1L, 1L << 54) : random.nextLong(1L, 0x7FEFFFFFFFFFFFFFL);
			var value = Double.longBitsToDouble(bits);
			var next = Math.nextUp(value);
			var middle = new BigDecimal(value).add(new BigDecimal(next)).divide(BigDecimal.valueOf(2));
			var isEven = (bits & 1) == 0;
			write(middle, isEven ? value : next);
			var tiny = BigDecimal.ONE.scaleByPowerOfTen(-middle.scale() - 1);
			write(middle.add(tiny), next);
			write(middle.subtract(tiny), value);
		}
	}
}
