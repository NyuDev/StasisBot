package fr.nyuway.stasisbot.service;

/**
 * Cheap connection-health guard. Sampled once per client tick, it tracks how far
 * apart ticks land in wall-clock time; a healthy client ticks every ~50 ms, so a
 * sustained gap means the client (and usually the link to the server) is hitching.
 *
 * <p>{@link HomeService} consults {@link #isLagging()} right before a teleport so
 * the bot never fires the pearl into a frozen world — releasing it during a lag
 * spike can desync the pull and waste the pearl.
 */
public final class LagMonitor {

	private final long thresholdMillis;
	private long lastTickAt = 0L;
	private double emaDeltaMillis = 50.0;

	public LagMonitor(long thresholdMillis) {
		this.thresholdMillis = Math.max(100L, thresholdMillis);
	}

	/** Call exactly once per client tick. */
	public void onTick() {
		long now = System.currentTimeMillis();
		if (lastTickAt != 0L) {
			long delta = now - lastTickAt;
			// Weighted toward recent ticks so a single spike fades quickly.
			emaDeltaMillis = emaDeltaMillis * 0.6 + delta * 0.4;
		}
		lastTickAt = now;
	}

	/** True when ticks have been spacing out, or we're mid-stall right now. */
	public boolean isLagging() {
		long sinceLast = lastTickAt == 0L ? 0L : System.currentTimeMillis() - lastTickAt;
		return emaDeltaMillis > thresholdMillis || sinceLast > thresholdMillis;
	}
}
