package samples;

/**
 * Enum constants and static fields. Enum values are singletons on the heap, and
 * static fields render in the dedicated statics area of the diagram.
 *
 * <p>Set a breakpoint inside {@link #advance} to watch {@code current} retarget
 * from one enum singleton to the next, and the static counter change.
 */
public class EnumsAndStaticsDemo {

	enum TrafficLight {
		RED, GREEN, YELLOW;

		TrafficLight next() {
			return switch (this) {
				case RED -> GREEN;
				case GREEN -> YELLOW;
				case YELLOW -> RED;
			};
		}
	}

	static int transitions = 0;
	static TrafficLight busiest = TrafficLight.RED;

	static TrafficLight advance(TrafficLight current) {
		transitions++; // breakpoint: static counter ticks, current retargets
		return current.next();
	}

	public static void main(String[] args) {
		TrafficLight current = TrafficLight.RED;
		for (int i = 0; i < 6; i++) {
			current = advance(current);
		}
		System.out.println("ended on " + current + " after " + transitions + " transitions");
	}
}
