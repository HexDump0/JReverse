package fixture;

public class Outer {
	public class Inner {
		public int value() {
			return 42;
		}
	}

	public int run() {
		return new Inner().value();
	}
}
