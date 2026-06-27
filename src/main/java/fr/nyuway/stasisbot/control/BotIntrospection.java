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

	/** The bot's recent chat lines (newline-separated), for the live feed. */
	String chatLog();

	/** Send {@code text} as the bot — a command when it starts with {@code /}, else chat. */
	void say(String text);

	/**
	 * The bot's position and its distance to a player. Returns {@code "x y z|distance"};
	 * distance is {@code -1} when that player isn't visible. {@code watcher} is the name to
	 * measure the distance to (e.g. the operator).
	 */
	String posInfo(String watcher);

	/** Walk the bot to fixed coordinates. */
	void goTo(int x, int y, int z);

	/** Walk the bot to the named player (one-shot; the panel repeats it for "follow"). */
	void come(String player);

	/** Stop any remote-directed movement. */
	void stopNav();

	/** Leave the current server and stay off (auto-reconnect disabled until a connect). */
	void serverDisconnect();

	/** Connect (or reconnect): a blank target reconnects to the current server, else switches. */
	void serverConnect(String hostPort);
}
