package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.config.DiscordEvent;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Player surveillance, all opt-in (no-op until configured):
 *
 * <ul>
 *   <li><b>Global watchlist</b> (master via {@code !sb watch}) — sends Discord events for
 *       watched players' chat and connects/disconnects.</li>
 *   <li><b>Per-member watchlists</b> (base members via {@code !watch}) — each base member
 *       can watch up to 5 players; notifications go either to that member's DM (bot whispers
 *       them in-game) or to Discord, configurable with {@code !watchmode}.</li>
 *   <li><b>General chat relay</b> — optional relay of ALL chat to a Discord webhook,
 *       batched to stay under the rate limit. Off by default.</li>
 * </ul>
 */
public final class SurveillanceService {

	private static final long CHECK_INTERVAL_MILLIS = 1000L;
	private static final long RELAY_FLUSH_MILLIS = 2500L;
	private static final int RELAY_MAX_BUFFER = 300;
	private static final int RELAY_MAX_CHARS = 1800;

	private final MinecraftClient client;
	private final StasisBotConfig config;
	private final DiscordNotifier discord;

	/** Players currently tracked as online (global + all member watch lists). */
	private final Set<String> onlineWatched = new HashSet<>();
	private final Deque<String> relayBuffer = new ArrayDeque<>();
	private boolean primed = false;
	private long lastCheck = 0L;
	private long lastFlush = 0L;

	public SurveillanceService(MinecraftClient client, StasisBotConfig config, DiscordNotifier discord) {
		this.client = client;
		this.config = config;
		this.discord = discord;
	}

	/** Fed every parsed chat line (sender, body, whether it arrived as a DM). */
	public void onChat(String sender, String body, boolean dm) {
		if (sender == null) return;

		boolean discordReady = discord.isReady();
		boolean watchChatEnabled = config.discordEventEnabled(DiscordEvent.WATCHED_CHAT);

		// Global watchlist → Discord only
		boolean globalMatch = config.isWatched(sender);

		// Per-member watchlists
		Map<String, String> memberWatchers = config.watchersOf(sender);
		boolean memberDiscordMode = !memberWatchers.isEmpty()
				&& memberWatchers.values().stream().anyMatch("discord"::equals);

		// Single Discord post covers both global and any discord-mode member watches
		if ((globalMatch || memberDiscordMode) && discordReady && watchChatEnabled) {
			discord.notify(DiscordEvent.WATCHED_CHAT, true, DiscordText.watchedChat(sender, body, dm));
		}

		// DM each member who watches in dm-mode
		for (Map.Entry<String, String> e : memberWatchers.entrySet()) {
			if ("dm".equals(e.getValue())) {
				sendDm(e.getKey(), watchChatDm(sender, body, dm));
			}
		}

		// General chat relay
		if (config.logAllChat()) {
			relayBuffer.addLast(DiscordText.chatLine(sender, body, dm));
			while (relayBuffer.size() > RELAY_MAX_BUFFER) relayBuffer.pollFirst();
		}
	}

	/** Call once per client tick. */
	public void tick() {
		long now = System.currentTimeMillis();
		if (!config.logAllChat()) {
			relayBuffer.clear();
		} else if (!relayBuffer.isEmpty() && now - lastFlush >= RELAY_FLUSH_MILLIS) {
			flushRelay();
			lastFlush = now;
		}

		if (now - lastCheck < CHECK_INTERVAL_MILLIS) return;
		lastCheck = now;
		trackWatchedPresence();
	}

	/** Diff the tab list for any globally- or member-watched players joining/leaving. */
	private void trackWatchedPresence() {
		var handler = client.getNetworkHandler();
		if (handler == null) { onlineWatched.clear(); primed = false; return; }

		Set<String> current = new HashSet<>();
		for (var entry : handler.getPlayerList()) {
			String name = entry.getProfile() == null ? null : entry.getProfile().name();
			if (name != null && config.isAnyWatched(name)) current.add(name);
		}

		if (!primed) {
			onlineWatched.clear();
			onlineWatched.addAll(current);
			primed = true;
			return;
		}

		boolean discordReady = discord.isReady();
		boolean joinEnabled = config.discordEventEnabled(DiscordEvent.WATCHED_JOIN);
		boolean leaveEnabled = config.discordEventEnabled(DiscordEvent.WATCHED_LEAVE);

		// Joined
		for (String name : current) {
			if (!onlineWatched.contains(name)) {
				notifyWatchers(name, true, discordReady, joinEnabled,
						DiscordText.watchedJoin(config.language(), name),
						"[Watch] " + name + " joined the server.");
			}
		}
		// Left
		for (String name : onlineWatched) {
			if (!current.contains(name)) {
				notifyWatchers(name, false, discordReady, leaveEnabled,
						DiscordText.watchedLeave(config.language(), name),
						"[Watch] " + name + " left the server.");
			}
		}

		onlineWatched.clear();
		onlineWatched.addAll(current);
	}

	/**
	 * Fire the appropriate Discord event and/or DMs for a join or leave.
	 * One Discord post at most (global OR any discord-mode member watch collapses into one).
	 */
	private void notifyWatchers(String name, boolean joining, boolean discordReady, boolean eventEnabled,
	                             String discordText, String dmText) {
		boolean globalMatch = config.isWatched(name);
		Map<String, String> memberWatchers = config.watchersOf(name);
		boolean memberDiscordMode = !memberWatchers.isEmpty()
				&& memberWatchers.values().stream().anyMatch("discord"::equals);

		if ((globalMatch || memberDiscordMode) && discordReady && eventEnabled) {
			DiscordEvent event = joining ? DiscordEvent.WATCHED_JOIN : DiscordEvent.WATCHED_LEAVE;
			discord.notify(event, true, discordText);
		}
		for (Map.Entry<String, String> e : memberWatchers.entrySet()) {
			if ("dm".equals(e.getValue())) {
				sendDm(e.getKey(), dmText);
			}
		}
	}

	/** Drain buffered chat lines into one Discord message. */
	private void flushRelay() {
		StringBuilder sb = new StringBuilder();
		while (!relayBuffer.isEmpty()) {
			String line = relayBuffer.peekFirst();
			if (sb.length() > 0 && sb.length() + line.length() + 1 > RELAY_MAX_CHARS) break;
			relayBuffer.pollFirst();
			if (sb.length() > 0) sb.append('\n');
			sb.append(line);
		}
		if (sb.length() > 0) discord.chatLog(sb.toString());
	}

	/** Whisper a notification to a base member in-game, with optional antispam suffix. */
	private void sendDm(String player, String text) {
		if (client.player == null || client.player.networkHandler == null) return;
		String msg = config.appendRandomChars() ? text + " " + PlayerFeedback.randomSuffix() : text;
		client.player.networkHandler.sendChatCommand(config.whisperCommand() + " " + player + " " + msg);
	}

	private static String watchChatDm(String name, String body, boolean dm) {
		String b = body == null ? "" : body.strip();
		if (b.length() > 200) b = b.substring(0, 200) + "…";
		return "[Watch] " + name + (dm ? " (DM): " : ": ") + b;
	}
}
