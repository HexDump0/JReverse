package fixture;

public class Greeter {
	private final String greeting;

	public Greeter(String greeting) {
		this.greeting = greeting;
	}

	public String greet(String name) {
		StringBuilder sb = new StringBuilder(greeting);
		for (int i = 0; i < 3; i++) {
			sb.append(i == 0 ? ", " : "!");
		}
		return sb.append(name).toString();
	}
}
