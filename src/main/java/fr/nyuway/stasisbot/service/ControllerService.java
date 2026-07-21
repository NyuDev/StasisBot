package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.StasisBot;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.control.ControlProtocol;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

	/**
	 * Hard floor between two auto-refreshes, whatever age the caller asks for. Opening and
	 * closing the panel repeatedly must never turn into a request storm against the bot.
	 */
	private static final long MIN_AUTO_REFRESH_MILLIS = 2_000L;

	private volatile ControlProtocol proto;
	private volatile long lastStateAt = 0L;       // when a STATE frame last landed (cache age)
	private volatile long lastAutoRefreshAt = 0L; // when an auto-refresh was last sent (anti-spam)
	private final Map<String, String> state = new LinkedHashMap<>();
	private volatile List<RemoteChamber> chambers = new ArrayList<>();
	private volatile Status status = Status.IDLE;
	private volatile String info = "";
	private volatile String chatLog = "";
	private volatile String logs = "";
	private volatile String botPos = "";   // "x y z" — only shown when the operator reveals it
	private volatile int distance = -1;     // blocks between the bot and the operator, -1 = unknown
	private volatile boolean following = false; // bot currently following (Baritone)
	private volatile boolean atHome = false;    // bot standing on its pinned home block
	private volatile boolean watcherAtHome = false; // the operator is standing on the bot's home block

	/** One chamber the remote bot detects: its sign label, position text, and pearl state. */
	public record RemoteChamber(String label, String pos, char state) {}

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

	/** The remote bot's detected chambers from the last fetch (never null). */
	public List<RemoteChamber> chambers() {
		return chambers;
	}

	public String chatLog() { return chatLog; }
	public String logs() { return logs; }
	public String botPos() { return botPos; }
	public int distance() { return distance; }
	public boolean following() { return following; }
	public boolean atHome() { return atHome; }
	public boolean watcherAtHome() { return watcherAtHome; }

	// --- bot control actions ---------------------------------------------------

	public void requestChatLog() { if (ready()) request("CHATLOG", ""); }
	public void requestLogs() { if (ready()) request("LOGS", ""); }
	public void requestPos(String watcher) { if (ready()) request("POS", watcher == null ? "" : watcher); }
	public void say(String text) { if (ready() && text != null && !text.isBlank()) request("SAY", text); }
	public void gotoCoords(int x, int y, int z) { if (ready()) request("GOTO", x + " " + y + " " + z); }
	public void come(String player) { if (ready() && player != null) request("COME", player); }
	public void follow(String player) { if (ready() && player != null && !player.isBlank()) request("FOLLOW", player); }
	public void stopNav() { if (ready()) request("STOP", ""); }
	public void goHome() { if (ready()) request("GOHOME", ""); }
	public void goSpawn() { if (ready()) request("SPAWN", ""); }
	public void restock() { if (ready()) request("RESTOCK", ""); }
	public void useBed(int x, int y, int z) { if (ready()) request("BED", x + " " + y + " " + z); }
	public void fireChamber(int x, int y, int z) { if (ready()) request("FIRE", x + " " + y + " " + z); }
	public void homeRequest(String player) { if (ready() && player != null && !player.isBlank()) request("HOMEREQ", player); }
	public void serverDisconnect() { if (ready()) request("DISCONNECT", ""); }
	public void serverConnect(String hostPort) { if (ready()) request("CONNECT", hostPort == null ? "" : hostPort); }

	/** Set the bot's home to a chosen position + facing (the operator's). */
	public void setHome(int x, int y, int z, float yaw, float pitch) {
		if (ready()) request("SETHOME", x + " " + y + " " + z + " " + yaw + " " + pitch);
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
		request("CHAMBERS", "");
	}

	public void disconnect() {
		status = Status.IDLE;
		info = "disconnected";
		state.clear();
		lastStateAt = 0L; // nothing cached any more — the next open must re-sync
		chambers = new ArrayList<>();
	}

	public void set(String key, String value) {
		if (!ready()) return;
		request("SET", key + " " + value);
	}

	public void refresh() {
		if (!ready()) return;
		lastAutoRefreshAt = System.currentTimeMillis();
		request("GET", "");
		request("CHAMBERS", "");
	}

	/**
	 * Pull fresh state from the bot, but only when what we hold is older than
	 * {@code maxAgeMillis}. Opening the panel calls this so the view is never stale, while
	 * re-opening it moments later just reuses the cached snapshot. A second, absolute floor
	 * ({@link #MIN_AUTO_REFRESH_MILLIS}) guards against menu open/close spam even when the
	 * cache is genuinely old, so the bot can't be hammered.
	 */
	public void refreshIfStale(long maxAgeMillis) {
		if (!ready()) return;
		long now = System.currentTimeMillis();
		if (now - lastStateAt < maxAgeMillis) return;            // cache still fresh enough
		if (now - lastAutoRefreshAt < MIN_AUTO_REFRESH_MILLIS) return; // one just went out
		refresh();
	}

	/** How stale the cached snapshot is, in ms ({@link Long#MAX_VALUE} when never synced). */
	public long stateAgeMillis() {
		return lastStateAt == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - lastStateAt;
	}

	/** Ask the bot for its detected chambers (used for the live list). */
	public void requestChambers() {
		if (ready()) request("CHAMBERS", "");
	}

	/** Tell the bot to re-scan, then refresh the chamber list. */
	public void rescan() {
		if (!ready()) return;
		request("RESCAN", "");
		request("CHAMBERS", "");
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
				StasisBot.LOGGER.warn("[control] {} failed: {}", type, e.toString());
			}
		});
	}

	private void onFrame(String type, String payload) {
		switch (type) {
			case "STATE" -> { parseState(payload); status = Status.SYNCED; info = "synced"; }
			case "CHAMBERS" -> { parseChambers(payload); status = Status.SYNCED; }
			case "CHATLOG" -> { chatLog = payload == null ? "" : payload; status = Status.SYNCED; }
			case "LOGS" -> { logs = payload == null ? "" : payload; status = Status.SYNCED; }
			case "POS" -> { parsePos(payload); status = Status.SYNCED; }
			case "OK" -> info = "applied: " + payload;
			case "ERR" -> info = "bot rejected: " + payload;
			case "PONG" -> info = "pong";
			default -> { }
		}
	}

	private void parsePos(String payload) {
		if (payload == null || payload.isBlank()) { botPos = ""; distance = -1; following = false; atHome = false; return; }
		String[] f = payload.split("\\|");
		botPos = f.length > 0 ? f[0].trim() : "";
		try { distance = f.length > 1 ? Integer.parseInt(f[1].trim()) : -1; }
		catch (NumberFormatException e) { distance = -1; }
		following = f.length > 2 && f[2].trim().equals("1");
		atHome = f.length > 3 && f[3].trim().equals("1");
		watcherAtHome = f.length > 4 && f[4].trim().equals("1");
	}

	private void parseChambers(String payload) {
		List<RemoteChamber> list = new ArrayList<>();
		if (payload != null && !payload.isBlank()) {
			for (String line : payload.split("\n")) {
				String[] f = line.split("\\|", 3);
				if (f.length == 3 && !f[2].isEmpty()) {
					list.add(new RemoteChamber(f[0], f[1], f[2].charAt(0)));
				}
			}
		}
		chambers = list;
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
		lastStateAt = System.currentTimeMillis(); // the cache is now fresh

		// Sync the global watch list from the bot so the WatchScreen shows current state.
		String watched = fresh.get("watched");
		if (watched != null) {
			List<String> botList = watched.isEmpty() ? List.of() : List.of(watched.split(","));
			config.syncWatchedPlayersFromRemote(botList);
		}
	}

	private String endpointUrl() {
		String base = config.controlEndpoint().trim();
		if (!base.startsWith("http://") && !base.startsWith("https://")) base = "http://" + base;
		if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
		return base + "/ctl";
	}
}
