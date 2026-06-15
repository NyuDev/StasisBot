package fr.nyuway.stasisbot.config;

import fr.nyuway.stasisbot.i18n.Messages;

import java.util.Locale;

/**
 * Parses and applies the master's chat/DM configuration commands, e.g.
 * {@code !sb drop off}, {@code !sb lang fr}, {@code !sb returnpos 100 64 -200}.
 *
 * <p>The caller decides who is allowed to run these (the configured master) and
 * how the {@link Result} reply is delivered; this class only knows the grammar.
 */
public final class MasterCommands {

	/** A localised reply to send back to the master, or a no-op when not a command. */
	public record Result(boolean handled, Messages.Key key, Object[] args) {
		static Result of(Messages.Key key, Object... args) { return new Result(true, key, args); }
		static final Result IGNORED = new Result(false, null, new Object[0]);
	}

	private MasterCommands() {
	}

	/** True if {@code body} begins with the configured command prefix. */
	public static boolean looksLikeCommand(StasisBotConfig config, String body) {
		if (body == null) return false;
		String b = body.trim().toLowerCase(Locale.ROOT);
		return b.equals(config.commandPrefix().toLowerCase(Locale.ROOT))
				|| b.startsWith(config.commandPrefix().toLowerCase(Locale.ROOT) + " ");
	}

	/** Apply a command; returns the reply to whisper back (or IGNORED). */
	public static Result apply(StasisBotConfig config, String body) {
		if (!looksLikeCommand(config, body)) return Result.IGNORED;

		String[] parts = body.trim().split("\\s+");
		if (parts.length < 2) return Result.of(Messages.Key.CFG_HELP);

		String key = parts[1].toLowerCase(Locale.ROOT);
		String value = parts.length >= 3 ? parts[2] : null;

		switch (key) {
			case "help" -> { return Result.of(Messages.Key.CFG_HELP); }
			case "lang" -> {
				if (value == null) return bad(key);
				config.setLanguage(value);
				return set("lang", config.language());
			}
			case "drop" -> { Boolean v = bool(value); if (v == null) return bad(key); config.setDropPearlForPlayer(v); return set("drop", on(v)); }
			case "reopen" -> { Boolean v = bool(value); if (v == null) return bad(key); config.setReopenTrigger(v); return set("reopen", on(v)); }
			case "return" -> { Boolean v = bool(value); if (v == null) return bad(key); config.setReturnHome(v); return set("return", on(v)); }
			case "walk" -> { Boolean v = bool(value); if (v == null) return bad(key); config.setAutoWalk(v); return set("walk", on(v)); }
			case "online" -> { Boolean v = bool(value); if (v == null) return bad(key); config.setRequireOnline(v); return set("online", on(v)); }
			case "dm" -> { Boolean v = bool(value); if (v == null) return bad(key); config.setDmFeedback(v); return set("dm", on(v)); }
			case "trigger" -> { if (value == null) return bad(key); config.setTriggerWord(value); return set("trigger", config.triggerWord()); }
			case "whisper" -> { if (value == null) return bad(key); config.setWhisperCommand(value); return set("whisper", config.whisperCommand()); }
			case "master" -> { if (value == null) return bad(key); config.setMaster(value); return set("master", config.master()); }
			case "returnpos" -> { return returnPos(config, parts); }
			default -> { return Result.of(Messages.Key.CFG_UNKNOWN, key); }
		}
	}

	private static Result returnPos(StasisBotConfig config, String[] parts) {
		if (parts.length >= 3 && parts[2].equalsIgnoreCase("clear")) {
			config.setReturnPos(null, null, null);
			return set("returnpos", "start");
		}
		if (parts.length < 5) return bad("returnpos");
		try {
			int x = Integer.parseInt(parts[2]);
			int y = Integer.parseInt(parts[3]);
			int z = Integer.parseInt(parts[4]);
			config.setReturnPos(x, y, z);
			return set("returnpos", x + " " + y + " " + z);
		} catch (NumberFormatException e) {
			return bad("returnpos");
		}
	}

	private static Result set(String name, String value) { return Result.of(Messages.Key.CFG_SET, name, value); }
	private static Result bad(String key) { return Result.of(Messages.Key.CFG_BADVALUE, key); }
	private static String on(boolean v) { return v ? "on" : "off"; }

	private static Boolean bool(String v) {
		if (v == null) return null;
		return switch (v.toLowerCase(Locale.ROOT)) {
			case "on", "true", "yes", "1", "enable", "enabled" -> Boolean.TRUE;
			case "off", "false", "no", "0", "disable", "disabled" -> Boolean.FALSE;
			default -> null;
		};
	}
}
