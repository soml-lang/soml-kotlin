import java.util.*;
import sh.soml.*;

// Writes `bits spelling` lines: the canonical spelling of each float, by soml-java, for the float test of soml-kotlin on every platform.
public class FloatFixture {
	public static void main(String[] arguments) {
		var values = new LinkedHashSet<Double>();
		var random = new Random(2026);

		for (var bits = 1L; bits <= 2000; bits++) {
			values.add(Double.longBitsToDouble(bits));
		}

		for (var exponent = -1074; exponent <= 1023; exponent++) {
			values.add(Math.scalb(1.0, exponent));
		}

		for (var exponent = -323; exponent <= 308; exponent++) {
			values.add(Double.parseDouble("1e" + exponent));
			values.add(Double.parseDouble("5e" + exponent));
		}

		for (var boundary : new double[] {1e21, 1e-7, 1e-6, 1e7, 1e-3, Double.MAX_VALUE, Double.MIN_NORMAL}) {
			values.add(boundary);
			values.add(Math.nextUp(boundary));
			values.add(Math.nextDown(boundary));
		}

		while (values.size() < 30000) {
			var value = Double.longBitsToDouble(random.nextLong());

			if (!Double.isNaN(value) && !Double.isInfinite(value)) {
				values.add(Math.abs(value));
			}
		}

		for (var value : values) {
			if (value > 0 && !Double.isInfinite(value)) {
				System.out.println(Long.toHexString(Double.doubleToLongBits(value)) + " " + new SomlFloat(value));
			}
		}
	}
}
