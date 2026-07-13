package samples;

import java.util.Arrays;

/**
 * Primitive arrays, a 2-D array (array of arrays), and an object array.
 * Shows how the view renders array boxes, indices, and element references.
 *
 * <p>Set a breakpoint after {@code grid} and {@code people} are populated, or
 * inside {@link #bubbleSort} to watch elements swap between suspends.
 */
public class ArraysDemo {

	record Person(String name, int age) {
	}

	static void bubbleSort(int[] a) {
		for (int i = 0; i < a.length - 1; i++) {
			for (int j = 0; j < a.length - 1 - i; j++) {
				if (a[j] > a[j + 1]) {
					int tmp = a[j];
					a[j] = a[j + 1];
					a[j + 1] = tmp; // breakpoint: watch two cells swap each suspend
				}
			}
		}
	}

	public static void main(String[] args) {
		int[] numbers = { 5, 2, 8, 1, 9, 3 };

		int[][] grid = new int[3][3];
		for (int r = 0; r < grid.length; r++) {
			for (int c = 0; c < grid[r].length; c++) {
				grid[r][c] = r * 3 + c;
			}
		}

		Person[] people = {
				new Person("Ada", 36),
				new Person("Alan", 41),
				new Person("Grace", 45),
		};

		bubbleSort(numbers); // breakpoint here: inspect numbers/grid/people before sorting
		System.out.println(Arrays.toString(numbers));
		System.out.println(Arrays.deepToString(grid));
		System.out.println(Arrays.toString(people));
	}
}
