package fr.nyuway.stasisbot.service;

/**
 * Shared marker for trigger toggles the bot itself performed. When the bot fires
 * or re-arms a stasis trap it changes that trigger's open/closed state, which the
 * {@link ChamberWatcher} would otherwise read as a player opening/closing the
 * stasis. The bot already announces its own actions ({@code STASIS_FIRED} /
 * {@code STASIS_REOPENED}), so those state changes must not also fire the
 * player-facing {@code STASIS_OPENED} / {@code STASIS_CLOSED} events.
 *
 * <p>{@link HomeService} calls {@link #markTriggerUse()} whenever it clicks a
 * trigger; {@link ChamberWatcher} skips open/close announcements while
 * {@link #recentlyToggledTrigger()} is true. A short window covers the click plus
 * the next watcher tick. Accessed only on the client thread.
 */
public final class BotActivity {

	/** How long after a bot trigger click to ignore the resulting open/close change. */
	private static final long SUPPRESS_MILLIS = 2500L;

	/**
	 * Epoch-ms of the bot's last trigger click. Starts at 0 (the epoch, i.e. "long ago")
	 * rather than {@link Long#MIN_VALUE}: subtracting MIN_VALUE from a current timestamp
	 * overflows to a negative result, which would make {@link #recentlyToggledTrigger()}
	 * wrongly report true forever until the first real click.
	 */
	private long lastTriggerUseAt = 0L;

	/** Record that the bot just clicked a stasis trigger (fire or re-arm). */
	public void markTriggerUse() {
		lastTriggerUseAt = System.currentTimeMillis();
	}

	/** True while a recent bot trigger click should mask an open/close state change. */
	public boolean recentlyToggledTrigger() {
		return System.currentTimeMillis() - lastTriggerUseAt < SUPPRESS_MILLIS;
	}
}
