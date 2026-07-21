package fr.nyuway.stasisbot.control;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.nyuway.stasisbot.StasisBot;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import net.minecraft.client.MinecraftClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Bot-side control endpoint: a tiny HTTP server (JDK built-in, no deps) that accepts
 * encrypted control frames at {@code POST /ctl}. The body is a sealed {@link ControlProtocol}
 * frame; only frames that decrypt under the shared secret and fall inside the timestamp
 * window are honoured, so an unauthenticated caller can do nothing. Config writes are
 * marshaled onto the client thread (where the bot reads them), then the new state is sealed
 * back in the response.
 *
 * <p>No 2b2t chat is involved. The port is exposed by Docker; the encryption secures it.
 */
public final class ControlHttpServer {

	/** How often the watchdog checks that the control API still answers. */
	private static final long WATCHDOG_PERIOD_MILLIS = 30_000L;
	/** Connect/read timeout for the watchdog's self-probe. */
	private static final int PROBE_TIMEOUT_MILLIS = 4_000;

	private final StasisBotConfig config;
	private final ControlProtocol proto;
	private final int port;
	private final BotIntrospection intro; // null when no live world info is available
	private volatile HttpServer server;
	private volatile Thread watchdog;

	public ControlHttpServer(StasisBotConfig config) {
		this(config, null);
	}

	public ControlHttpServer(StasisBotConfig config, BotIntrospection intro) {
		this.config = config;
		this.proto = new ControlProtocol(config.controlSecret());
		this.port = config.controlPort();
		this.intro = intro;
	}

	public void start() {
		if (!proto.isReady()) {
			StasisBot.LOGGER.info("[control] HTTP API disabled (no controlSecret set)");
			return;
		}
		if (bind()) proto.selfTest();
		startWatchdog();
	}

	/** Create and start the HTTP server; true when it is listening. */
	private synchronized boolean bind() {
		try {
			HttpServer s = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
			s.createContext("/ctl", this::handle);
			s.createContext("/ping", ex -> respondText(ex, 200, "stasisbot"));
			s.setExecutor(Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "StasisBot-Control-HTTP");
				t.setDaemon(true);
				return t;
			}));
			s.start();
			server = s;
			StasisBot.LOGGER.info("[control] HTTP control API listening on 0.0.0.0:{}/ctl", port);
			return true;
		} catch (Exception e) {
			server = null;
			StasisBot.LOGGER.error("[control] failed to start HTTP API on port {}: {}", port, e.toString());
			return false;
		}
	}

	/**
	 * Watch the control API and bring it back if it ever stops answering. The bot's Minecraft
	 * client can keep running happily while this endpoint dies, which strands the operator's
	 * panel on a connection timeout with nothing in the logs. A periodic end-to-end self-probe
	 * (a real {@code GET /ping}, not just a socket check, so a hung-but-listening server is
	 * caught too) rebinds it automatically instead of needing a container restart.
	 */
	private void startWatchdog() {
		if (watchdog != null) return;
		watchdog = new Thread(() -> {
			while (!Thread.currentThread().isInterrupted()) {
				try {
					Thread.sleep(WATCHDOG_PERIOD_MILLIS);
				} catch (InterruptedException e) {
					return;
				}
				if (selfProbeOk()) continue;
				StasisBot.LOGGER.warn("[control] API stopped answering on port {} — restarting it", port);
				try {
					synchronized (this) {
						if (server != null) server.stop(0);
						server = null;
					}
				} catch (Exception ignored) {
					// a dead server may throw on stop; rebinding is what matters
				}
				bind();
			}
		}, "StasisBot-Control-Watchdog");
		watchdog.setDaemon(true);
		watchdog.start();
	}

	/** True when the endpoint answers its own {@code /ping} — a genuine end-to-end health check. */
	private boolean selfProbeOk() {
		if (server == null) return false;
		try {
			java.net.HttpURLConnection c = (java.net.HttpURLConnection)
					java.net.URI.create("http://127.0.0.1:" + port + "/ping").toURL().openConnection();
			c.setConnectTimeout(PROBE_TIMEOUT_MILLIS);
			c.setReadTimeout(PROBE_TIMEOUT_MILLIS);
			c.setRequestMethod("GET");
			int code = c.getResponseCode();
			c.disconnect();
			return code == 200;
		} catch (Exception e) {
			return false;
		}
	}

	public void stop() {
		if (watchdog != null) {
			watchdog.interrupt();
			watchdog = null;
		}
		if (server != null) server.stop(0);
	}

	private void handle(HttpExchange ex) {
		try {
			if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
				respond(ex, 405, new byte[0]);
				return;
			}
			byte[] body = ex.getRequestBody().readAllBytes();
			String b64 = new String(body, StandardCharsets.UTF_8).trim();
			Optional<ControlProtocol.Frame> f = proto.open(b64);
			if (f.isEmpty() || !proto.inWindow(f.get().timestamp())) {
				// Bad secret, tampered, or stale — reveal nothing.
				respond(ex, 403, new byte[0]);
				return;
			}
			String[] reply = process(f.get().type(), f.get().payload());
			byte[] out = proto.seal(reply[0], reply[1]).getBytes(StandardCharsets.UTF_8);
			respond(ex, 200, out);
		} catch (Exception e) {
			StasisBot.LOGGER.warn("[control] request error: {}", e.toString());
			try { respond(ex, 500, new byte[0]); } catch (Exception ignored) { }
		} finally {
			ex.close();
		}
	}

	/** Produce the (type, payload) reply for a request frame. */
	private String[] process(String type, String payload) {
		switch (type) {
			case "HELLO", "GET" -> {
				return new String[]{"STATE", ControlCore.snapshot(config)};
			}
			case "SET" -> {
				boolean ok = onClientThread(() -> ControlCore.applySet(config, payload), false);
				return ok ? new String[]{"STATE", ControlCore.snapshot(config)} : new String[]{"ERR", payload};
			}
			case "CHAMBERS" -> {
				if (intro == null) return new String[]{"CHAMBERS", ""};
				return new String[]{"CHAMBERS", onClientThread(intro::chambers, "")};
			}
			case "SETHOME" -> {
				// payload: "x y z yaw pitch" — the operator's own position + facing.
				// Coordinates MUST be parsed with Integer.parseInt, not Float.parseFloat:
				// at base-scale magnitudes (~2^24) a float only has 2-block precision and
				// rounds odd coordinates by ±1, causing the bot to home to the wrong block.
				if (intro == null || payload == null) return new String[]{"ERR", "sethome"};
				String[] hp = payload.trim().split("\\s+");
				if (hp.length != 5) return new String[]{"ERR", "sethome"};
				try {
					int hx = Integer.parseInt(hp[0]);
					int hy = Integer.parseInt(hp[1]);
					int hz = Integer.parseInt(hp[2]);
					float hyaw = Float.parseFloat(hp[3]);
					float hpitch = Float.parseFloat(hp[4]);
					boolean ok = onClientThread(() -> intro.setHome(hx, hy, hz, hyaw, hpitch), false);
					return ok ? new String[]{"OK", "home set"} : new String[]{"ERR", "sethome"};
				} catch (NumberFormatException e) {
					return new String[]{"ERR", "sethome"};
				}
			}
			case "RESCAN" -> {
				if (intro != null) onClientThread(() -> { intro.rescan(); return Boolean.TRUE; }, Boolean.FALSE);
				return new String[]{"OK", "rescan"};
			}
			case "CHATLOG" -> {
				if (intro == null) return new String[]{"CHATLOG", ""};
				return new String[]{"CHATLOG", onClientThread(intro::chatLog, "")};
			}
			case "SAY" -> {
				if (intro == null || payload == null || payload.isBlank()) return new String[]{"ERR", "say"};
				onClientThread(() -> { intro.say(payload); return Boolean.TRUE; }, Boolean.FALSE);
				return new String[]{"OK", "say"};
			}
			case "POS" -> {
				if (intro == null) return new String[]{"POS", ""};
				return new String[]{"POS", onClientThread(() -> intro.posInfo(payload), "")};
			}
			case "GOTO" -> {
				int[] c = parseCoords(payload);
				if (intro == null || c == null) return new String[]{"ERR", "goto"};
				onClientThread(() -> { intro.goTo(c[0], c[1], c[2]); return Boolean.TRUE; }, Boolean.FALSE);
				return new String[]{"OK", "goto"};
			}
			case "COME" -> {
				if (intro == null || payload == null || payload.isBlank()) return new String[]{"ERR", "come"};
				onClientThread(() -> { intro.come(payload.trim()); return Boolean.TRUE; }, Boolean.FALSE);
				return new String[]{"OK", "come"};
			}
			case "FOLLOW" -> {
				if (intro == null || payload == null || payload.isBlank()) return new String[]{"ERR", "follow"};
				onClientThread(() -> { intro.follow(payload.trim()); return Boolean.TRUE; }, Boolean.FALSE);
				return new String[]{"OK", "follow"};
			}
			case "LOGS" -> {
				return new String[]{"LOGS", fr.nyuway.stasisbot.service.LogTap.dump()};
			}
			case "STOP" -> {
				if (intro != null) onClientThread(() -> { intro.stopNav(); return Boolean.TRUE; }, Boolean.FALSE);
				return new String[]{"OK", "stop"};
			}
			case "GOHOME" -> {
				if (intro != null) onClientThread(() -> { intro.goHome(); return Boolean.TRUE; }, Boolean.FALSE);
				return new String[]{"OK", "gohome"};
			}
			case "BED" -> {
				int[] c = parseCoords(payload);
				if (intro == null || c == null) return new String[]{"ERR", "bed"};
				onClientThread(() -> { intro.useBed(c[0], c[1], c[2]); return Boolean.TRUE; }, Boolean.FALSE);
				return new String[]{"OK", "bed"};
			}
			case "SPAWN" -> {
				if (intro != null) onClientThread(() -> { intro.goSpawn(); return Boolean.TRUE; }, Boolean.FALSE);
				return new String[]{"OK", "spawn"};
			}
			case "RESTOCK" -> {
				if (intro != null) onClientThread(() -> { intro.restock(); return Boolean.TRUE; }, Boolean.FALSE);
				return new String[]{"OK", "restock"};
			}
			case "FIRE" -> {
				int[] c = parseCoords(payload);
				if (intro == null || c == null) return new String[]{"ERR", "fire"};
				onClientThread(() -> { intro.fireChamber(c[0], c[1], c[2]); return Boolean.TRUE; }, Boolean.FALSE);
				return new String[]{"OK", "fire"};
			}
			case "HOMEREQ" -> {
				if (intro == null || payload == null || payload.isBlank()) return new String[]{"ERR", "homereq"};
				onClientThread(() -> { intro.homeRequest(payload.trim()); return Boolean.TRUE; }, Boolean.FALSE);
				return new String[]{"OK", "homereq"};
			}
			case "DISCONNECT" -> {
				if (intro != null) onClientThread(() -> { intro.serverDisconnect(); return Boolean.TRUE; }, Boolean.FALSE);
				return new String[]{"OK", "disconnect"};
			}
			case "CONNECT" -> {
				if (intro != null) onClientThread(() -> { intro.serverConnect(payload); return Boolean.TRUE; }, Boolean.FALSE);
				return new String[]{"OK", "connect"};
			}
			case "PING" -> {
				return new String[]{"PONG", payload == null ? "" : payload};
			}
			default -> {
				return new String[]{"ERR", "unknown"};
			}
		}
	}

	// parseHome has been inlined into the SETHOME case to use Integer.parseInt for coordinates.

	/** Parse "x y z" into three ints, or null if malformed. */
	private static int[] parseCoords(String payload) {
		if (payload == null) return null;
		String[] p = payload.trim().split("\\s+");
		if (p.length != 3) return null;
		try {
			return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Run a task on the client thread (where the bot reads world/config), waiting briefly for the result. */
	private <T> T onClientThread(Supplier<T> task, T fallback) {
		MinecraftClient client = MinecraftClient.getInstance();
		CompletableFuture<T> fut = new CompletableFuture<>();
		client.execute(() -> {
			try { fut.complete(task.get()); }
			catch (Throwable t) { fut.complete(fallback); }
		});
		try {
			return fut.get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			return fallback;
		}
	}

	private static void respond(HttpExchange ex, int code, byte[] body) throws java.io.IOException {
		ex.getResponseHeaders().set("Content-Type", "text/plain");
		ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
		if (body.length > 0) ex.getResponseBody().write(body);
	}

	private static void respondText(HttpExchange ex, int code, String text) {
		try {
			respond(ex, code, text.getBytes(StandardCharsets.UTF_8));
		} catch (Exception ignored) {
		} finally {
			ex.close();
		}
	}
}
