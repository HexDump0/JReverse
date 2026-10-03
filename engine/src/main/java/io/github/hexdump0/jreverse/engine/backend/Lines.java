package io.github.hexdump0.jreverse.engine.backend;

import java.util.Arrays;

/** Maps character offsets in a source string to 0-based lines. */
public final class Lines {

	private final String text;
	private final int[] starts;

	public Lines(String text) {
		this.text = text;
		int[] s = new int[64];
		int n = 1;
		for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
			if (n == s.length) {
				s = Arrays.copyOf(s, n * 2);
			}
			s[n++] = i + 1;
		}
		this.starts = Arrays.copyOf(s, n);
	}

	public int count() {
		return starts.length;
	}

	public int line(int offset) {
		int i = Arrays.binarySearch(starts, offset);
		return i >= 0 ? i : -i - 2;
	}

	public int start(int line) {
		return starts[line];
	}

	/** The line's text without its newline. */
	public String text(int line) {
		int end = line + 1 < starts.length ? starts[line + 1] - 1 : text.length();
		if (end > starts[line] && text.charAt(end - 1) == '\r') {
			end--;
		}
		return text.substring(starts[line], end);
	}
}
