package samples;

import java.util.List;

/**
 * Nested Java records forming an immutable data tree. Records render like any
 * other heap object, with one field box per component.
 *
 * <p>Set a breakpoint on the final {@code println} to explore the nested
 * structure: an {@code Order} pointing at a {@code Customer} and a list of
 * {@code LineItem} records.
 */
public class RecordsDemo {

	record Customer(String name, String email) {
	}

	record LineItem(String product, int quantity, double unitPrice) {
		double total() {
			return quantity * unitPrice;
		}
	}

	record Order(int id, Customer customer, List<LineItem> items) {
		double grandTotal() {
			return items.stream().mapToDouble(LineItem::total).sum();
		}
	}

	public static void main(String[] args) {
		Customer customer = new Customer("Grace Hopper", "grace@example.com");
		Order order = new Order(1001, customer, List.of(
				new LineItem("Keyboard", 2, 49.99),
				new LineItem("Monitor", 1, 199.00),
				new LineItem("Cable", 3, 9.50)));

		double total = order.grandTotal();
		System.out.println("order " + order.id() + " total = " + total); // breakpoint here
	}
}
