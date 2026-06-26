package fr.nyuway.stasisbot.control;

import fr.nyuway.stasisbot.StasisBot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Stateful, per-side transport for the encrypted control channel. Wraps a
 * {@link ControlProtocol} with three concerns the raw crypto layer doesn't own:
 *
 * <ul>
 *   <li><b>Outbound:</b> a queue drained one whisper at a time, spaced out, so a
 *       multi-chunk message never trips the server's chat-rate kick.</li>
 *   <li><b>Inbound:</b> chunk reassembly keyed by message id, with a TTL that drops
 *       half-received messages.</li>
 *   <li><b>Replay guard:</b> a frame is delivered once and only if its id hasn't been
 *       seen recently and its timestamp is in-window.</li>
 * </ul>
 *
 * <p>It is wired with a "send one line" sink and a "(type, payload)" handler, so the
 * same class serves both the bot and the controller endpoints.
 */
public final class ControlChannel {

	/** Spacing between outbound whispers (anti chat-rate-kick). */
	private static final long SEND_INTERVAL_MILLIS = 300L;
	/** Drop a half-received multi-chunk message after this long. */
	private static final long PARTIAL_TTL_MILLIS = 12_000L;
	/** How many recent message ids to remember for replay rejection. */
	private static final int SEEN_CAP = 256;
	/** Hard cap on chunks per message — bounds memory against a malformed/huge total. */
	private static final int MAX_PARTS = 64;

	private final ControlProtocol proto;
	private final Consumer<String> sink;              // sends exactly one whisper line
	private final BiConsumer<String, String> handler; // (type, payload) of accepted frames
	private final String tag;                          // "bot" / "controller" — for diagnostic logs

	private final Deque<String> outbound = new ArrayDeque<>();
	private long lastSend = 0L;

	private final Map<String, Partial> partials = new HashMap<>();
	private final Set<String> seen = new LinkedHashSet<>();

	public ControlChannel(ControlProtocol proto, Consumer<String> sink, BiConsumer<String, String> handler) {
		this(proto, sink, handler, "ctl");
	}

	public ControlChannel(ControlProtocol proto, Consumer<String> sink, BiConsumer<String, String> handler, String tag) {
		this.proto = proto;
		this.sink = sink;
		this.handler = handler;
		this.tag = tag;
	}

	public boolean isReady() { return proto.isReady(); }

	/** Queue a (type, payload) message for sending (encrypted + chunked). */
	public void send(String type, String payload) {
		if (!proto.isReady()) return;
		java.util.List<String> lines = proto.encode(type, payload);
		outbound.addAll(lines);
		StasisBot.LOGGER.info("[control/{}] queued {} ({} chunk(s)) for send", tag, type, lines.size());
	}

	/** Feed every incoming whisper body; non-control lines are ignored. */
	public void onLine(String body) {
		Optional<ControlProtocol.Chunk> oc = ControlProtocol.parseChunk(body);
		if (oc.isEmpty()) return;
		ControlProtocol.Chunk c = oc.get();
		if (c.total() <= 0 || c.total() > MAX_PARTS || c.part() < 0 || c.part() >= c.total()) {
			StasisBot.LOGGER.warn("[control/{}] bad chunk header part={}/{}", tag, c.part(), c.total());
			return;
		}
		if (seen.contains(c.id())) return; // already delivered — ignore stragglers/replays

		Partial p = partials.computeIfAbsent(c.id(), k -> new Partial(c.total()));
		p.touch();
		if (p.parts.length != c.total()) return; // total disagrees with the first chunk seen
		p.parts[c.part()] = c.data();
		if (!p.complete()) return;

		partials.remove(c.id());
		markSeen(c.id());

		StringBuilder b64 = new StringBuilder();
		for (String part : p.parts) b64.append(part);
		Optional<ControlProtocol.Frame> f = proto.decode(b64.toString());
		if (f.isEmpty()) {                       // wrong secret / tampered / corrupted
			StasisBot.LOGGER.warn("[control/{}] rx id={} DECODE FAILED (wrong secret or corrupted, {} b64 chars)",
					tag, c.id(), b64.length());
			return;
		}
		if (!proto.inWindow(f.get().timestamp())) { // stale / replayed
			long skew = System.currentTimeMillis() - f.get().timestamp();
			StasisBot.LOGGER.warn("[control/{}] rx id={} type={} REJECTED out-of-window (skew={}ms)",
					tag, c.id(), f.get().type(), skew);
			return;
		}
		StasisBot.LOGGER.info("[control/{}] rx id={} type={} delivered", tag, c.id(), f.get().type());
		handler.accept(f.get().type(), f.get().payload());
	}

	/** Pump the outbound queue (call each client tick); also expires stale partials. */
	public void tick() {
		long now = System.currentTimeMillis();
		partials.values().removeIf(p -> now - p.lastTouch > PARTIAL_TTL_MILLIS);
		if (outbound.isEmpty()) return;
		if (now - lastSend < SEND_INTERVAL_MILLIS) return;
		String line = outbound.pollFirst();
		StasisBot.LOGGER.info("[control/{}] tx whisper ({} chars)", tag, line.length());
		sink.accept(line);
		lastSend = now;
	}

	private void markSeen(String id) {
		seen.add(id);
		if (seen.size() > SEEN_CAP) {
			var it = seen.iterator();
			it.next();
			it.remove();
		}
	}

	private static final class Partial {
		final String[] parts;
		long lastTouch;

		Partial(int total) {
			parts = new String[total];
			touch();
		}

		void touch() { lastTouch = System.currentTimeMillis(); }

		boolean complete() {
			for (String s : parts) if (s == null) return false;
			return true;
		}
	}
}
