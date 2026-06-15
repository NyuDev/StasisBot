package fr.nyuway.stasisbot.chat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a (sender, body) pair from a raw chat line. 2b2t reformats chat, so
 * when the client doesn't give us a verified sender we fall back to parsing
 * common shapes like {@code <Name> msg}, {@code Name: msg}, {@code [rank] Name » msg},
 * and also whispers: {@code Name whispers to you: msg}.
 */
public final class ChatMessageParser {

	/** Normal chat: &lt;Name&gt; body, Name: body, [rank] Name » body, etc. */
	private static final Pattern FALLBACK =
			Pattern.compile("^[\\[<]?\\s*(?:[^>\\]:»]*?\\s)?([A-Za-z0-9_]{2,16})\\s*[>\\]:»]+\\s*(.*)$");

	/** Whispers received: "Name whispers to you: body" */
	private static final Pattern WHISPER_IN =
			Pattern.compile("^([A-Za-z0-9_]{2,16})\\s+whispers\\s+to\\s+you:\\s*(.*)$",
					java.util.regex.Pattern.CASE_INSENSITIVE);

	public record ParsedMessage(String sender, String body) {}

	private ChatMessageParser() {
	}

	/** Build a message from an already-verified sender. */
	public static Optional<ParsedMessage> withKnownSender(String sender, String body) {
		if (sender == null || sender.isBlank() || body == null) return Optional.empty();
		return Optional.of(new ParsedMessage(sender, body));
	}

	/** Recover the sender from the raw text when the event didn't supply one. */
	public static Optional<ParsedMessage> fromRaw(String raw) {
		if (raw == null) return Optional.empty();
		// Try whisper first ("Name whispers to you: body") — highest priority.
		Matcher w = WHISPER_IN.matcher(raw.trim());
		if (w.matches()) return Optional.of(new ParsedMessage(w.group(1), w.group(2)));
		Matcher m = FALLBACK.matcher(raw.trim());
		if (!m.matches()) return Optional.empty();
		return Optional.of(new ParsedMessage(m.group(1), m.group(2)));
	}
}
