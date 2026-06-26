package fr.nyuway.stasisbot.control;

import fr.nyuway.stasisbot.StasisBot;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * End-to-end crypto for the HTTP control channel. A control message is a small UTF-8
 * frame {@code v1|<epochMillis>|<type>|<payload>} sealed with <b>AES-256-GCM</b>
 * (key = SHA-256 of the shared secret, fresh 12-byte nonce, 128-bit tag) and carried
 * as a base64 body in an HTTP request/response.
 *
 * <p>Because the payload is encrypted and authenticated, the API stays confidential and
 * tamper-proof even over plain HTTP: an eavesdropper sees only ciphertext, and only a
 * holder of the secret can forge a frame. A timestamp window guards against replay. The
 * secret itself is never transmitted.
 */
public final class ControlProtocol {

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

	public boolean isReady() { return key != null; }

	/** A decrypted, parsed frame. */
	public record Frame(String type, String payload, long timestamp) {}

	/** Seal a (type, payload) into a base64 body ready to send over HTTP. */
	public String seal(String type, String payload) {
		String plain = "v1|" + System.currentTimeMillis() + "|" + type + "|" + (payload == null ? "" : payload);
		byte[] blob = encrypt(plain.getBytes(StandardCharsets.UTF_8));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(blob);
	}

	/** Open a base64 body back into a frame, or empty on any failure (wrong secret/tampered). */
	public Optional<Frame> open(String b64) {
		if (key == null || b64 == null) return Optional.empty();
		try {
			byte[] blob = Base64.getUrlDecoder().decode(b64.trim());
			byte[] plain = decrypt(blob);
			if (plain == null) return Optional.empty();
			String s = new String(plain, StandardCharsets.UTF_8);
			String[] p = s.split("\\|", 4);
			if (p.length < 4 || !"v1".equals(p[0])) return Optional.empty();
			return Optional.of(new Frame(p[2], p[3], Long.parseLong(p[1])));
		} catch (Exception e) {
			return Optional.empty();
		}
	}

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

	private static byte[] sha256(String s) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	/** Round-trip a frame through seal → open and log the result, so a broken JCE surfaces early. */
	public boolean selfTest() {
		if (key == null) return false;
		try {
			final String sample = "selftest|payload|with|pipes";
			Optional<Frame> f = open(seal("HELLO", sample));
			boolean ok = f.isPresent() && "HELLO".equals(f.get().type())
					&& sample.equals(f.get().payload()) && inWindow(f.get().timestamp());
			StasisBot.LOGGER.info("[control] crypto self-test: {}", ok ? "OK" : "FAILED");
			return ok;
		} catch (Exception e) {
			StasisBot.LOGGER.warn("[control] crypto self-test threw: {}", e.toString());
			return false;
		}
	}
}
