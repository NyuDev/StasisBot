package fr.nyuway.stasisbot.control;

/**
 * The live, world-dependent things the control API can expose about a running bot —
 * implemented in bot mode (it has the chamber index + the player), called on the client
 * thread by {@link ControlHttpServer}. Lets the remote panel mirror the bot's detected
 * chambers and drive the few actions that need the bot's actual position.
 */
public interface BotIntrospection {

	/**
	 * The currently detected chambers, one per line as {@code label|x y z|state}, where
	 * state is {@code 0} (empty), {@code 1} (our pearl loaded) or {@code w} (wrong pearl).
	 * Empty string when not in a world.
	 */
	String chambers();

	/** Pin the bot's current block as its home position. Returns true when set. */
	boolean setHome();

	/** Force a fresh scan of nearby chambers. */
	void rescan();
}
