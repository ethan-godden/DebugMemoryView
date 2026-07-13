package samples;

/**
 * Strings, {@code StringBuilder}, and boxed primitives (autoboxing). Shows how
 * the view renders string values and boxed wrapper objects distinctly from raw
 * primitives.
 *
 * <p>Set a breakpoint on the final {@code println} to inspect every local at once.
 */
public class StringsAndBoxingDemo {

	public static void main(String[] args) {
		String greeting = "hello";
		String name = "world";
		String combined = greeting + ", " + name + "!";

		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < 5; i++) {
			builder.append(i).append('-'); // breakpoint: watch the builder's contents grow
		}

		Integer boxedInt = 42;
		Long boxedLong = 10_000_000_000L;
		Double boxedDouble = 3.14159;
		Boolean boxedBool = Boolean.TRUE;
		Character boxedChar = 'Z';

		// Values in the Integer cache (-128..127) are shared; large ones are not.
		Integer cachedA = 100;
		Integer cachedB = 100;
		Integer freshA = 1000;
		Integer freshB = 1000;

		System.out.println(combined + " " + builder + " " + boxedInt + boxedLong
				+ boxedDouble + boxedBool + boxedChar
				+ (cachedA == cachedB) + (freshA == freshB)); // breakpoint here
	}
}
