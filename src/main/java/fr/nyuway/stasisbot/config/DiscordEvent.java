package fr.nyuway.stasisbot.config;

import java.util.Locale;

/**
 * Every kind of thing the bot can announce to Discord. Each event carries its own
 * stable config key plus sensible defaults; the actual per-event settings (send on/off,
 * the {@code @everyone} {@link PingMode}, and the optional gear / coordinates / distance
 * extras) live in {@link StasisBotConfig}, keyed by {@link #key()}.
 *
 * <p>The flags describe what an event <em>supports</em>:
 * <ul>
 *   <li>{@code scoped} — the event is about a specific player who may or may not be a
 *       base member, so the {@link PingMode#OUTSIDERS} ping choice is meaningful;</li>
 *   <li>{@code detailable} — the event can optionally carry the player's gear
 *       (armour + held items);</li>
 *   <li>{@code locatable} — the event happens at a known spot, so it can optionally
 *       carry coordinates (spoiler-tagged) and/or the distance to the bot.</li>
 * </ul>
 */
public enum DiscordEvent {

	// key                label                    scoped detailable locatable enabled  defaultPing
	PLAYER_ENTER     ("player_enter",      "Player enters render",  true,  true,  true,  false, PingMode.OFF),
	PLAYER_LEAVE     ("player_leave",      "Player leaves render",  true,  true,  true,  false, PingMode.OFF),
	PLAYER_CONNECT   ("player_connect",    "Player connected",      true,  true,  true,  false, PingMode.OFF),
	PLAYER_DISCONNECT("player_disconnect", "Player disconnected",   true,  true,  true,  false, PingMode.OFF),
	WATCHED_CHAT     ("watched_chat",      "Watched player chatted",true,  false, false, true,  PingMode.OFF),
	WATCHED_JOIN     ("watched_join",      "Watched player joined", true,  false, false, true,  PingMode.OFF),
	WATCHED_LEAVE    ("watched_leave",     "Watched player left",   true,  false, false, true,  PingMode.OFF),
	PLAYER_DIED      ("player_died",       "Player died (render)",  true,  false, true,  false, PingMode.OFF),
	PEARL_THROWN     ("pearl_thrown",      "Player pearl teleport", true,  false, true,  false, PingMode.OFF),
	CRYSTAL_PLACED   ("crystal_placed",    "End crystal placed",    true,  false, true,  false, PingMode.OFF),
	CRYSTAL_BROKEN   ("crystal_broken",    "End crystal exploded",  true,  false, true,  false, PingMode.OFF),
	TNT_PRIMED       ("tnt_primed",        "TNT ignited (render)",  true,  false, true,  false, PingMode.OFF),
	STASIS_WRONG_PEARL("stasis_wrong_pearl","Pearl in wrong stasis",true,  false, true,  false, PingMode.OFF),
	STASIS_OPENED    ("stasis_opened",     "Stasis opened (player)",true,  false, true,  true,  PingMode.OFF),
	STASIS_CLOSED    ("stasis_closed",     "Stasis closed (player)",true,  false, true,  true,  PingMode.OFF),
	HOME_REQUESTED   ("home_requested",    "Home requested",        true,  false, false, false, PingMode.OFF),
	STASIS_EMPTY     ("stasis_empty",      "Stasis empty",          true,  false, false, false, PingMode.OFF),
	BOT_EN_ROUTE     ("bot_en_route",      "Bot walking to stasis", true,  false, false, false, PingMode.OFF),
	PATH_FAILED      ("path_failed",       "Bot couldn't reach",    true,  false, false, false, PingMode.OFF),
	STASIS_FIRED     ("stasis_fired",      "Stasis fired",          true,  false, false, false, PingMode.OFF),
	TP_CONFIRMED     ("tp_confirmed",      "Teleport confirmed",    true,  false, false, false, PingMode.OFF),
	TP_FAILED        ("tp_failed",         "Teleport failed",       true,  false, false, false, PingMode.OFF),
	PEARL_DROPPED    ("pearl_dropped",     "Bot dropped a pearl",   true,  false, false, false, PingMode.OFF),
	PEARL_PICKED_UP  ("pearl_picked_up",   "Player took the pearl", true,  false, false, false, PingMode.OFF),
	STASIS_REOPENED  ("stasis_reopened",   "Bot reopened stasis",   true,  false, false, false, PingMode.OFF),

	// --- bot / base state (no outsider scope) -------------------------------
	OUT_OF_PEARLS    ("out_of_pearls",     "Out of pearls",         false, false, false, true,  PingMode.ALL),
	STASIS_RECHARGED ("stasis_recharged",  "Stasis recharged",      false, false, true,  false, PingMode.OFF),
	BOT_DIED         ("bot_died",          "Bot died",              false, false, false, true,  PingMode.ALL),
	BOT_RESPAWNED    ("bot_respawned",     "Bot respawned",         false, false, false, false, PingMode.OFF),
	RESTOCKED        ("restocked",         "Bot restocked pearls",  false, false, false, false, PingMode.OFF),
	RETURN_TOO_FAR   ("return_too_far",    "Home too far, gave up", false, false, false, false, PingMode.OFF);

	private final String key;
	private final String label;
	private final boolean scoped;
	private final boolean detailable;
	private final boolean locatable;
	private final boolean defaultEnabled;
	private final PingMode defaultPing;

	DiscordEvent(String key, String label, boolean scoped, boolean detailable, boolean locatable,
	             boolean defaultEnabled, PingMode defaultPing) {
		this.key = key;
		this.label = label;
		this.scoped = scoped;
		this.detailable = detailable;
		this.locatable = locatable;
		this.defaultEnabled = defaultEnabled;
		this.defaultPing = defaultPing;
	}

	public String key() { return key; }
	public String label() { return label; }
	/** True when the {@link PingMode#OUTSIDERS} choice is meaningful (event is about a player). */
	public boolean scoped() { return scoped; }
	/** True when the event can optionally include the player's gear. */
	public boolean detailable() { return detailable; }
	/** True when the event has a location (can include coords / distance). */
	public boolean locatable() { return locatable; }
	public boolean defaultEnabled() { return defaultEnabled; }
	public PingMode defaultPing() { return defaultPing; }

	/** Lookup by stable key (used when loading config); null when unknown. */
	public static DiscordEvent byKey(String key) {
		if (key == null) return null;
		String k = key.toLowerCase(Locale.ROOT);
		for (DiscordEvent e : values()) {
			if (e.key.equals(k)) return e;
		}
		return null;
	}
}
