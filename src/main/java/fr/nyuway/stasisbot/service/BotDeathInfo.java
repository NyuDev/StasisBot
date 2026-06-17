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

	private static final long FRESH_MILLIS = 5_000L;

	private volatile String reason;
	private volatile long at;

	/** Record the cause the chat watcher parsed for the bot's own death. */
	public void record(String reason) {
		this.reason = reason;
		this.at = System.currentTimeMillis();
	}

	/** The cause if one was captured in the last few seconds, otherwise {@code null}. */
	public String recentReason() {
		String r = reason;
		if (r == null) return null;
		if (System.currentTimeMillis() - at > FRESH_MILLIS) return null;
		return r;
	}

	/** Forget any stored reason (called once it's been consumed). */
	public void clear() {
		this.reason = null;
		this.at = 0L;
	}
}
