package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.config.DiscordEvent;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.identity.IdentityResolver;
import fr.nyuway.stasisbot.model.StasisChamber;
import fr.nyuway.stasisbot.scan.ChamberIndex;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Watches who is within the bot's render distance. Two jobs:
 *
 * <ul>
 *   <li>announces enter/leave to Discord ({@link DiscordEvent#PLAYER_ENTER} /
 *       {@link DiscordEvent#PLAYER_LEAVE}), optionally enriching the "entered"
 *       line with the player's armour + held items when gear logging is on;</li>
 *   <li>always keeps a shared {@link RenderPresence} snapshot up to date — even
 *       when those events are off — so pings can be gated on a stranger being
 *       around and death messages can be scoped to players the bot actually saw.</li>
 * </ul>
 *
 * <p>Diff-based and throttled to once a second, so it stays cheap even on a
 * crowded server.
 */
public final class PlayerWatcher {

	/** How often to re-scan the visible player list. */
	private static final long CHECK_INTERVAL_MILLIS = 1000L;

	private final MinecraftClient client;
	private final StasisBotConfig config;
	private final ChamberIndex index;
	private final IdentityResolver identity;
	private final DiscordNotifier discord;
	private final PlayerFeedback feedback;
	private final RenderPresence presence;
	private final PlayerSessionTracker session;

	private final Set<String> present = new HashSet<>();
	private final Set<String> prevTab = new HashSet<>();   // who was online last tick (to split connect vs walk-in)
	private boolean primed = false;   // skip the first diff so joining doesn't spam every nearby player
	private long lastCheck = 0L;

	public PlayerWatcher(MinecraftClient client, StasisBotConfig config, ChamberIndex index,
	                     IdentityResolver identity, DiscordNotifier discord, RenderPresence presence,
	                     PlayerSessionTracker session) {
		this.client = client;
		this.config = config;
		this.index = index;
		this.identity = identity;
		this.discord = discord;
		this.presence = presence;
		this.session = session;
		this.feedback = new PlayerFeedback(client, config);
	}

	/** Call once per client tick. */
	public void tick() {
		long now = System.currentTimeMillis();
		if (now - lastCheck < CHECK_INTERVAL_MILLIS) return;
		lastCheck = now;

		ClientWorld world = client.world;
		ClientPlayerEntity self = client.player;
		if (world == null || self == null) { reset(); return; }

		String selfName = self.getGameProfile().name();
		Map<String, PlayerEntity> near = new HashMap<>();
		boolean outsiderHere = false;
		for (var p : world.getPlayers()) {
			String name = p.getGameProfile().name();
			if (name == null || name.equalsIgnoreCase(selfName)) continue;
			near.put(name, p);
			if (presence != null) presence.markSeen(name);
			if (!isBaseMember(name)) outsiderHere = true;
		}
		// Keep the shared snapshot current regardless of whether enter/leave is on.
		if (presence != null) {
			presence.setOutsiderPresent(outsiderHere);
			presence.prune();
		}

		// Everyone currently online (server-wide). Lets us tell a fresh connect from a
		// player merely walking into render, and a disconnect from one walking out.
		Set<String> tab = onlineNames(selfName);

		boolean announce = discord.isReady()
				&& (config.discordEventEnabled(DiscordEvent.PLAYER_ENTER)
						|| config.discordEventEnabled(DiscordEvent.PLAYER_LEAVE)
						|| config.discordEventEnabled(DiscordEvent.PLAYER_CONNECT)
						|| config.discordEventEnabled(DiscordEvent.PLAYER_DISCONNECT));

		// First pass after (re)gaining a world: remember who's here without notifying.
		if (!primed) {
			present.clear();
			present.addAll(near.keySet());
			prevTab.clear();
			prevTab.addAll(tab);
			primed = true;
			return;
		}

		// Record server-wide (re)connects (independent of the announce gate) so the
		// chamber watcher can ignore pearls that merely respawned on a reconnect.
		for (String name : tab) {
			if (!prevTab.contains(name)) session.markConnected(name);
		}
		session.prune();

		if (announce) {
			for (Map.Entry<String, PlayerEntity> e : near.entrySet()) {
				String name = e.getKey();
				if (present.contains(name)) continue;
				PlayerEntity p = e.getValue();
				boolean outsider = !isBaseMember(name);
				boolean connected = !prevTab.contains(name);   // wasn't online before → just joined
				DiscordEvent ev = connected ? DiscordEvent.PLAYER_CONNECT : DiscordEvent.PLAYER_ENTER;
				if (!config.discordEventEnabled(ev)) continue;
				String gear = config.discordEventDetails(ev) ? formatGear(p) : null;
				String msg = connected
						? DiscordText.connectedGear(config.language(), name, gear)
						: DiscordText.enterGear(config.language(), name, gear);
				feedback.debug("saw '" + name + "' " + (connected ? "CONNECT" : "ENTER")
						+ " (" + (outsider ? "outsider" : "base member") + ")");
				discord.notify(ev, outsider, msg, p.getBlockPos(), distanceTo(self, p));
			}
			for (String name : present) {
				if (near.containsKey(name)) continue;
				boolean outsider = !isBaseMember(name);
				boolean disconnected = !tab.contains(name);   // no longer online → left the server
				DiscordEvent ev = disconnected ? DiscordEvent.PLAYER_DISCONNECT : DiscordEvent.PLAYER_LEAVE;
				if (!config.discordEventEnabled(ev)) continue;
				String msg = disconnected
						? DiscordText.disconnected(config.language(), name)
						: DiscordText.leave(config.language(), name);
				feedback.debug("saw '" + name + "' " + (disconnected ? "DISCONNECT" : "LEAVE")
						+ " (" + (outsider ? "outsider" : "base member") + ")");
				discord.notify(ev, outsider, msg);
			}
		}

		present.clear();
		present.addAll(near.keySet());
		prevTab.clear();
		prevTab.addAll(tab);
	}

	private void reset() {
		present.clear();
		prevTab.clear();
		primed = false;
		if (presence != null) presence.setOutsiderPresent(false);
	}

	/** Names of everyone currently online (from the tab list), excluding the bot itself. */
	private Set<String> onlineNames(String selfName) {
		Set<String> names = new HashSet<>();
		var handler = client.getNetworkHandler();
		if (handler == null) return names;
		for (var entry : handler.getPlayerList()) {
			String name = entry.getProfile() == null ? null : entry.getProfile().name();
			if (name != null && !name.equalsIgnoreCase(selfName)) names.add(name);
		}
		return names;
	}

	/** Whole-block distance between the bot and a player, for the optional distance tag. */
	private static int distanceTo(ClientPlayerEntity self, PlayerEntity other) {
		return (int) Math.round(Math.sqrt(self.squaredDistanceTo(other)));
	}

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

	/** One short "main / off / armour" line describing what a player is wearing/holding. */
	private static String formatGear(PlayerEntity p) {
		StringBuilder sb = new StringBuilder();
		appendItem(sb, "main", p.getMainHandStack());
		appendItem(sb, "off", p.getOffHandStack());

		StringBuilder armor = new StringBuilder();
		appendArmor(armor, p.getEquippedStack(EquipmentSlot.HEAD));
		appendArmor(armor, p.getEquippedStack(EquipmentSlot.CHEST));
		appendArmor(armor, p.getEquippedStack(EquipmentSlot.LEGS));
		appendArmor(armor, p.getEquippedStack(EquipmentSlot.FEET));
		if (armor.length() > 0) {
			if (sb.length() > 0) sb.append(" | ");
			sb.append("armor: ").append(armor);
		}
		return sb.length() == 0 ? "(empty hands, no armor)" : sb.toString();
	}

	private static void appendItem(StringBuilder sb, String slot, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return;
		if (sb.length() > 0) sb.append(" | ");
		sb.append(slot).append(": ").append(itemName(stack));
	}

	private static void appendArmor(StringBuilder sb, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return;
		if (sb.length() > 0) sb.append(", ");
		sb.append(itemName(stack));
	}

	/** Item display name, with a ✨ marker when it's enchanted and a count when stacked. */
	private static String itemName(ItemStack stack) {
		String name = stack.getName().getString();
		if (stack.hasEnchantments()) name = "\u2728" + name;
		return stack.getCount() > 1 ? name + " x" + stack.getCount() : name;
	}
}

