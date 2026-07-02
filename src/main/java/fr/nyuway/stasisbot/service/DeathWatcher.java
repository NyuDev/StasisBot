package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.config.DiscordEvent;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.identity.IdentityResolver;
import fr.nyuway.stasisbot.model.StasisChamber;
import fr.nyuway.stasisbot.scan.ChamberIndex;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static fr.nyuway.stasisbot.service.Proximity.distanceTo;

/**
 * Reports player deaths that happen around the bot to Discord ({@link
 * DiscordEvent#PLAYER_DIED}). Deaths can't be observed directly client-side —
 * other players' health isn't synced — so this listens to the server's broadcast
 * death messages and, crucially, only reports a death when the victim was
 * <em>actually seen</em> in the bot's render distance recently (tracked by {@link
 * RenderPresence}). That render-distance gate is what keeps it from echoing every
 * death on the whole server.
 *
 * <p>Attribution ("by whom") is taken from the death message itself when it names
 * a killer (e.g. "… was slain by X"); otherwise it's an environmental death and
 * only the victim is reported. Best-effort by nature — it depends on the server
 * using readable death messages.
 */
public final class DeathWatcher {

	/** Substrings that strongly indicate a vanilla death message. */
	private static final String[] DEATH_HINTS = {
			"was slain", "was shot", "was killed", "was blown up", "was fireballed",
			"was pricked", "was squashed", "was poked to death", "was impaled", "was skewered",
			"was struck by lightning", "was burnt", "was roasted", "was frozen",
			"was stung", "was obliterated", "was pummeled", "was squished",
			"blew up", "drowned", "experienced kinetic energy", "suffocated",
			"starved to death", "froze to death", "burned to death", "withered away",
			"fell from", "fell off", "fell out of", "fell into", "fell too far",
			"hit the ground too hard", "was doomed", "tried to swim in lava",
			"went up in flames", "walked into fire", "walked into a cactus",
			"walked into the danger zone", "discovered the floor was lava",
			"didn't want to live",
	};

	/** System lines that start with a player name but are NOT deaths. */
	private static final String[] NOT_DEATH = {
			"joined the game", "left the game", "has made the advancement",
			"has reached the goal", "has completed the challenge", "whispers",
	};

	private final MinecraftClient client;
	private final StasisBotConfig config;
	private final ChamberIndex index;
	private final IdentityResolver identity;
	private final DiscordNotifier discord;
	private final RenderPresence presence;
	private final BotDeathInfo deathInfo;
	private final PlayerFeedback feedback;

	public DeathWatcher(MinecraftClient client, StasisBotConfig config, ChamberIndex index,
	                    IdentityResolver identity, DiscordNotifier discord,
	                    RenderPresence presence, BotDeathInfo deathInfo) {
		this.client = client;
		this.config = config;
		this.index = index;
		this.identity = identity;
		this.discord = discord;
		this.presence = presence;
		this.deathInfo = deathInfo;
		this.feedback = new PlayerFeedback(client, config);
	}

	/** Hook the server's system messages (death messages are broadcast as these). */
	public void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) return;
			handle(message.getString());
		});
	}

	private void handle(String raw) {
		if (raw == null || raw.isBlank()) return;

		String line = raw.trim();
		String lower = line.toLowerCase(Locale.ROOT);

		for (String skip : NOT_DEATH) {
			if (lower.contains(skip)) return;
		}
		boolean looksLikeDeath = false;
		for (String hint : DEATH_HINTS) {
			if (lower.contains(hint)) { looksLikeDeath = true; break; }
		}
		if (!looksLikeDeath) return;

		String victim = firstToken(line);
		if (victim == null) return;

		ClientPlayerEntity self = client.player;
		String selfName = self == null ? null : self.getGameProfile().name();

		// The bot itself: stash the cause so the BOT_DIED announcement can quote it.
		// This must run regardless of the PLAYER_DIED toggle (it's a different event).
		if (selfName != null && victim.equalsIgnoreCase(selfName)) {
			if (deathInfo != null) {
				deathInfo.record(causePhrase(line, victim));
				// Capture who was in render distance at the moment of death.
				ClientWorld world = client.world;
				if (world != null) {
					List<String> nearby = new ArrayList<>();
					for (PlayerEntity p : world.getPlayers()) {
						String name = p.getGameProfile().name();
						if (!name.equalsIgnoreCase(selfName)) nearby.add(name);
					}
					deathInfo.recordNearby(nearby);
				}
			}
			feedback.debug("bot death message captured: " + line);
			return;
		}

		if (!discord.isReady() || !config.discordEventEnabled(DiscordEvent.PLAYER_DIED)) return;
		// Render-distance gate: only report someone the bot actually saw nearby.
		if (presence == null || !presence.recentlySeen(victim)) return;

		String killer = extractKiller(line);
		boolean outsider = !isBaseMember(victim);
		BlockPos pos = victimPos(victim);
		int dist = (pos == null || self == null) ? -1 : distanceTo(self, pos);
		feedback.debug("death seen: '" + victim + "'" + (killer != null ? " by '" + killer + "'" : "") + " — " + line);
		discord.notify(DiscordEvent.PLAYER_DIED, outsider,
				DiscordText.playerDied(config.language(), victim, killer), pos, dist);
	}

	/** The cause phrase: the death line with the leading victim name stripped. */
	private static String causePhrase(String line, String victim) {
		String rest = line.length() > victim.length() ? line.substring(victim.length()).trim() : line;
		if (rest.endsWith(".")) rest = rest.substring(0, rest.length() - 1).trim();
		return rest.isBlank() ? null : rest;
	}

	/** The victim's current block position if they're still loaded nearby, else null. */
	private BlockPos victimPos(String victim) {
		ClientWorld world = client.world;
		if (world == null) return null;
		for (PlayerEntity p : world.getPlayers()) {
			if (victim.equalsIgnoreCase(p.getGameProfile().name())) return p.getBlockPos();
		}
		return null;
	}

	/** The first whitespace-delimited token of the line (the victim's name), or null. */
	private static String firstToken(String line) {
		int sp = line.indexOf(' ');
		String tok = sp < 0 ? line : line.substring(0, sp);
		tok = tok.trim();
		return isPlausibleName(tok) ? tok : null;
	}

	/**
	 * Pull the killer out of "… by &lt;name&gt; [using &lt;weapon&gt;]" when present, returning a
	 * plausible player/mob name, or null for an environmental death.
	 */
	private static String extractKiller(String line) {
		int by = line.toLowerCase(Locale.ROOT).lastIndexOf(" by ");
		if (by < 0) return null;
		String rest = line.substring(by + 4).trim();
		int using = rest.toLowerCase(Locale.ROOT).indexOf(" using ");
		if (using >= 0) rest = rest.substring(0, using).trim();
		// Drop a trailing period and take the first token only.
		if (rest.endsWith(".")) rest = rest.substring(0, rest.length() - 1).trim();
		String first = firstToken(rest);
		return first;
	}

	/** A loose check that a token looks like a Minecraft name (avoids matching brackets/sentences). */
	private static boolean isPlausibleName(String s) {
		if (s == null || s.length() < 2 || s.length() > 16) return false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
		}
		return true;
	}

	/** Whether the named player matches a detected stasis sign (i.e. is a base member). */
	private boolean isBaseMember(String name) {
		ClientWorld world = client.world;
		ClientPlayerEntity self = client.player;
		if (world == null || self == null) return false;
		Set<String> tokens = identity.tokensFor(name);
		if (tokens.isEmpty()) return false;
		for (StasisChamber c : index.chambers(world, self.getBlockPos())) {
			if (c.matchesAny(tokens)) return true;
		}
		return false;
	}
}
