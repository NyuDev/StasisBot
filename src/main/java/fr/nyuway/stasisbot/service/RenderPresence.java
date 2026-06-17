package fr.nyuway.stasisbot.service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiny shared snapshot of who is currently around the bot, written by {@link
 * PlayerWatcher} once a second and read by collaborators that run on other paths:
 * {@link DiscordNotifier} (to gate {@code @everyone} pings on an unknown being
 * present) and {@link DeathWatcher} (to scope death messages to players the bot
 * actually saw). Deliberately dumb — it only holds state; the watcher decides who
 * counts as an outsider.
 */
public final class RenderPresence {

	/** A name stays "recently seen" for this long after it last left render. */
	private static final long RECENT_MILLIS = 20_000L;

	private volatile boolean outsiderPresent;
	private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

	/** Set whether at least one non-base player is in render right now. */
	void setOutsiderPresent(boolean present) {
		this.outsiderPresent = present;
	}

	/** True when an unknown (non-base) player is currently in the bot's render distance. */
	public boolean outsiderPresent() {
		return outsiderPresent;
	}

	/** Remember that {@code name} is/was just seen in render distance. */
	void markSeen(String name) {
		if (name != null) lastSeen.put(name.toLowerCase(Locale.ROOT), System.currentTimeMillis());
	}

	/** True when {@code name} was in render distance within the last few seconds. */
	public boolean recentlySeen(String name) {
		if (name == null) return false;
		Long t = lastSeen.get(name.toLowerCase(Locale.ROOT));
		return t != null && System.currentTimeMillis() - t <= RECENT_MILLIS;
	}

	/** Drop stale entries (called occasionally by the watcher to keep the map small). */
	void prune() {
		long now = System.currentTimeMillis();
		lastSeen.values().removeIf(t -> now - t > RECENT_MILLIS);
	}
}
