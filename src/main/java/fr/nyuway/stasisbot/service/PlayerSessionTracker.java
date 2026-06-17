package fr.nyuway.stasisbot.service;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared record of which players (re)connected to the server very recently,
 * written by {@link PlayerWatcher} from the tab list and read by {@link
 * ChamberWatcher}.
 *
 * <p>Why it exists: since Minecraft 1.21.2 a player's thrown ender pearls
 * despawn when they log out and respawn when they log back in. From the bot's
 * point of view that respawning pearl looks exactly like a stasis being
 * recharged — so without this, a simple disconnect/reconnect spams false
 * "recharged" notifications. ChamberWatcher consults this to ignore a chamber
 * that merely re-filled because its owner just reconnected.
 */
public final class PlayerSessionTracker {

	/** A connect stays "recent" for this long — long enough for the pearl to respawn. */
	private static final long GRACE_MILLIS = 10_000L;

	/** Lower-cased player name → when they were last seen joining the server. */
	private final Map<String, Long> connectedAt = new ConcurrentHashMap<>();

	/** Record that {@code name} just appeared in the tab list (joined the server). */
	public void markConnected(String name) {
		if (name != null) connectedAt.put(name.toLowerCase(Locale.ROOT), System.currentTimeMillis());
	}

	/** True when {@code name} (re)connected within the grace window. */
	public boolean recentlyConnected(String name) {
		if (name == null) return false;
		Long t = connectedAt.get(name.toLowerCase(Locale.ROOT));
		return t != null && System.currentTimeMillis() - t <= GRACE_MILLIS;
	}

	/** The (lower-cased) names that (re)connected within the grace window. */
	public Set<String> recentNames() {
		long now = System.currentTimeMillis();
		Set<String> out = new HashSet<>();
		for (Map.Entry<String, Long> e : connectedAt.entrySet()) {
			if (now - e.getValue() <= GRACE_MILLIS) out.add(e.getKey());
		}
		return out;
	}

	/** Drop stale entries so the map can't grow without bound. */
	public void prune() {
		long now = System.currentTimeMillis();
		connectedAt.values().removeIf(t -> now - t > GRACE_MILLIS);
	}
}
