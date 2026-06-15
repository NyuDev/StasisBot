package fr.nyuway.stasisbot.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fr.nyuway.stasisbot.StasisBot;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * User configuration, persisted as JSON in {@code config/stasisbot.json}.
 *
 * <p>The mod is driven entirely by chat — there are no in-game commands — so the
 * only knobs live in this hand-editable file. Fields are private and exposed
 * through read-only accessors; nothing mutates the config at runtime.
 */
public final class StasisBotConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Word the bot reacts to in chat (substring match, case-insensitive). */
	private String triggerWord = "!home";

	/** Radius, in chunks, around the bot to look at for chambers. */
	private int scanChunkRadius = 2;
	/** Ignore signs farther than this many blocks from the bot. */
	private int maxChamberDistance = 24;
	/** How far from a sign to look for its lever/button. */
	private int triggerSearchRadius = 3;
	/** How close an ender pearl must be to a chamber to count as "loaded". */
	private double pearlSearchRadius = 4.0;
	/** Minimum time between world re-scans, so chat spam never causes scan spam. */
	private long indexTtlMillis = 3000L;

	/** Maximum block-interaction distance; the bot must be parked within it. */
	private double reach = 5.0;
	/** Rotate toward the trigger before clicking it. */
	private boolean autoLook = true;

	/** Send /msg feedback to the player who requested a home. */
	private boolean dmFeedback = true;
	/** Server whisper command name (without the leading slash). 2b2t uses "msg". */
	private String whisperCommand = "msg";

	/** Let the bot walk to a chamber that's out of reach before firing it. */
	private boolean autoWalk = true;
	/** Give up walking after this long (ms) — avoids the bot wandering forever. */
	private long navTimeoutMillis = 30000L;
	/** Give up if the bot hasn't gotten closer for this long (ms) — stuck on a wall. */
	private long navStuckMillis = 3000L;
	/** How close (blocks) Baritone must get to the trigger before we click it. */
	private int navGoalRange = 2;

	/** Message language: "en" (default) or "fr". */
	private String language = "en";
	/** Drop one ender pearl on the ground for the player after pulling them. */
	private boolean dropPearlForPlayer = true;
	/** Click the trigger again after the TP to reset the trap for next time. */
	private boolean reopenTrigger = true;
	/** Walk back to the start point (or fixed return position) once the job is done. */
	private boolean returnHome = true;
	/** Optional fixed return position; when null the bot returns to where it started. */
	private Integer returnX = null;
	private Integer returnY = null;
	private Integer returnZ = null;
	/** Don't teleport a player who isn't in the server player list. */
	private boolean requireOnline = true;
	/** Treat the server as lagging when client ticks stall beyond this (ms). */
	private long lagThresholdMillis = 250L;
	/** How long (ms) to wait for the player to actually appear after firing. */
	private long arrivalTimeoutMillis = 5000L;
	/** Player name allowed to configure the bot by chat/DM (empty = nobody). */
	private String master = "";
	/** Prefix for master config commands (e.g. "!sb drop off"). */
	private String commandPrefix = "!sb";
	/** Verbose logging + in-game debug messages. */
	private boolean debug = false;
	/** Use Baritone for pathfinding when it's installed (else the primitive walker). */
	private boolean useBaritone = true;
	/** Forget a chamber that hasn't been re-seen for this long (ms). */
	private long rememberMillis = 60000L;

	/**
	 * Lower-cased player name → keywords that may appear on that player's sign
	 * instead of (or in addition to) their name. E.g. {@code "zarivox": ["tower"]}.
	 */
	private Map<String, List<String>> aliases = new LinkedHashMap<>();

	// --- accessors -----------------------------------------------------------

	public String triggerWord() { return triggerWord; }
	public int scanChunkRadius() { return scanChunkRadius; }
	public int maxChamberDistance() { return maxChamberDistance; }
	public int triggerSearchRadius() { return triggerSearchRadius; }
	public double pearlSearchRadius() { return pearlSearchRadius; }
	public long indexTtlMillis() { return indexTtlMillis; }
	public double reach() { return reach; }
	public boolean autoLook() { return autoLook; }
	public boolean dmFeedback() { return dmFeedback; }
	public String whisperCommand() { return whisperCommand; }
	public boolean autoWalk() { return autoWalk; }
	public long navTimeoutMillis() { return navTimeoutMillis; }
	public long navStuckMillis() { return navStuckMillis; }
	public int navGoalRange() { return navGoalRange; }
	public String language() { return language; }
	public boolean dropPearlForPlayer() { return dropPearlForPlayer; }
	public boolean reopenTrigger() { return reopenTrigger; }
	public boolean returnHome() { return returnHome; }
	public Integer returnX() { return returnX; }
	public Integer returnY() { return returnY; }
	public Integer returnZ() { return returnZ; }
	public boolean hasReturnPos() { return returnX != null && returnY != null && returnZ != null; }
	public boolean requireOnline() { return requireOnline; }
	public long lagThresholdMillis() { return lagThresholdMillis; }
	public long arrivalTimeoutMillis() { return arrivalTimeoutMillis; }
	public String master() { return master; }
	public boolean isMaster(String name) {
		return name != null && master != null && !master.isBlank()
				&& master.equalsIgnoreCase(name.trim());
	}
	public String commandPrefix() { return commandPrefix; }
	public boolean debug() { return debug; }
	public boolean useBaritone() { return useBaritone; }
	public long rememberMillis() { return rememberMillis; }

	// --- mutators (master commands / GUI toggles) — each persists immediately ----

	public void setLanguage(String v) { this.language = "fr".equalsIgnoreCase(v) ? "fr" : "en"; save(); }
	public void setDropPearlForPlayer(boolean v) { this.dropPearlForPlayer = v; save(); }
	public void setReopenTrigger(boolean v) { this.reopenTrigger = v; save(); }
	public void setReturnHome(boolean v) { this.returnHome = v; save(); }
	public void setAutoWalk(boolean v) { this.autoWalk = v; save(); }
	public void setRequireOnline(boolean v) { this.requireOnline = v; save(); }
	public void setDmFeedback(boolean v) { this.dmFeedback = v; save(); }
	public void setTriggerWord(String v) { if (v != null && !v.isBlank()) { this.triggerWord = v.trim(); save(); } }
	public void setWhisperCommand(String v) { if (v != null && !v.isBlank()) { this.whisperCommand = v.trim(); save(); } }
	public void setMaster(String v) { this.master = v == null ? "" : v.trim(); save(); }
	public void setDebug(boolean v) { this.debug = v; save(); }
	public void setUseBaritone(boolean v) { this.useBaritone = v; save(); }
	public void setReturnPos(Integer x, Integer y, Integer z) { this.returnX = x; this.returnY = y; this.returnZ = z; save(); }

	/** Configured keywords for a player (never null). */
	public List<String> aliasesFor(String playerName) {
		if (playerName == null || aliases == null) return List.of();
		List<String> found = aliases.get(playerName.toLowerCase());
		return found != null ? found : List.of();
	}

	// --- persistence ---------------------------------------------------------

	public static StasisBotConfig load() {
		Path path = path();
		try {
			if (Files.exists(path)) {
				StasisBotConfig cfg = GSON.fromJson(Files.readString(path), StasisBotConfig.class);
				if (cfg != null) {
					cfg.sanitise();
					return cfg;
				}
			}
		} catch (Exception e) {
			StasisBot.LOGGER.error("Could not read config; using defaults", e);
		}
		StasisBotConfig cfg = withExample();
		cfg.save();
		return cfg;
	}

	public void save() {
		Path path = path();
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(this));
		} catch (IOException e) {
			StasisBot.LOGGER.error("Could not write config", e);
		}
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(StasisBot.MOD_ID + ".json");
	}

	private void sanitise() {
		if (triggerWord == null || triggerWord.isBlank()) triggerWord = "!home";
		if (aliases == null) aliases = new LinkedHashMap<>();
		if (scanChunkRadius < 1) scanChunkRadius = 1;
		if (maxChamberDistance < 1) maxChamberDistance = 24;
		if (triggerSearchRadius < 1) triggerSearchRadius = 3;
		if (pearlSearchRadius <= 0) pearlSearchRadius = 4.0;
		if (indexTtlMillis < 0) indexTtlMillis = 3000L;
		if (reach <= 0) reach = 5.0;
		if (whisperCommand == null || whisperCommand.isBlank()) whisperCommand = "msg";
		if (navTimeoutMillis < 1000L) navTimeoutMillis = 30000L;
		if (navStuckMillis < 500L) navStuckMillis = 3000L;
		if (navGoalRange < 1) navGoalRange = 2;
		if (language == null || !language.equalsIgnoreCase("fr")) language = "en";
		if (lagThresholdMillis < 100L) lagThresholdMillis = 250L;
		if (arrivalTimeoutMillis < 1000L) arrivalTimeoutMillis = 5000L;
		if (commandPrefix == null || commandPrefix.isBlank()) commandPrefix = "!sb";
		if (master == null) master = "";
		if (rememberMillis < 0L) rememberMillis = 60000L;
	}

	private static StasisBotConfig withExample() {
		StasisBotConfig cfg = new StasisBotConfig();
		// Seed one illustrative alias so the file documents its own format.
		// (Real player aliases belong in your local config/stasisbot.json, not in source.)
		cfg.aliases.put("steve", List.of("tower"));
		return cfg;
	}
}
