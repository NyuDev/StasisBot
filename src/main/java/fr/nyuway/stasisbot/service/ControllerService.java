package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.StasisBot;
import fr.nyuway.stasisbot.chat.ChatMessageParser;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.control.ControlChannel;
import fr.nyuway.stasisbot.control.ControlProtocol;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Controller-side endpoint: runs on the operator's own client (when
 * {@code controllerMode} is on). Drives a headless bot over the encrypted whisper
 * channel — sends {@code HELLO}/{@code GET}/{@code SET}, receives
 * {@code WELCOME}/{@code STATE}/{@code OK}/{@code ERR} — and exposes the last synced
 * state to {@link fr.nyuway.stasisbot.gui.ControllerScreen}.
 */
public final class ControllerService {

	public enum Status { IDLE, CONNECTING, SYNCED, ERROR }

	private final MinecraftClient client;
	private final StasisBotConfig config;
	private ControlProtocol proto;
	private ControlChannel channel;

	private final Map<String, String> state = new LinkedHashMap<>();
	private volatile Status status = Status.IDLE;
	private volatile String info = "";

	public ControllerService(MinecraftClient client, StasisBotConfig config) {
		this.client = client;
		this.config = config;
		rebuild();
	}

	/** Rebuild the crypto from the current secret (call after the secret changes in the GUI). */
	public void rebuild() {
		this.proto = new ControlProtocol(config.controlSecret());
		this.channel = new ControlChannel(proto, this::sendLine, this::onFrame);
	}

	public void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) handleRaw(message.getString());
		});
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) ->
				handleRaw(message.getString()));
		if (proto.isReady()) proto.selfTest();
		StasisBot.LOGGER.info("[control] controller endpoint ready (target bot: {})",
				config.controlBotName().isBlank() ? "NOT SET" : config.controlBotName());
	}

	public boolean isReady() {
		return proto.isReady() && !config.controlBotName().isBlank();
	}

	public Status status() { return status; }
	public String info() { return info; }
	public Map<String, String> state() { return state; }

	/** A boolean setting from the last snapshot ({@code def} when unknown). */
	public boolean flag(String key, boolean def) {
		String v = state.get(key);
		if (v == null) return def;
		return v.equalsIgnoreCase("on") || v.equalsIgnoreCase("true");
	}

	public String text(String key, String def) {
		return state.getOrDefault(key, def);
	}

	/** A short fingerprint of the synced state, so the GUI can detect changes and refresh. */
	public String signature() {
		return status + "|" + info + "|" + state;
	}

	public void connect() {
		rebuild();
		if (!isReady()) {
			status = Status.ERROR;
			info = "Set a secret AND the bot name first";
			return;
		}
		status = Status.CONNECTING;
		info = "Connecting… (you and the bot must both be on the server)";
		channel.send("HELLO", "");
	}

	public void set(String key, String value) {
		if (!isReady()) return;
		channel.send("SET", key + " " + value);
	}

	public void refresh() {
		if (isReady()) channel.send("GET", "");
	}

	private void handleRaw(String raw) {
		if (proto == null || !proto.isReady()) return;
		var parsed = ChatMessageParser.fromRaw(raw);
		if (parsed.isEmpty()) return;
		String sender = parsed.get().sender();
		String body = parsed.get().body();
		if (!ControlProtocol.isControlLine(body)) return;
		if (sender == null || !sender.equalsIgnoreCase(config.controlBotName())) return;
		channel.onLine(body);
	}

	private void onFrame(String type, String payload) {
		switch (type) {
			case "WELCOME" -> {
				status = Status.SYNCED;
				String name = payload != null && payload.contains(";") ? payload.substring(payload.indexOf(';') + 1) : payload;
				info = "Synced with " + name;
			}
			case "STATE" -> {
				parseState(payload);
				status = Status.SYNCED;
				if (info.startsWith("Connecting")) info = "Synced";
			}
			case "OK" -> info = "Applied: " + payload;
			case "ERR" -> info = "Bot rejected: " + payload;
			case "PONG" -> info = "pong";
			default -> { /* ignore */ }
		}
	}

	private void parseState(String payload) {
		if (payload == null) return;
		state.clear();
		for (String pair : payload.split(";")) {
			int eq = pair.indexOf('=');
			if (eq > 0) state.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
		}
	}

	public void tick() {
		if (proto != null && proto.isReady()) channel.tick();
	}

	private void sendLine(String line) {
		if (client.player == null || client.player.networkHandler == null) return;
		String target = config.controlBotName();
		if (target == null || target.isBlank()) return;
		client.player.networkHandler.sendChatCommand(config.whisperCommand() + " " + target + " " + line);
	}
}
