package fr.nyuway.stasisbot.control;

import fr.nyuway.stasisbot.config.MasterCommands;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.i18n.Messages;

/**
 * The request → response logic of the control API, independent of transport. The bot's
 * HTTP server calls these: a snapshot of the remotely-togglable settings, and applying a
 * {@code SET <key> <value>} by reusing the existing {@link MasterCommands} grammar.
 */
public final class ControlCore {

	private ControlCore() {
	}

	/** Compact {@code key=on/off;…} snapshot of the settings the controller GUI shows. */
	public static String snapshot(StasisBotConfig config) {
		StringBuilder sb = new StringBuilder();
		kv(sb, "drop", config.dropPearlForPlayer());
		kv(sb, "reopen", config.reopenTrigger());
		kv(sb, "return", config.returnHome());
		kv(sb, "death", config.returnHomeOnDeath());
		kv(sb, "online", config.requireOnline());
		kv(sb, "walk", config.autoWalk());
		kv(sb, "members", config.baseMembersControl());
		kv(sb, "reqmember", config.requireBaseMemberForHome());
		kv(sb, "skip", config.skipIfPresent());
		kv(sb, "lockhome", config.lockAtHome());
		kv(sb, "debug", config.debug());
		kv(sb, "baritone", config.useBaritone());
		kv(sb, "discord", config.discordEnabled());
		kv(sb, "embeds", config.discordUseEmbeds());
		kv(sb, "alert", config.alertOutsiders());
		kv(sb, "chatlog", config.logAllChat());
		kv(sb, "appendchars", config.appendRandomChars());
		sb.append("lang=").append(config.language()).append(';');
		// Webhook URL and watch list last — avoid their content (/ and ,) mid-list.
		sb.append("discordwebhook=").append(config.discordWebhookUrl()).append(';');
		sb.append("watched=").append(String.join(",", config.watchedPlayers()));
		return sb.toString();
	}

	/** Apply a {@code SET <key> <value>} by reusing the master-command grammar; true on success. */
	public static boolean applySet(StasisBotConfig config, String payload) {
		if (payload == null || payload.isBlank()) return false;
		String[] kv = payload.trim().split("\\s+", 2);
		String key = kv[0];
		String value = kv.length > 1 ? kv[1] : "";
		MasterCommands.Result r = MasterCommands.apply(config, config.commandPrefix() + " " + key + " " + value);
		return r.handled()
				&& r.key() != Messages.Key.CFG_UNKNOWN
				&& r.key() != Messages.Key.CFG_BADVALUE;
	}

	private static void kv(StringBuilder sb, String k, boolean v) {
		sb.append(k).append('=').append(v ? "on" : "off").append(';');
	}
}
