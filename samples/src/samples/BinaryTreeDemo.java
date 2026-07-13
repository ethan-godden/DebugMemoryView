package samples;

/**
 * Small binary search tree. Each {@code Node} has two outgoing reference arrows
 * (left/right), so the heap fans out into a tree shape.
 *
 * <p>Set a breakpoint inside {@link #insert} to watch nodes attach, or in
 * {@link #depthFirst} to watch the recursive call stack grow and shrink.
 */
public class BinaryTreeDemo {

	static final class Node {
		final int key;
		Node left;
		Node right;

		Node(int key) {
			this.key = key;
		}
	}

	static Node insert(Node root, int key) {
		if (root == null) {
			return new Node(key);
		}
		if (key < root.key) {
			root.left = insert(root.left, key); // breakpoint: recursion + attaching left
		} else if (key > root.key) {
			root.right = insert(root.right, key);
		}
		return root;
	}

	static void depthFirst(Node node, StringBuilder out) {
		if (node == null) {
			return;
		}
		depthFirst(node.left, out);
		out.append(node.key).append(' '); // breakpoint: deep stack, one frame per level
		depthFirst(node.right, out);
	}

	public static void main(String[] args) {
		Node root = null;
		for (int key : new int[] { 50, 30, 70, 20, 40, 60, 80 }) {
			root = insert(root, key);
		}
		StringBuilder out = new StringBuilder();
		depthFirst(root, out);
		System.out.println("in-order: " + out);
	}
}
