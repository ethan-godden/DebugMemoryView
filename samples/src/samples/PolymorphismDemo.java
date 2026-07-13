package samples;

import java.util.List;

/**
 * Inheritance and polymorphism. A {@code List<Shape>} holds objects of several
 * runtime subtypes; the view labels each heap box with its actual runtime class
 * even though the static type is {@code Shape}.
 *
 * <p>Set a breakpoint inside the loop to inspect each shape's concrete type and
 * fields.
 */
public class PolymorphismDemo {

	abstract static class Shape {
		abstract double area();
	}

	static final class Circle extends Shape {
		final double radius;

		Circle(double radius) {
			this.radius = radius;
		}

		@Override
		double area() {
			return Math.PI * radius * radius;
		}
	}

	static final class Rectangle extends Shape {
		final double width;
		final double height;

		Rectangle(double width, double height) {
			this.width = width;
			this.height = height;
		}

		@Override
		double area() {
			return width * height;
		}
	}

	public static void main(String[] args) {
		List<Shape> shapes = List.of(
				new Circle(2.0),
				new Rectangle(3.0, 4.0),
				new Circle(1.5));

		double total = 0;
		for (Shape shape : shapes) {
			total += shape.area(); // breakpoint: inspect shape's runtime type each iteration
		}
		System.out.println("total area = " + total);
	}
}
