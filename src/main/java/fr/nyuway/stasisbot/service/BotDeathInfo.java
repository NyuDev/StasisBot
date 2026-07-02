package fr.nyuway.stasisbot.service;

/**
 * A tiny shared mailbox for the bot's most recent death cause. The chat-based
 * {@link DeathWatcher} writes the reason it parsed from the server's death
 * message; {@link HomeService} reads it back when it fires the {@code BOT_DIED}
 * Discord notification, so the announcement can say <em>how</em> the bot died.
 *
 * <p>Only the latest, still-fresh reason is kept — an old message is ignored so a
 * later death without a parsed cause doesn't reuse a stale one.
 */
public final class BotDeathInfo {

	/** Generous window: 2b2t respawn queues can take 10–20 s after the death message. */
	private static final long FRESH_MILLIS = 30_000L;

	private volatile String reason;
	private volatile long at;
	private volatile java.util.List<String> nearbyAtDeath = java.util.List.of();

	/** Record the cause the chat watcher parsed for the bot's own death. */
	public void record(String reason) {
		this.reason = reason;
		this.at = System.currentTimeMillis();
	}

	/** Record the player names visible in render distance at the moment of death. */
	public void recordNearby(java.util.List<String> names) {
		this.nearbyAtDeath = java.util.List.copyOf(names);
	}

	/** The cause if one was captured recently, otherwise {@code null}. */
	public String recentReason() {
		String r = reason;
		if (r == null) return null;
		if (System.currentTimeMillis() - at > FRESH_MILLIS) return null;
		return r;
	}

	/** Players that were in render distance when the bot died, or an empty list. */
	public java.util.List<String> recentNearby() {
		if (System.currentTimeMillis() - at > FRESH_MILLIS) return java.util.List.of();
		return nearbyAtDeath;
	}

	/** Forget all stored state (called once it's been consumed). */
	public void clear() {
		this.reason = null;
		this.at = 0L;
		this.nearbyAtDeath = java.util.List.of();
	}
}
