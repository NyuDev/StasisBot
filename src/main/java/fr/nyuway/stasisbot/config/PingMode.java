package fr.nyuway.stasisbot.config;

/**
 * How an event should mention {@code @everyone} in Discord. Replaces the old
 * separate "ping on/off" + "all-vs-outsiders scope" pair with a single, clearer
 * three-way choice:
 *
 * <ul>
 *   <li>{@link #OFF} — never ping;</li>
 *   <li>{@link #OUTSIDERS} — ping only when the event is about someone the bot
 *       doesn't recognise (no stasis sign in their name);</li>
 *   <li>{@link #ALL} — always ping.</li>
 * </ul>
 *
 * <p>For non-scoped events (the bot died, restocked, …) there is no "outsider"
 * notion, so only {@link #OFF} and {@link #ALL} are meaningful there.
 */
public enum PingMode {
	OFF,
	OUTSIDERS,
	ALL;

	/** Cycle to the next mode. Non-scoped events skip {@link #OUTSIDERS}. */
	public PingMode next(boolean scoped) {
		return switch (this) {
			case OFF -> scoped ? OUTSIDERS : ALL;
			case OUTSIDERS -> ALL;
			case ALL -> OFF;
		};
	}
}
