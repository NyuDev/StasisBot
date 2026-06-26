package fr.nyuway.stasisbot.control;

import fr.nyuway.stasisbot.StasisBot;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-to-end crypto + framing for the remote-control channel that rides on the
 * game's private messages ({@code /msg}). No port is ever opened: control frames
 * travel as ordinary whispers between the bot account and the operator's account.
 *
 * <p>Each logical message is a small UTF-8 frame {@code v1|<epochMillis>|<type>|<payload>}
 * encrypted with <b>AES-256-GCM</b> (key = SHA-256 of the shared secret, fresh 12-byte
 * nonce per message, 128-bit auth tag). The {@code nonce||ciphertext} blob is base64'd
 * (URL-safe, unpadded) and split into whisper-sized chunks carrying a small header
 * {@code !ctl <id>/<part>/<total>:<b64>} so the other side can reassemble it.
 *
 * <p>Confidentiality (even 2b2t staff reading whispers see only ciphertext),
 * integrity + authenticity (GCM tag — only a holder of the secret can forge a frame)
 * and a timestamp window (replay guard) all come from this layer. The secret itself
 * is never transmitted.
 */
public final class ControlProtocol {

	/** Wire prefix carried inside a whisper. */
	public static final String PREFIX = "!ctl";
	private static final Pattern LINE =
			Pattern.compile("^!ctl ([0-9a-f]{12})/(\\d+)/(\\d+):(.*)$");

	/** Max base64 chars per chunk — kept well under the 256-char chat input cap with /msg overhead. */
	public static final int MAX_CHUNK = 160;
	/** Reject frames whose timestamp is outside this window (clock skew + transit + replay guard). */
	public static final long WINDOW_MILLIS = 45_000L;

	private static final String CIPHER = "AES/GCM/NoPadding";
	private static final int GCM_TAG_BITS = 128;
	private static final int NONCE_LEN = 12;

	private final byte[] key; // 32 bytes (AES-256), or null when control is disabled
	private final SecureRandom rng = new SecureRandom();

	public ControlProtocol(String secret) {
		this.key = (secret == null || secret.isBlank()) ? null : sha256(secret.trim());
	}

	/** True when a non-blank secret was supplied (control channel usable). */
	public boolean isReady() { return key != null; }

	/** A decrypted, parsed frame. */
	public record Frame(String type, String payload, long timestamp) {}

	/** One reassembly chunk parsed off the wire. */
	public record Chunk(String id, int part, int total, String data) {}

	/** Encrypt a (type, payload) frame and split it into ready-to-send whisper lines. */
	public List<String> encode(String type, String payload) {
		String plain = "v1|" + System.currentTimeMillis() + "|" + type + "|" + (payload == null ? "" : payload);
		byte[] blob = encrypt(plain.getBytes(StandardCharsets.UTF_8));
		String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(blob);
		String id = randomId();
		int total = Math.max(1, (b64.length() + MAX_CHUNK - 1) / MAX_CHUNK);
		List<String> lines = new ArrayList<>(total);
		for (int i = 0; i < total; i++) {
			int from = i * MAX_CHUNK;
			int to = Math.min(from + MAX_CHUNK, b64.length());
			lines.add(PREFIX + " " + id + "/" + i + "/" + total + ":" + b64.substring(from, to));
		}
		return lines;
	}

	/** True when {@code body} is a well-formed control line. */
	public static boolean isControlLine(String body) {
		return body != null && body.trim().startsWith(PREFIX + " ") && LINE.matcher(body.trim()).matches();
	}

	/** Parse a single control line into its chunk header + data, or empty. */
	public static Optional<Chunk> parseChunk(String body) {
		if (body == null) return Optional.empty();
		Matcher m = LINE.matcher(body.trim());
		if (!m.matches()) return Optional.empty();
		try {
			return Optional.of(new Chunk(m.group(1), Integer.parseInt(m.group(2)),
					Integer.parseInt(m.group(3)), m.group(4)));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	/** Decrypt a fully-reassembled base64 blob into a frame, or empty on any failure. */
	public Optional<Frame> decode(String b64) {
		if (key == null || b64 == null) return Optional.empty();
		try {
			byte[] blob = Base64.getUrlDecoder().decode(b64);
			byte[] plain = decrypt(blob);
			if (plain == null) return Optional.empty();
			String s = new String(plain, StandardCharsets.UTF_8);
			String[] p = s.split("\\|", 4);
			if (p.length < 4 || !"v1".equals(p[0])) return Optional.empty();
			long ts = Long.parseLong(p[1]);
			return Optional.of(new Frame(p[2], p[3], ts));
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	/** True when a frame timestamp is within the accepted window (replay/skew guard). */
	public boolean inWindow(long ts) {
		long d = System.currentTimeMillis() - ts;
		return d <= WINDOW_MILLIS && d >= -WINDOW_MILLIS;
	}

	// --- crypto ---------------------------------------------------------------

	private byte[] encrypt(byte[] plain) {
		try {
			byte[] nonce = new byte[NONCE_LEN];
			rng.nextBytes(nonce);
			Cipher c = Cipher.getInstance(CIPHER);
			c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
			byte[] ct = c.doFinal(plain);
			byte[] out = new byte[NONCE_LEN + ct.length];
			System.arraycopy(nonce, 0, out, 0, NONCE_LEN);
			System.arraycopy(ct, 0, out, NONCE_LEN, ct.length);
			return out;
		} catch (Exception e) {
			throw new IllegalStateException("control encrypt failed", e);
		}
	}

	private byte[] decrypt(byte[] blob) {
		try {
			if (blob.length <= NONCE_LEN) return null;
			byte[] nonce = new byte[NONCE_LEN];
			System.arraycopy(blob, 0, nonce, 0, NONCE_LEN);
			Cipher c = Cipher.getInstance(CIPHER);
			c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
			return c.doFinal(blob, NONCE_LEN, blob.length - NONCE_LEN);
		} catch (Exception e) {
			return null; // wrong secret / tampered / truncated — reject silently
		}
	}

	private String randomId() {
		byte[] b = new byte[6];
		rng.nextBytes(b);
		StringBuilder sb = new StringBuilder(12);
		for (byte x : b) sb.append(String.format("%02x", x));
		return sb.toString();
	}

	private static byte[] sha256(String s) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	/**
	 * Round-trip a frame through encrypt → chunk → reassemble → decrypt and log the
	 * result. Run at startup so a broken JCE setup surfaces immediately in the logs.
	 */
	public boolean selfTest() {
		if (key == null) return false;
		try {
			final String sample = "selftest|payload|with|pipes";
			List<String> lines = encode("HELLO", sample);
			StringBuilder b64 = new StringBuilder();
			for (String line : lines) {
				Optional<Chunk> ch = parseChunk(line);
				if (ch.isEmpty()) { StasisBot.LOGGER.warn("[control] self-test: chunk parse failed"); return false; }
				b64.append(ch.get().data());
			}
			Optional<Frame> f = decode(b64.toString());
			boolean ok = f.isPresent() && "HELLO".equals(f.get().type())
					&& sample.equals(f.get().payload()) && inWindow(f.get().timestamp());
			StasisBot.LOGGER.info("[control] crypto self-test ({} chunk(s)): {}", lines.size(), ok ? "OK" : "FAILED");
			return ok;
		} catch (Exception e) {
			StasisBot.LOGGER.warn("[control] crypto self-test threw: {}", e.toString());
			return false;
		}
	}
}
