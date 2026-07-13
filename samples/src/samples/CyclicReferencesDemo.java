package samples;

/**
 * Object graph containing reference cycles. Two {@code Person} objects point at
 * each other (mutual friendship), and a {@code Person} can be their own emergency
 * contact. Exercises the view's cycle handling (arrows that loop back).
 *
 * <p>Set a breakpoint on the final {@code println} once the graph is fully wired.
 */
public class CyclicReferencesDemo {

	static final class Person {
		final String name;
		Person bestFriend;
		Person emergencyContact;

		Person(String name) {
			this.name = name;
		}
	}

	public static void main(String[] args) {
		Person alice = new Person("Alice");
		Person bob = new Person("Bob");

		alice.bestFriend = bob;
		bob.bestFriend = alice;          // cycle: alice <-> bob
		alice.emergencyContact = alice;  // self-reference: arrow loops back to alice

		System.out.println(alice.name + " & " + bob.name); // breakpoint here
	}
}
