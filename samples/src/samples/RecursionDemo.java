package samples;

/**
 * Deep recursion, to show many stack frames at once. Each frame of
 * {@link #fib} carries its own {@code n} local, so the stack column grows tall.
 *
 * <p>Set a breakpoint on the base-case {@code return} to suspend at maximum
 * stack depth and read {@code n} in every frame.
 */
public class RecursionDemo {

	static long fib(int n) {
		if (n < 2) {
			return n; // breakpoint: suspends deep in the recursion, one frame per call
		}
		return fib(n - 1) + fib(n - 2);
	}

	static long factorial(int n) {
		if (n <= 1) {
			return 1;
		}
		return n * factorial(n - 1);
	}

	public static void main(String[] args) {
		long f = fib(8);
		long fact = factorial(6);
		System.out.println("fib(8) = " + f + ", 6! = " + fact);
	}
}
