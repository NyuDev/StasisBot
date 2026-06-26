package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.StasisBot;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.control.ControlProtocol;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Controller-side client of the bot's HTTP control API. Runs on the operator's own client
 * (when {@code controllerMode} is on) and is driven by the unified {@code StasisMonitorScreen}.
 * Each action — connect, refresh, toggle a setting — is one encrypted HTTP request/response
 * to the bot's {@code /ctl} endpoint, off the render thread; the latest synced state feeds
 * the menu. No 2b2t chat is involved.
 */
public final class ControllerService {

	public enum Status { IDLE, CONNECTING, SYNCED, ERROR }

	private final StasisBotConfig config;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8))
			.build();
	private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "StasisBot-Controller");
		t.setDaemon(true);
		return t;
	});

	private volatile ControlProtocol proto;
	private final Map<String, String> state = new LinkedHashMap<>();
	private volatile Status status = Status.IDLE;
	private volatile String info = "";

	public ControllerService(StasisBotConfig config) {
		this.config = config;
		this.proto = new ControlProtocol(config.controlSecret());
	}

	public void register() {
		StasisBot.LOGGER.info("[control] controller ready (endpoint: {})",
				config.controlEndpoint().isBlank() ? "NOT SET" : config.controlEndpoint());
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

	private boolean ready() {
		return proto != null && proto.isReady() && !config.controlEndpoint().isBlank();
	}

	/** Connect (or re-connect): rebuild crypto from the current secret, then HELLO. */
	public void connect() {
		this.proto = new ControlProtocol(config.controlSecret());
		if (!ready()) {
			status = Status.ERROR;
			info = "Set an endpoint AND a secret first";
			return;
		}
		status = Status.CONNECTING;
		info = "Connecting to " + config.controlEndpoint() + " …";
		request("HELLO", "");
	}

	public void disconnect() {
		status = Status.IDLE;
		info = "disconnected";
		state.clear();
	}

	public void set(String key, String value) {
		if (!ready()) return;
		request("SET", key + " " + value);
	}

	public void refresh() {
		if (ready()) request("GET", "");
	}

	/** Fire one encrypted request off the render thread and fold the reply into the state. */
	private void request(String type, String payload) {
		final ControlProtocol p = this.proto;
		final String url = endpointUrl();
		final String body = p.seal(type, payload);
		io.execute(() -> {
			try {
				HttpRequest req = HttpRequest.newBuilder(URI.create(url))
						.timeout(Duration.ofSeconds(8))
						.header("Content-Type", "text/plain")
						.POST(HttpRequest.BodyPublishers.ofString(body))
						.build();
				HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
				if (resp.statusCode() == 403) { status = Status.ERROR; info = "rejected — wrong secret?"; return; }
				if (resp.statusCode() != 200) { status = Status.ERROR; info = "HTTP " + resp.statusCode(); return; }
				Optional<ControlProtocol.Frame> f = p.open(resp.body());
				if (f.isEmpty()) { status = Status.ERROR; info = "decrypt failed — secret mismatch?"; return; }
				onFrame(f.get().type(), f.get().payload());
			} catch (Exception e) {
				status = Status.ERROR;
				info = "unreachable: " + e.getClass().getSimpleName();
				StasisBot.LOGGER.warn("[control] request failed: {}", e.toString());
			}
		});
	}

	private void onFrame(String type, String payload) {
		switch (type) {
			case "STATE" -> { parseState(payload); status = Status.SYNCED; info = "synced"; }
			case "OK" -> info = "applied: " + payload;
			case "ERR" -> info = "bot rejected: " + payload;
			case "PONG" -> info = "pong";
			default -> { }
		}
	}

	private void parseState(String payload) {
		if (payload == null) return;
		Map<String, String> fresh = new LinkedHashMap<>();
		for (String pair : payload.split(";")) {
			int eq = pair.indexOf('=');
			if (eq > 0) fresh.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
		}
		state.clear();
		state.putAll(fresh);
	}

	private String endpointUrl() {
		String base = config.controlEndpoint().trim();
		if (!base.startsWith("http://") && !base.startsWith("https://")) base = "http://" + base;
		if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
		return base + "/ctl";
	}
}
