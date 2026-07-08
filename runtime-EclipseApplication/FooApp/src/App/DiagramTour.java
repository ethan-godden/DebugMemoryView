package App;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Debug target for exercising the Eclipseview memory diagram.
 *
 * <p>Run this as a Java Application in the runtime workspace, then open the
 * "Memory Diagram" view (Debug category). Suspend at the numbered breakpoints
 * below to see each part of the renderer:
 *
 * <ol>
 *   <li>BREAKPOINT #1 &ndash; the full initial graph: locals, boxed values,
 *       interned vs. {@code new} strings, three array kinds, an object graph,
 *       an enum, and the static fields section.</li>
 *   <li>BREAKPOINT #2 &ndash; put a breakpoint here and press <b>Resume</b>
 *       repeatedly. Each suspend diffs against the previous one:
 *       <ul>
 *         <li><b>NEW</b>     &rarr; {@code newest}, freshly allocated this step</li>
 *         <li><b>CHANGED</b> &rarr; the current chain node's {@code label} field</li>
 *         <li><b>DELETED</b> &rarr; last step's node, now unreferenced &mdash;
 *             it renders once as a translucent ghost</li>
 *       </ul></li>
 *   <li>BREAKPOINT #3 &ndash; a reference cycle (A &harr; B) to exercise the
 *       bezier connection routing.</li>
 *   <li>BREAKPOINT #4 &ndash; deep recursion, so the stack panel shows several
 *       frames stacked, each with its own locals.</li>
 * </ol>
 */
public class DiagramTour {

	// ---- static fields: render in the statics section ----
	static int tickCount = 0;
	static final String BANNER = "Eclipseview"; //$NON-NLS-1$
	static Node registryHead = null; // static reference that follows the newest node

	enum Phase { WARMUP, RUNNING, COOLDOWN }

	/** Heap object with an outgoing reference &mdash; draws boxes and arrows. */
	static final class Node {
		int id;
		String label;
		Node next; // reference arrow (and, later, a cycle)

		Node(int id, String label) {
			this.id = id;
			this.label = label;
		}
	}

	/** Records render as ordinary heap objects. */
	record Point(int x, int y) {}

	public static void main(String[] args) {
		// --- primitives & locals ---
		int x = 10;
		double ratio = 4.8;
		boolean flag = true;
		Phase phase = Phase.WARMUP;

		// --- strings: interned identity vs. a distinct heap object ---
		String a = "hello"; //$NON-NLS-1$
		String b = "hello"; //$NON-NLS-1$              // a == b (same interned instance)
		String c = new String("hello"); //$NON-NLS-1$  // distinct object, a != c

		// --- boxed values: inside vs. outside the Integer cache ---
		Integer smallBoxed = 42;   // cached
		Integer bigBoxed = 1000;   // outside the cache -> its own box
		Double boxedD = 2.5;
		Boolean boxedFlag = Boolean.TRUE;

		// --- three kinds of array ---
		int[] primArr = { 1, 2, 3, 5, 8, 13 };
		String[] strArr = { "foo", "bar", "baz" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		Object[] refArr = { new Point(1, 2), new Point(3, 4), a };

		// --- a small object graph: head -> n2 -> n3 -> ... -> null ---
		Map<String, Node> byLabel = new HashMap<>();
		Node head = buildChain(6, byLabel);

		// >>> BREAKPOINT #1: initial snapshot; everything above is live.
		phase = Phase.RUNNING;

		// --- mutate across suspends to drive NEW / CHANGED / DELETED ---
		Node cursor = head;
		Node newest = null;
		while (cursor != null) {
			tickCount++;
			// Allocating a new node here drops the previous 'newest' (no local or
			// static still points at it), so it becomes a ghost on the next suspend.
			newest = new Node(100 + tickCount, "tick-" + tickCount); //$NON-NLS-1$
			registryHead = newest;                                    // static follows newest
			cursor.label = "visited@" + tickCount;                    // CHANGED field //$NON-NLS-1$

			// >>> BREAKPOINT #2: set here and press Resume repeatedly (see class doc).
			checkpoint(newest, cursor);

			cursor = cursor.next;
		}

		// --- a reference cycle: p <-> q ---
		Node p = new Node(1, "A"); //$NON-NLS-1$
		Node q = new Node(2, "B"); //$NON-NLS-1$
		p.next = q;
		q.next = p;
		registryHead = p;
		phase = Phase.COOLDOWN;

		// >>> BREAKPOINT #3: cycle is live (A -> B -> A).
		int depth = descend(5, p);

		System.out.println(BANNER + " done:"
				+ " ticks=" + tickCount
				+ " depth=" + depth
				+ " internedSame=" + (a == b)
				+ " newStringSame=" + (a == c)
				+ " boxedCached=" + (smallBoxed == Integer.valueOf(42))
				+ " phase=" + phase
				+ " x=" + x + " ratio=" + ratio + " flag=" + flag
				+ " arrays=" + primArr.length + "/" + strArr.length + "/" + refArr.length
				+ " boxed=" + bigBoxed + "/" + boxedD + "/" + boxedFlag
				+ " nodes=" + byLabel.size());
	}

	/** Builds a singly linked chain head -> ... -> null and indexes it by label. */
	private static Node buildChain(int n, Map<String, Node> index) {
		Node head = null;
		for (int i = n; i >= 1; i--) {
			Node node = new Node(i, "n" + i); //$NON-NLS-1$
			node.next = head;
			head = node;
			index.put(node.label, node);
		}
		return head;
	}

	/** Recurses so the stack panel shows several frames, each with its own locals. */
	private static int descend(int n, Node ring) {
		int level = n;                     // distinct local per frame
		String frameTag = "descend#" + n;  // distinct local per frame //$NON-NLS-1$
		List<Integer> seen = new ArrayList<>();
		seen.add(level);
		if (n == 0) {
			// >>> BREAKPOINT #4: suspend here; walk up the frames in the stack panel.
			return frameTag.length() - frameTag.length(); // == 0, keeps frameTag live
		}
		return 1 + descend(n - 1, ring.next); // 'ring' is a 2-cycle, so next is never null
	}

	/** Stable breakpoint anchor; also keeps its arguments referenced at the suspend. */
	private static void checkpoint(Node newest, Node cursor) {
		if (newest == cursor) {
			throw new IllegalStateException(); // unreachable; defeats dead-code elimination
		}
	}
}
