package io.github.hexdump0.jreverse.engine.session;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Who signed an APK or JAR: the v1 (JAR) signature files, plus the APK
 * Signature Scheme v2/v3 block that sits just before the zip's central
 * directory. Signatures are not verified, only read.
 */
public final class Signing {

	private static final int V2 = 0x7109871a;
	private static final int V3 = 0xf05368c0;
	private static final int V31 = 0x1b93ad61;
	private static final byte[] MAGIC = "APK Sig Block 42".getBytes(StandardCharsets.US_ASCII);

	/** @param schemes e.g. {@code ["v1", "v2", "v3"]} */
	public record Result(List<String> schemes, List<Cert> certs) {
	}

	public record Cert(String subject, String issuer, String serial, String notBefore, String notAfter, String algorithm,
			String key, String sha256, String sha1, boolean debug) {
	}

	private Signing() {
	}

	public static Result read(Path path, ZipFile zip) {
		List<String> schemes = new ArrayList<>();
		Map<String, Cert> certs = new LinkedHashMap<>();
		try {
			List<Cert> v1 = v1(zip);
			if (!v1.isEmpty()) {
				schemes.add("v1");
				v1.forEach(c -> certs.putIfAbsent(c.sha256(), c));
			}
		} catch (IOException | RuntimeException e) {
			System.err.println("engine: v1 signature unreadable: " + e);
		}
		try {
			for (Map.Entry<Integer, ByteBuffer> block : signingBlock(path).entrySet()) {
				String scheme = switch (block.getKey()) {
					case V2 -> "v2";
					case V3 -> "v3";
					case V31 -> "v3.1";
					default -> null;
				};
				if (scheme == null) {
					continue;
				}
				schemes.add(scheme);
				schemeCerts(block.getValue()).forEach(c -> certs.putIfAbsent(c.sha256(), c));
			}
		} catch (IOException | RuntimeException e) {
			System.err.println("engine: signing block unreadable: " + e);
		}
		return new Result(schemes, List.copyOf(certs.values()));
	}

	private static List<Cert> v1(ZipFile zip) throws IOException {
		List<Cert> out = new ArrayList<>();
		var entries = zip.entries();
		while (entries.hasMoreElements()) {
			ZipEntry e = entries.nextElement();
			String name = e.getName().toUpperCase(Locale.ROOT);
			if (!name.startsWith("META-INF/") || name.indexOf('/', 9) >= 0) {
				continue;
			}
			if (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC")) {
				try (InputStream in = zip.getInputStream(e)) {
					// A PKCS#7 SignedData blob; the factory pulls out its certificates.
					for (Certificate c : x509().generateCertificates(in)) {
						if (c instanceof X509Certificate x) {
							out.add(cert(x));
						}
					}
				} catch (CertificateException ex) {
					System.err.println("engine: bad certificate in " + e.getName() + ": " + ex.getMessage());
				}
			}
		}
		return out;
	}

	/** The id-value pairs of the APK Signing Block, or none if the file has no block. */
	static Map<Integer, ByteBuffer> signingBlock(Path path) throws IOException {
		Map<Integer, ByteBuffer> out = new LinkedHashMap<>();
		try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "r")) {
			long cd = centralDirectoryOffset(f);
			if (cd < 32) {
				return out;
			}
			ByteBuffer footer = read(f, cd - 24, 24);
			long size = footer.getLong();
			byte[] magic = new byte[16];
			footer.get(magic);
			if (!Arrays.equals(magic, MAGIC) || size < 24 || size > cd - 8 || size > Integer.MAX_VALUE) {
				return out;
			}
			ByteBuffer pairs = read(f, cd - size, (int) (size - 24));
			while (pairs.remaining() >= 12) {
				long len = pairs.getLong();
				if (len < 4 || len > pairs.remaining()) {
					break;
				}
				int id = pairs.getInt();
				ByteBuffer value = slice(pairs, (int) len - 4);
				out.put(id, value);
			}
		}
		return out;
	}

	private static long centralDirectoryOffset(RandomAccessFile f) throws IOException {
		long len = f.length();
		int scan = (int) Math.min(len, 22 + 0xffff);
		ByteBuffer tail = read(f, len - scan, scan);
		for (int i = scan - 22; i >= 0; i--) {
			if (tail.getInt(i) == 0x06054b50) {
				return Integer.toUnsignedLong(tail.getInt(i + 16));
			}
		}
		return -1;
	}

	/** v2 and v3 share the layout up to the certificates: signers, each with signed data. */
	private static List<Cert> schemeCerts(ByteBuffer value) {
		List<Cert> out = new ArrayList<>();
		try {
			ByteBuffer signers = prefixed(value);
			while (signers.hasRemaining()) {
				ByteBuffer signer = prefixed(signers);
				ByteBuffer signedData = prefixed(signer);
				prefixed(signedData); // digests
				ByteBuffer certificates = prefixed(signedData);
				while (certificates.hasRemaining()) {
					ByteBuffer der = prefixed(certificates);
					byte[] bytes = new byte[der.remaining()];
					der.get(bytes);
					try {
						out.add(cert((X509Certificate) x509().generateCertificate(new ByteArrayInputStream(bytes))));
					} catch (CertificateException e) {
						System.err.println("engine: bad certificate in signing block: " + e.getMessage());
					}
				}
			}
		} catch (BufferUnderflowException | IllegalArgumentException e) {
			System.err.println("engine: malformed signing block: " + e);
		}
		return out;
	}

	private static Cert cert(X509Certificate x) {
		byte[] der;
		try {
			der = x.getEncoded();
		} catch (CertificateEncodingException e) {
			der = new byte[0];
		}
		String subject = x.getSubjectX500Principal().getName("RFC1779");
		return new Cert(subject, x.getIssuerX500Principal().getName("RFC1779"), x.getSerialNumber().toString(16),
				x.getNotBefore().toInstant().toString(), x.getNotAfter().toInstant().toString(), x.getSigAlgName(),
				keyDescription(x.getPublicKey()), digest("SHA-256", der), digest("SHA-1", der),
				subject.contains("CN=Android Debug"));
	}

	private static String keyDescription(PublicKey key) {
		if (key instanceof RSAPublicKey rsa) {
			return "RSA " + rsa.getModulus().bitLength();
		}
		if (key instanceof ECPublicKey ec) {
			return "EC " + ec.getParams().getCurve().getField().getFieldSize();
		}
		return key.getAlgorithm();
	}

	private static String digest(String alg, byte[] data) {
		try {
			return HexFormat.ofDelimiter(":").withUpperCase().formatHex(MessageDigest.getInstance(alg).digest(data));
		} catch (NoSuchAlgorithmException e) {
			return "";
		}
	}

	private static CertificateFactory x509() {
		try {
			return CertificateFactory.getInstance("X.509");
		} catch (CertificateException e) {
			throw new IllegalStateException(e);
		}
	}

	private static ByteBuffer read(RandomAccessFile f, long at, int len) throws IOException {
		byte[] b = new byte[len];
		f.seek(at);
		f.readFully(b);
		return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
	}

	private static ByteBuffer prefixed(ByteBuffer buf) {
		int len = buf.getInt();
		if (len < 0 || len > buf.remaining()) {
			throw new IllegalArgumentException("length " + len + " exceeds " + buf.remaining());
		}
		return slice(buf, len);
	}

	private static ByteBuffer slice(ByteBuffer buf, int len) {
		ByteBuffer s = buf.slice(buf.position(), len).order(ByteOrder.LITTLE_ENDIAN);
		buf.position(buf.position() + len);
		return s;
	}
}
