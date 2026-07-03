package App;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class MyApp {
	static double d =  4.8;
	int f1 = 1;
	String s = "foo";

	public static void main(String[] args) {
		int x = 10;
		Object o = new MyApp();
		List<Integer> list = new ArrayList<>();
		list.add(5);
		
		List<Character> cList = new LinkedList<>();
		cList.add('a');
		cList.addFirst('b');
		int y = fib(10);
		
		System.out.println(x + y);
		int[] arr = {1, 2, 3};
		
		
		String s = "hello";
		String s2 = "hello";
		System.out.println(s == s2);
	}
	
	private static int fib(int n) {
		int x = 10;
		int y = 12;
		
		int[] arr = new int[42];

		return switch(n) {
		case 1 -> 1;
		case 2 -> 2;
		default -> fib(n - 1) + fib(n - 2);
		};
	}

}
