package fr.nyuway.stasisbot.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fr.nyuway.stasisbot.StasisBot;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * User configuration, persisted as JSON in {@code config/stasisbot.json}.
 *
 * <p>The mod is driven entirely by chat — there are no in-game commands — so the
 * only knobs live in this hand-editable file. Fields are private and exposed
 * through read-only accessors; nothing mutates the config at runtime.
 */
public final class StasisBotConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * Default trigger words for a fresh config. Several presets are seeded so the
	 * master can vary the wording out of the box to dodge server anti-spam: a chat
	 * line fires a request when it <em>starts</em> with any of these.
	 */
	private static final List<String> DEFAULT_TRIGGERS = List.of("!home", "pearl", "warp");

	/**
	 * Legacy single trigger word, kept only so old config files still load; it is
	 * migrated into {@link #triggerWords} on first load and then dropped.
	 */
	private String triggerWord;

	/**
	 * Words/phrases the bot reacts to in chat. A chat line triggers a home request
	 * when it <em>starts</em> with any of these (case-insensitive), optionally
	 * followed by a space and arbitrary text — that trailing "gibberish" lets a
	 * player slip past a server's repeat-message anti-spam (e.g. {@code "!home xj3"}).
	 * The keyword must come <em>first</em>, so ordinary chat mentioning the word
	 * mid-sentence never fires. Having several keywords lets the master vary the
	 * wording too. Left null until {@link #sanitise()} fills it (from the legacy
	 * field or the presets) so an old config carrying only {@code triggerWord}
	 * migrates cleanly.
	 */
	private List<String> triggerWords;

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
	/**
	 * When true, any base member — a player whose name/alias appears on a detected
	 * stasis sign — may run config commands too, not just the master. Lets a whole
	 * trusted base drive the bot without sharing one master account.
	 */
	private boolean baseMembersControl = false;
	/** Prefix for master config commands (e.g. "!sb drop off"). */
	private String commandPrefix = "!sb";
	/** Verbose logging + in-game debug messages. */
	private boolean debug = false;
	/** Use Baritone for pathfinding when it's installed (else the primitive walker). */
	private boolean useBaritone = true;
	/** Forget a chamber that hasn't been re-seen for this long (ms). */
	private long rememberMillis = 60000L;
	/** After dying, automatically respawn and walk back to the fixed home position. */
	private boolean returnHomeOnDeath = true;

	// --- Discord webhook (opt-in, off by default) ---------------------------
	/** Master switch for Discord webhook notifications. */
	private boolean discordEnabled = false;
	/** Discord webhook URL; only used when {@link #discordEnabled} and it's a real webhook host. */
	private String discordWebhookUrl = "";
	/** Whether the GUI "Test" button pings @everyone (per-event pings are configured separately). */
	private boolean discordTestPing = true;
	/** Send messages as a rich embed (named, coloured card) instead of plain text. */
	private boolean discordUseEmbeds = false;
	/**
	 * What a ping mentions when one fires (the per-event {@link PingMode} decides
	 * <em>whether</em> to ping): {@code @everyone}, {@code @here}, or a custom role.
	 */
	private PingTarget pingTarget = PingTarget.EVERYONE;
	/**
	 * The custom role to mention when {@link #pingTarget} is {@link PingTarget#ROLE}:
	 * either its numeric ID (a real ping) or its name (shown as text — a webhook can't
	 * resolve a name to an ID). Defaults to {@code "2b2t"}.
	 */
	private String pingRole = "2b2t";
	/**
	 * Per-event settings, keyed by {@link DiscordEvent#key()}: whether to send it, its
	 * {@code @everyone} {@link PingMode}, and the optional gear / coordinates / distance
	 * extras. Missing entries fall back to each event's defaults.
	 */
	private Map<String, DiscordEventSetting> discordEvents = new LinkedHashMap<>();

	/**
	 * Lower-cased player name → keywords that may appear on that player's sign
	 * instead of (or in addition to) their name. E.g. {@code "zarivox": ["tower"]}.
	 */
	private Map<String, List<String>> aliases = new LinkedHashMap<>();

	// --- accessors -----------------------------------------------------------

	/** Effective trigger words (never empty; defaults to the presets). */
	public List<String> triggerWords() {
		return (triggerWords == null || triggerWords.isEmpty()) ? List.copyOf(DEFAULT_TRIGGERS) : List.copyOf(triggerWords);
	}

	/** Human-readable, comma-separated list for logs/GUI/command echoes. */
	public String triggerWordsDisplay() {
		return String.join(", ", triggerWords());
	}

	/**
	 * True when {@code body} <em>begins</em> with a configured trigger word
	 * (case-insensitive), either standing alone or directly followed by a space.
	 * Anything after that space is ignored, so a player can append random text to
	 * beat a server's "same message twice" anti-spam while the bot still reacts.
	 * The keyword must be first, so it won't fire on normal chat that merely
	 * mentions the word later in a sentence.
	 */
	public boolean matchesTrigger(String body) {
		if (body == null) return false;
		String b = body.trim().toLowerCase(Locale.ROOT);
		for (String w : triggerWords()) {
			if (w.isBlank()) continue;
			String word = w.toLowerCase(Locale.ROOT);
			if (b.equals(word)) return true;
			if (b.startsWith(word) && Character.isWhitespace(b.charAt(word.length()))) return true;
		}
		return false;
	}

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
	public boolean baseMembersControl() { return baseMembersControl; }
	public String commandPrefix() { return commandPrefix; }
	public boolean debug() { return debug; }
	public boolean useBaritone() { return useBaritone; }
	public long rememberMillis() { return rememberMillis; }
	public boolean returnHomeOnDeath() { return returnHomeOnDeath; }
	public boolean discordEnabled() { return discordEnabled; }
	public String discordWebhookUrl() { return discordWebhookUrl == null ? "" : discordWebhookUrl; }
	public boolean discordTestPing() { return discordTestPing; }
	public boolean discordUseEmbeds() { return discordUseEmbeds; }
	public PingTarget pingTarget() { return pingTarget == null ? PingTarget.EVERYONE : pingTarget; }
	public String pingRole() { return (pingRole == null || pingRole.isBlank()) ? "2b2t" : pingRole.trim(); }

	/** Effective "send this event?" flag (falls back to the event's default). */
	public boolean discordEventEnabled(DiscordEvent e) { return eventSetting(e).enabled; }
	/** Effective {@code @everyone} ping mode for this event. */
	public PingMode discordEventPing(DiscordEvent e) { return eventSetting(e).pingMode; }
	/** Effective "attach the player's gear?" flag (only meaningful for detailable events). */
	public boolean discordEventDetails(DiscordEvent e) { return Boolean.TRUE.equals(eventSetting(e).details); }
	/** Effective "attach coordinates (spoiler)?" flag (only meaningful for locatable events). */
	public boolean discordEventCoords(DiscordEvent e) { return Boolean.TRUE.equals(eventSetting(e).coords); }
	/** Effective "attach distance to the bot?" flag (only meaningful for locatable events). */
	public boolean discordEventDistance(DiscordEvent e) { return Boolean.TRUE.equals(eventSetting(e).distance); }

	// --- mutators (master commands / GUI toggles) — each persists immediately ----

	public void setLanguage(String v) { this.language = "fr".equalsIgnoreCase(v) ? "fr" : "en"; save(); }
	public void setDropPearlForPlayer(boolean v) { this.dropPearlForPlayer = v; save(); }
	public void setReopenTrigger(boolean v) { this.reopenTrigger = v; save(); }
	public void setReturnHome(boolean v) { this.returnHome = v; save(); }
	public void setAutoWalk(boolean v) { this.autoWalk = v; save(); }
	public void setRequireOnline(boolean v) { this.requireOnline = v; save(); }
	public void setDmFeedback(boolean v) { this.dmFeedback = v; save(); }
	public void setWhisperCommand(String v) { if (v != null && !v.isBlank()) { this.whisperCommand = v.trim(); save(); } }

	/** Replace the whole trigger-word list (cleaned; falls back to the presets if empty). */
	public void setTriggerWords(List<String> words) {
		this.triggerWords = cleanWords(words);
		if (this.triggerWords.isEmpty()) this.triggerWords.addAll(DEFAULT_TRIGGERS);
		save();
	}

	/** Add one trigger word if not already present. */
	public void addTriggerWord(String word) {
		if (word == null || word.isBlank()) return;
		if (triggerWords == null) triggerWords = new ArrayList<>();
		String s = word.trim().toLowerCase(Locale.ROOT);
		if (!triggerWords.contains(s)) { triggerWords.add(s); save(); }
	}

	/** Remove a trigger word; keeps the presets if you'd empty the list, so the bot is never deaf. */
	public boolean removeTriggerWord(String word) {
		if (word == null || triggerWords == null) return false;
		boolean removed = triggerWords.removeIf(s -> s.equalsIgnoreCase(word.trim()));
		if (removed) {
			if (triggerWords.isEmpty()) triggerWords.addAll(DEFAULT_TRIGGERS);
			save();
		}
		return removed;
	}
	public void setMaster(String v) { this.master = v == null ? "" : v.trim(); save(); }
	public void setBaseMembersControl(boolean v) { this.baseMembersControl = v; save(); }
	public void setDebug(boolean v) { this.debug = v; save(); }
	public void setUseBaritone(boolean v) { this.useBaritone = v; save(); }
	public void setReturnPos(Integer x, Integer y, Integer z) { this.returnX = x; this.returnY = y; this.returnZ = z; save(); }
	public void setReturnHomeOnDeath(boolean v) { this.returnHomeOnDeath = v; save(); }
	public void setDiscordEnabled(boolean v) { this.discordEnabled = v; save(); }
	public void setDiscordWebhookUrl(String v) { this.discordWebhookUrl = v == null ? "" : v.trim(); save(); }
	public void setDiscordTestPing(boolean v) { this.discordTestPing = v; save(); }
	public void setDiscordUseEmbeds(boolean v) { this.discordUseEmbeds = v; save(); }
	public void setPingTarget(PingTarget v) { this.pingTarget = v == null ? PingTarget.EVERYONE : v; save(); }
	public void setPingRole(String v) { this.pingRole = (v == null || v.isBlank()) ? "2b2t" : v.trim(); save(); }
	public void setDiscordEventEnabled(DiscordEvent e, boolean v) { eventSetting(e).enabled = v; save(); }
	public void setDiscordEventPing(DiscordEvent e, PingMode v) { eventSetting(e).pingMode = v; save(); }
	public void setDiscordEventDetails(DiscordEvent e, boolean v) { eventSetting(e).details = v; save(); }
	public void setDiscordEventCoords(DiscordEvent e, boolean v) { eventSetting(e).coords = v; save(); }
	public void setDiscordEventDistance(DiscordEvent e, boolean v) { eventSetting(e).distance = v; save(); }

	/** Get-or-create the (materialised) settings for one event, filling nulls from its defaults. */
	private DiscordEventSetting eventSetting(DiscordEvent e) {
		if (discordEvents == null) discordEvents = new LinkedHashMap<>();
		DiscordEventSetting s = discordEvents.computeIfAbsent(e.key(), k -> new DiscordEventSetting());
		if (s.enabled == null) s.enabled = e.defaultEnabled();
		if (s.pingMode == null) s.pingMode = e.defaultPing();
		if (s.details == null) s.details = false;
		if (s.coords == null) s.coords = false;
		if (s.distance == null) s.distance = false;
		return s;
	}

	/** Per-event Discord toggles; nullable so a missing field inherits the event default. */
	static final class DiscordEventSetting {
		Boolean enabled;
		PingMode pingMode;
		Boolean details;
		Boolean coords;
		Boolean distance;
	}

	/** Configured keywords for a player (never null). */
	public List<String> aliasesFor(String playerName) {
		if (playerName == null || aliases == null) return List.of();
		List<String> found = aliases.get(playerName.toLowerCase());
		return found != null ? found : List.of();
	}

	/** Read-only snapshot of every mapping (player → keywords), for the GUI. */
	public Map<String, List<String>> aliases() {
		return aliases == null ? Map.of() : new LinkedHashMap<>(aliases);
	}

	/**
	 * Add or replace a mapping. Player name and keywords are lower-cased and
	 * trimmed; blank keywords are dropped. An empty keyword list removes the
	 * mapping entirely. Persists immediately.
	 */
	public void putAlias(String player, List<String> keywords) {
		if (player == null || player.isBlank()) return;
		if (aliases == null) aliases = new LinkedHashMap<>();
		String key = player.trim().toLowerCase();
		List<String> clean = keywords == null ? List.of() : keywords.stream()
				.map(s -> s == null ? "" : s.trim().toLowerCase())
				.filter(s -> !s.isBlank())
				.distinct()
				.toList();
		if (clean.isEmpty()) aliases.remove(key);
		else aliases.put(key, clean);
		save();
	}

	/** Remove a player's mapping if present. Persists immediately. */
	public void removeAlias(String player) {
		if (player == null || aliases == null) return;
		if (aliases.remove(player.trim().toLowerCase()) != null) save();
	}

	/** Lower-case, trim, drop blanks and de-duplicate a list of trigger words. */
	private static List<String> cleanWords(List<String> words) {
		if (words == null) return new ArrayList<>();
		return words.stream()
				.map(s -> s == null ? "" : s.trim().toLowerCase(Locale.ROOT))
				.filter(s -> !s.isBlank())
				.distinct()
				.collect(Collectors.toCollection(ArrayList::new));
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
		// Migrate the legacy single trigger word into the multi-word list, then drop it.
		if (triggerWords == null) triggerWords = new ArrayList<>();
		if (triggerWords.isEmpty() && triggerWord != null && !triggerWord.isBlank()) {
			triggerWords.add(triggerWord);
		}
		triggerWord = null; // legacy field consumed — never re-serialised
		triggerWords = cleanWords(triggerWords);
		if (triggerWords.isEmpty()) triggerWords.addAll(DEFAULT_TRIGGERS);
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
		if (discordWebhookUrl == null) discordWebhookUrl = "";
		if (discordEvents == null) discordEvents = new LinkedHashMap<>();
		if (pingTarget == null) pingTarget = PingTarget.EVERYONE;
		if (pingRole == null || pingRole.isBlank()) pingRole = "2b2t";
	}

	private static StasisBotConfig withExample() {
		StasisBotConfig cfg = new StasisBotConfig();
		cfg.triggerWords = new ArrayList<>(DEFAULT_TRIGGERS);
		// Seed one illustrative alias so the file documents its own format.
		// (Real player aliases belong in your local config/stasisbot.json, not in source.)
		cfg.aliases.put("steve", List.of("tower"));
		return cfg;
	}
}
