package samples;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The common JDK collections: {@code ArrayList}, {@code HashMap},
 * {@code HashSet}. Shows how the view walks the internal backing arrays and
 * entry objects of the collection implementations.
 *
 * <p>Set a breakpoint inside the loop to watch the collections fill up.
 */
public class CollectionsDemo {

	public static void main(String[] args) {
		List<String> list = new ArrayList<>();
		Set<Integer> set = new HashSet<>();
		Map<String, Integer> wordLengths = new HashMap<>();

		String[] words = { "apple", "banana", "cherry", "date", "apple" };
		for (String word : words) {
			list.add(word);
			set.add(word.length());
			wordLengths.put(word, word.length()); // breakpoint: watch all three grow
		}

		System.out.println(list);
		System.out.println(set);
		System.out.println(wordLengths);
	}
}
