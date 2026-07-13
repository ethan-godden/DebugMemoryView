package samples;

/**
 * Designed for the diff highlighting. On each loop iteration this program
 * mutates existing state, allocates something NEW, and drops a reference so an
 * object becomes DELETED (rendered once as a translucent ghost).
 *
 * <p>Set a breakpoint at the marked line and repeatedly <b>Resume</b> (F8). Each
 * suspend the view highlights what changed since the previous one: {@code counter}
 * and the array element as CHANGED, the freshly allocated box as NEW, and the
 * previously held box as a DELETED ghost.
 */
public class MutationOverTimeDemo {

	static final class Box {
		int payload;

		Box(int payload) {
			this.payload = payload;
		}
	}

	static int counter = 0;

	public static void main(String[] args) {
		int[] running = new int[4];
		Box held = new Box(0);

		for (int i = 1; i <= 8; i++) {
			counter += i;                 // CHANGED: static counter
			running[i % running.length] = counter; // CHANGED: one array cell
			Box fresh = new Box(counter); // NEW: a fresh object each iteration
			held = fresh;                 // previous held box loses its last reference -> DELETED ghost
			System.out.println("i=" + i + " held=" + held.payload); // breakpoint here; Resume repeatedly
		}
	}
}
