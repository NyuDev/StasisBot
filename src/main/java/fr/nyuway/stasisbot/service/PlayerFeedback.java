package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.StasisBot;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.i18n.Messages;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.Random;

/**
 * All outgoing player-facing communication in one place: self HUD notes, debug
 * lines and localised whispers. Keeping this here means the rest of the service
 * never has to know <em>how</em> a message is formatted, localised or delivered.
 */
final class PlayerFeedback {

	private static final Random RANDOM = new Random();

	/** 6 random lowercase letters — appended to whispers to bypass server antispam. */
	static String randomSuffix() {
		char[] buf = new char[6];
		for (int i = 0; i < 6; i++) buf[i] = (char) ('a' + RANDOM.nextInt(26));
		return new String(buf);
	}

	private final MinecraftClient client;
	private final StasisBotConfig config;

	PlayerFeedback(MinecraftClient client, StasisBotConfig config) {
		this.client = client;
		this.config = config;
	}

	/** Print a message to the bot's own chat/HUD (never sent to the server). */
	void notifySelf(String message) {
		if (client.player != null) {
			client.player.sendMessage(Text.literal(message), false);
		}
	}

	/** Verbose log + optional in-game note, only when debug mode is enabled. */
	void debug(String message) {
		if (!config.debug()) return;
		StasisBot.LOGGER.info("[StasisBot] {}", message);
		notifySelf("§8[SB] §7" + message);
	}

	/** Localised player feedback (suppressed when dmFeedback is off). */
	void whisper(String player, Messages.Key key, Object... args) {
		if (!config.dmFeedback()) return;
		sendWhisper(player, Messages.get(config.language(), key, args));
	}

	/** Localised reply that always sends (e.g. master-command confirmations). */
	void reply(String player, Messages.Key key, Object... args) {
		sendWhisper(player, Messages.get(config.language(), key, args));
	}

	/** Raw text reply that always sends (e.g. watch-command confirmations, member notifications). */
	void replyText(String player, String text) {
		sendWhisper(player, text);
	}

	private void sendWhisper(String player, String message) {
		if (client.player == null || client.player.networkHandler == null) return;
		if (player == null || player.isBlank()) return;
		String text = config.appendRandomChars() ? message + " " + randomSuffix() : message;
		client.player.networkHandler.sendChatCommand(config.whisperCommand() + " " + player + " " + text);
	}
}
