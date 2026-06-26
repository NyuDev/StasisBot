package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.StasisBot;
import fr.nyuway.stasisbot.chat.ChatMessageParser;
import fr.nyuway.stasisbot.config.MasterCommands;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.control.ControlChannel;
import fr.nyuway.stasisbot.control.ControlInbox;
import fr.nyuway.stasisbot.control.ControlProtocol;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;

/**
 * Bot-side endpoint of the encrypted remote-control channel. Active only when a
 * {@code controlSecret} <em>and</em> a {@code master} are set (two factors: the secret
 * authenticates the message, the master name authorises the sender).
 *
 * <p>It listens for {@code !ctl} whispers, accepts only those from the master that
 * decrypt under the shared secret, applies configuration changes through the existing
 * {@link MasterCommands} grammar, and replies with a fresh state snapshot so the
 * controller's GUI stays in sync. Replies are whispered back over the same channel.
 */
public final class ControlService {

	private final MinecraftClient client;
	private final StasisBotConfig config;
	private final ControlProtocol proto;
	private final ControlChannel channel;
	private volatile String peer; // master IGN to reply to (set on first accepted frame)

	public ControlService(MinecraftClient client, StasisBotConfig config) {
		this.client = client;
		this.config = config;
		this.proto = new ControlProtocol(config.controlSecret());
		this.channel = new ControlChannel(proto, this::sendLine, this::onFrame, "bot");
	}

	/** Remote control is live only with both a secret and a master configured. */
	public boolean isEnabled() {
		return proto.isReady() && !config.master().isBlank();
	}

	public void register() {
		// Primary, cancel-proof receive path: a packet-level mixin feeds raw lines here.
		ControlInbox.setSink(this::handleRaw);
		// Also keep the Fabric events as a fallback (deduped by message id downstream).
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) handleRaw(message.getString());
		});
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) ->
				handleRaw(message.getString()));
		if (proto.isReady()) {
			proto.selfTest();
			StasisBot.LOGGER.info("[control] bot endpoint armed (master gate: {})",
					config.master().isBlank() ? "NOT SET — control disabled" : config.master());
		}
	}

	private void handleRaw(String raw) {
		if (!isEnabled()) return;
		var parsed = ChatMessageParser.fromRaw(raw);
		if (parsed.isEmpty()) return;
		String sender = parsed.get().sender();
		String body = parsed.get().body();
		if (!ControlProtocol.isControlLine(body)) return;
		// Defence in depth: only the configured master may drive the bot, on top of the secret.
		if (sender == null || !sender.equalsIgnoreCase(config.master())) {
			StasisBot.LOGGER.warn("[control/bot] ignored control line from '{}' (master is '{}')", sender, config.master());
			return;
		}
		StasisBot.LOGGER.info("[control/bot] rx control whisper from master '{}'", sender);
		peer = sender;
		channel.onLine(body);
	}

	private void onFrame(String type, String payload) {
		switch (type) {
			case "HELLO" -> {
				channel.send("WELCOME", welcome());
				channel.send("STATE", snapshot());
			}
			case "GET" -> channel.send("STATE", snapshot());
			case "SET" -> applySet(payload);
			case "PING" -> channel.send("PONG", payload == null ? "" : payload);
			default -> { /* unknown verb — ignore */ }
		}
	}

	/** Apply a {@code SET <key> <value>} by reusing the master-command grammar. */
	private void applySet(String payload) {
		if (payload == null || payload.isBlank()) { channel.send("ERR", "empty"); return; }
		String[] kv = payload.trim().split("\\s+", 2);
		String key = kv[0];
		String value = kv.length > 1 ? kv[1] : "";
		MasterCommands.Result r = MasterCommands.apply(config, config.commandPrefix() + " " + key + " " + value);
		if (r.handled() && r.key() != fr.nyuway.stasisbot.i18n.Messages.Key.CFG_UNKNOWN
				&& r.key() != fr.nyuway.stasisbot.i18n.Messages.Key.CFG_BADVALUE) {
			channel.send("OK", key);
			channel.send("STATE", snapshot());
		} else {
			channel.send("ERR", key);
		}
	}

	/** Compact {@code key=on/off;…} snapshot of the remotely-togglable settings, for the GUI. */
	private String snapshot() {
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
		kv(sb, "debug", config.debug());
		kv(sb, "baritone", config.useBaritone());
		kv(sb, "discord", config.discordEnabled());
		kv(sb, "embeds", config.discordUseEmbeds());
		kv(sb, "alert", config.alertOutsiders());
		kv(sb, "chatlog", config.logAllChat());
		kv(sb, "appendchars", config.appendRandomChars());
		sb.append("lang=").append(config.language());
		return sb.toString();
	}

	private static void kv(StringBuilder sb, String k, boolean v) {
		sb.append(k).append('=').append(v ? "on" : "off").append(';');
	}

	/** WELCOME payload: a tag plus the bot's in-game name, for the controller's status line. */
	private String welcome() {
		String name = client.player != null ? client.player.getGameProfile().name() : "?";
		return "stasisbot;" + name;
	}

	public void tick() {
		if (isEnabled()) channel.tick();
	}

	private void sendLine(String line) {
		if (client.player == null || client.player.networkHandler == null) {
			StasisBot.LOGGER.warn("[control/bot] cannot send reply — no player/network yet");
			return;
		}
		String target = peer != null ? peer : config.master();
		if (target == null || target.isBlank()) return;
		StasisBot.LOGGER.info("[control/bot] sending reply via /{} to {}", config.whisperCommand(), target);
		client.player.networkHandler.sendChatCommand(config.whisperCommand() + " " + target + " " + line);
	}
}
