package samples;

/**
 * Singly linked list built on the heap. Shows a chain of {@code Node} objects
 * connected by reference arrows, plus a {@code head} local in the stack frame.
 *
 * <p>Set a breakpoint on the {@code return} in {@link #buildList} (or step through
 * the loop) to watch the chain grow node by node.
 */
public class LinkedListDemo {

	static final class Node {
		final int value;
		Node next;

		Node(int value) {
			this.value = value;
		}
	}

	static Node buildList(int... values) {
		Node head = null;
		for (int i = values.length - 1; i >= 0; i--) {
			Node node = new Node(values[i]);
			node.next = head;
			head = node; // breakpoint: watch head advance and the chain lengthen
		}
		return head;
	}

	static int sum(Node head) {
		int total = 0;
		for (Node cur = head; cur != null; cur = cur.next) {
			total += cur.value; // breakpoint: watch cur walk the chain, total grow
		}
		return total;
	}

	public static void main(String[] args) {
		Node head = buildList(10, 20, 30, 40, 50);
		int total = sum(head);
		System.out.println("sum = " + total);
	}
}
