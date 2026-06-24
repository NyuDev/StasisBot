package fr.nyuway.stasisbot.chat;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a (sender, body) pair from a raw chat line. 2b2t reformats chat and
 * routes private messages through generic game messages (not the verified chat
 * channel), so when the client doesn't hand us a sender we parse common shapes
 * ourselves: {@code <Name> msg}, {@code Name: msg}, {@code [rank] Name » msg}, and
 * DMs in several flavours — vanilla {@code Name whispers to you: msg}, the shorter
 * 2b2t form {@code Name whispers: msg}, and Essentials-style {@code [Name -> me] msg}
 * / {@code Name -> me: msg}.
 */
public final class ChatMessageParser {

	/** Normal chat: &lt;Name&gt; body, Name: body, [rank] Name » body, etc. */
	private static final Pattern FALLBACK =
			Pattern.compile("^[\\[<]?\\s*(?:[^>\\]:»]*?\\s)?([A-Za-z0-9_]{2,16})\\s*[>\\]:»]+\\s*(.*)$");

	/**
	 * Whispers received. Covers vanilla "Name whispers to you: body" and the shorter
	 * 2b2t form "Name whispers: body", each optionally carrying a leading "[rank] "
	 * tag. The " to you" segment is optional, which is the key difference on 2b2t.
	 */
	private static final Pattern WHISPER_IN =
			Pattern.compile("^(?:\\[[^\\]]*\\]\\s*)?([A-Za-z0-9_]{2,16})\\s+whispers(?:\\s+to\\s+you)?:\\s*(.*)$",
					Pattern.CASE_INSENSITIVE);

	/**
	 * Arrow-style DMs used by Essentials-like plugins: "Name -> me: body",
	 * "[Name -> me] body", "Name » me: body". Recipient marker is "me" or "you".
	 */
	private static final Pattern WHISPER_ARROW =
			Pattern.compile("^\\[?\\s*([A-Za-z0-9_]{2,16})\\s*(?:->|»|→)\\s*(?:me|you)\\s*[\\]:]\\s*(.*)$",
					Pattern.CASE_INSENSITIVE);

	/**
	 * Verbs {@link #FALLBACK} would otherwise mis-capture as the sender when an
	 * unrecognised whisper shape slips through (e.g. "Name whispers: hi" would yield
	 * "whispers"). Such a parse is rejected rather than acted on.
	 */
	private static final Set<String> NON_NAMES =
			Set.of("whispers", "whisper", "tells", "tell", "from", "msg", "pm");

	/** Parsed line; {@code dm} is true when it came in as a private message/whisper. */
	public record ParsedMessage(String sender, String body, boolean dm) {}

	private ChatMessageParser() {
	}

	/** Build a message from an already-verified sender (the verified chat channel = public). */
	public static Optional<ParsedMessage> withKnownSender(String sender, String body) {
		if (sender == null || sender.isBlank() || body == null) return Optional.empty();
		return Optional.of(new ParsedMessage(sender, body, false));
	}

	/** Recover the sender from the raw text when the event didn't supply one. */
	public static Optional<ParsedMessage> fromRaw(String raw) {
		if (raw == null) return Optional.empty();
		String text = raw.trim();
		// Whispers first — highest priority, and the shapes most likely to be mis-read
		// by the generic fallback below. Both whisper shapes are private messages.
		Matcher w = WHISPER_IN.matcher(text);
		if (w.matches()) return Optional.of(new ParsedMessage(w.group(1), w.group(2), true));
		Matcher a = WHISPER_ARROW.matcher(text);
		if (a.matches()) return Optional.of(new ParsedMessage(a.group(1), a.group(2), true));
		Matcher m = FALLBACK.matcher(text);
		if (!m.matches()) return Optional.empty();
		// Guard: don't let the fallback turn a whisper verb into a fake sender when an
		// unknown DM shape slipped past the patterns above.
		String sender = m.group(1);
		if (NON_NAMES.contains(sender.toLowerCase(Locale.ROOT))) return Optional.empty();
		return Optional.of(new ParsedMessage(sender, m.group(2), false));
	}
}
