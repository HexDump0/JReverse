package io.github.hexdump0.jreverse.engine.backend;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.extern.IContextSource;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

import io.github.hexdump0.jreverse.engine.rpc.ErrorCode;
import io.github.hexdump0.jreverse.engine.rpc.RpcException;

/**
 * Vineflower, for JVM class files only (JAR, AAR, a single class). Each class is
 * decompiled on its own, with the whole archive as a library so types resolve.
 * Class ids are jadx's, so both list the same classes.
 */
public final class VineflowerBackend implements Backend {

	public static final String ID = "vineflower";

	private static final Map<String, Object> OPTIONS = Map.of(
			IFernflowerPreferences.INDENT_STRING, "    ",
			IFernflowerPreferences.THREADS, "1",
			IFernflowerPreferences.DECOMPILER_COMMENTS, "1",
			IFernflowerPreferences.MAX_PROCESSING_METHOD, "30");

	private final Path archive;
	private final Path temp;
	private final ZipFile zip;
	private final List<ClassEntry> classes;
	/** Top-level class id to its class file and those of its inner classes. */
	private final Map<String, List<String>> files;

	private VineflowerBackend(Path archive, Path temp, ZipFile zip, List<ClassEntry> classes, Map<String, List<String>> files) {
		this.archive = archive;
		this.temp = temp;
		this.zip = zip;
		this.classes = classes;
		this.files = files;
	}

	/**
	 * @param aar whether {@code input} is an AAR, whose code is the {@code classes.jar} inside
	 * @param single a lone class file, or null for an archive
	 */
	public static VineflowerBackend load(Path input, boolean aar, String single, List<ClassEntry> classes) throws RpcException {
		Path temp = null;
		try {
			Path archive = input;
			if (single != null) {
				// A lone class file becomes a one-entry archive, so both cases work the same.
				temp = Files.createTempFile("jreverse-vf-", ".jar");
				try (var out = new ZipOutputStream(Files.newOutputStream(temp))) {
					out.putNextEntry(new ZipEntry(single + ".class"));
					Files.copy(input, out);
					out.closeEntry();
				}
				archive = temp;
			} else if (aar) {
				temp = Files.createTempFile("jreverse-vf-", ".jar");
				try (ZipFile outer = new ZipFile(input.toFile()); InputStream in = outer.getInputStream(outer.getEntry("classes.jar"))) {
					Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
				}
				archive = temp;
			}
			ZipFile zip = new ZipFile(archive.toFile());
			return new VineflowerBackend(archive, temp, zip, classes, index(zip, classes));
		} catch (IOException | RuntimeException e) {
			deleteQuietly(temp);
			throw new RpcException(ErrorCode.OPEN_FAILED, "Vineflower could not read " + input.getFileName() + ": " + e.getMessage(), e);
		}
	}

	private static Map<String, List<String>> index(ZipFile zip, List<ClassEntry> classes) {
		Set<String> tops = new HashSet<>();
		classes.forEach(c -> tops.add(c.id()));
		Map<String, List<String>> files = new HashMap<>();
		Enumeration<? extends ZipEntry> entries = zip.entries();
		while (entries.hasMoreElements()) {
			String name = entries.nextElement().getName();
			if (!name.endsWith(".class") || name.startsWith("META-INF/")) {
				continue;
			}
			String id = name.substring(0, name.length() - 6);
			// Strip $Inner parts until a top-level class is left: a/B$C$1 belongs to a/B.
			String top = id;
			while (!tops.contains(top) && top.lastIndexOf('$') > top.lastIndexOf('/')) {
				top = top.substring(0, top.lastIndexOf('$'));
			}
			if (tops.contains(top)) {
				files.computeIfAbsent(top, k -> new ArrayList<>()).add(name);
			}
		}
		return files;
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public List<ClassEntry> classes() {
		return classes;
	}

	@Override
	public Decompiled decompile(String classId) throws RpcException {
		List<String> names = files.get(classId);
		if (names == null) {
			throw new RpcException(ErrorCode.NO_CLASS, "class not found: " + classId);
		}
		StringBuilder out = new StringBuilder();
		Fernflower ff = new Fernflower(NO_SAVER, OPTIONS, IFernflowerLogger.NO_OP);
		try {
			ff.addSource(new Source(classId, names, out));
			ff.addLibrary(archive.toFile());
			ff.decompileContext();
		} catch (Exception | StackOverflowError e) {
			throw new RpcException(ErrorCode.DECOMPILE_FAILED, "Vineflower failed on " + classId + ": " + e, e);
		} finally {
			ff.clearContext();
		}
		if (out.isEmpty()) {
			throw new RpcException(ErrorCode.DECOMPILE_FAILED, "Vineflower produced nothing for " + classId);
		}
		String code = out.toString();
		return new Decompiled(code, countWarnings(code));
	}

	@Override
	public void close() {
		try {
			zip.close();
		} catch (IOException e) {
			System.err.println("engine: closing " + archive + ": " + e);
		}
		deleteQuietly(temp);
	}

	/** Vineflower flags trouble with {@code $VF:} comments. */
	static int countWarnings(String code) {
		int n = 0;
		for (int i = code.indexOf("$VF:"); i >= 0; i = code.indexOf("$VF:", i + 4)) {
			n++;
		}
		return n;
	}

	private static void deleteQuietly(Path p) {
		if (p != null) {
			try {
				Files.deleteIfExists(p);
			} catch (IOException e) {
				p.toFile().deleteOnExit();
			}
		}
	}

	/** One class and its inner classes, read from the archive; the output goes to {@code out}. */
	private final class Source implements IContextSource {

		private final String name;
		private final List<Entry> entries;
		private final StringBuilder out;

		Source(String name, List<String> files, StringBuilder out) {
			this.name = name;
			this.entries = files.stream().map(f -> Entry.atBase(f.substring(0, f.length() - CLASS_SUFFIX.length()))).toList();
			this.out = out;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public Entries getEntries() {
			return new Entries(entries, List.of(), List.of());
		}

		@Override
		public InputStream getInputStream(String resource) throws IOException {
			ZipEntry e = zip.getEntry(resource);
			return e == null ? null : zip.getInputStream(e);
		}

		@Override
		public IOutputSink createOutputSink(IResultSaver saver) {
			return new IOutputSink() {
				@Override
				public void begin() {
				}

				@Override
				public void acceptClass(String qualifiedName, String fileName, String content, int[] mapping) {
					if (content != null) {
						out.append(content);
					}
				}

				@Override
				public void acceptDirectory(String directory) {
				}

				@Override
				public void acceptOther(String path) {
				}

				@Override
				public void close() {
				}
			};
		}
	}

	/** Output goes through {@link Source}'s sink; nothing is written to disk. */
	private static final IResultSaver NO_SAVER = new IResultSaver() {
		@Override
		public void saveFolder(String path) {
		}

		@Override
		public void copyFile(String source, String path, String entryName) {
		}

		@Override
		public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
		}

		@Override
		public void createArchive(String path, String archiveName, Manifest manifest) {
		}

		@Override
		public void saveDirEntry(String path, String archiveName, String entryName) {
		}

		@Override
		public void copyEntry(String source, String path, String archiveName, String entry) {
		}

		@Override
		public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, String content) {
		}

		@Override
		public void closeArchive(String path, String archiveName) {
		}
	};

	/** For a single class file: its id, taken from the class list jadx built. */
	public static String singleId(List<ClassEntry> classes) {
		return classes.size() == 1 ? classes.get(0).id() : null;
	}
}
