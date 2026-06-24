package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.config.DiscordEvent;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Player surveillance, all to Discord and all opt-in (no-op until configured):
 *
 * <ul>
 *   <li>watched players — their chat lines ({@link DiscordEvent#WATCHED_CHAT}) and
 *       their server connects/disconnects ({@link DiscordEvent#WATCHED_JOIN} /
 *       {@link DiscordEvent#WATCHED_LEAVE}); the list is empty by default and
 *       managed with {@code !sb watch add/remove};</li>
 *   <li>an optional relay of <em>all</em> chat to a (usually separate) webhook,
 *       off by default and batched so it stays under Discord's rate limit.</li>
 * </ul>
 *
 * <p>Chat is fed in from the chat pipeline; joins/leaves are read from the tab list
 * once a second.
 */
public final class SurveillanceService {

	private static final long CHECK_INTERVAL_MILLIS = 1000L;
	/** Batch window for the chat relay — one Discord post per this interval at most. */
	private static final long RELAY_FLUSH_MILLIS = 2500L;
	/** Cap the relay backlog so heavy chat can't grow it without bound. */
	private static final int RELAY_MAX_BUFFER = 300;
	/** Keep a relay post under Discord's 2000-char message limit. */
	private static final int RELAY_MAX_CHARS = 1800;

	private final MinecraftClient client;
	private final StasisBotConfig config;
	private final DiscordNotifier discord;

	private final Set<String> onlineWatched = new HashSet<>();   // watched players online on the last scan
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
		if (config.isWatched(sender) && discord.isReady()
				&& config.discordEventEnabled(DiscordEvent.WATCHED_CHAT)) {
			discord.notify(DiscordEvent.WATCHED_CHAT, true, DiscordText.watchedChat(sender, body, dm));
		}
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

	/** Diff the tab list for watched players joining/leaving the server. */
	private void trackWatchedPresence() {
		var handler = client.getNetworkHandler();
		if (handler == null) { onlineWatched.clear(); primed = false; return; }

		Set<String> current = new HashSet<>();
		for (var entry : handler.getPlayerList()) {
			String name = entry.getProfile() == null ? null : entry.getProfile().name();
			if (name != null && config.isWatched(name)) current.add(name);
		}

		if (!primed) {                 // first scan after (re)connect: seed without announcing
			onlineWatched.clear();
			onlineWatched.addAll(current);
			primed = true;
			return;
		}

		boolean ready = discord.isReady();
		if (ready && config.discordEventEnabled(DiscordEvent.WATCHED_JOIN)) {
			for (String name : current) {
				if (!onlineWatched.contains(name)) {
					discord.notify(DiscordEvent.WATCHED_JOIN, true, DiscordText.watchedJoin(config.language(), name));
				}
			}
		}
		if (ready && config.discordEventEnabled(DiscordEvent.WATCHED_LEAVE)) {
			for (String name : onlineWatched) {
				if (!current.contains(name)) {
					discord.notify(DiscordEvent.WATCHED_LEAVE, true, DiscordText.watchedLeave(config.language(), name));
				}
			}
		}
		onlineWatched.clear();
		onlineWatched.addAll(current);
	}

	/** Drain as many buffered lines as fit into one Discord message and send them. */
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
}
