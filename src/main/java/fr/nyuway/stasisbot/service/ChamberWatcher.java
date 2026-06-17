package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.activation.TrapState;
import fr.nyuway.stasisbot.config.DiscordEvent;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.entity.PearlDetector;
import fr.nyuway.stasisbot.i18n.Messages;
import fr.nyuway.stasisbot.identity.IdentityResolver;
import fr.nyuway.stasisbot.model.StasisChamber;
import fr.nyuway.stasisbot.scan.ChamberIndex;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static fr.nyuway.stasisbot.service.Proximity.distanceTo;

/**
 * Detects when a chamber gets a fresh pearl — i.e. someone (re)armed a stasis —
 * and announces it to Discord ({@link DiscordEvent#STASIS_RECHARGED}). It also
 * announces when a stasis is <em>triggered</em>: the reliable signal is the
 * suspended pearl vanishing (a quick button press would be missed by polling the
 * trigger's open state), after which it waits a moment to see whether anyone
 * teleported in ({@link DiscordEvent#STASIS_OPENED}) or the pearl simply broke.
 * Opens of an empty trap and trigger closes are still read from the block state.
 * Everything is diffed once a second; the very first pass only records a baseline.
 */
public final class ChamberWatcher {

	private static final long CHECK_INTERVAL_MILLIS = 1000L;
	/**
	 * How long to wait after detecting a fresh pearl before treating it as a real
	 * recharge. This gives {@link PlayerWatcher} time to record the connect in the
	 * {@link PlayerSessionTracker} so that {@link #isReconnectRespawn} can do its
	 * job even if the pearl and the watcher tick landed in the same 1-second window.
	 */
	private static final long FRESH_PEARL_PENDING_MILLIS = 1500L;
	/**
	 * How long to wait after a stasis releases its pearl before deciding whether a TP
	 * happened. Long enough for the teleported player to appear at the chamber in the
	 * client's render snapshot, short enough that the log still feels immediate.
	 */
	private static final long RELEASE_CONFIRM_MILLIS = 3500L;
	/**
	 * After a release the trapdoor's own open-state flip may only be polled a tick or two
	 * later; within this window we skip the "opened (empty)" announce so a single release
	 * isn't logged twice (once as a trigger, once as a bare open).
	 */
	private static final long RELEASE_SUPPRESS_MILLIS = 4000L;
	/** Max distance (blocks) for crediting a nearby player with a toggle/recharge. */
	private static final double ATTRIBUTION_RADIUS = 12.0;
	/** A player within this distance of the trigger just after a release is the one who teleported in. */
	private static final double TP_ARRIVAL_RADIUS = 8.0;

	private final MinecraftClient client;
	private final StasisBotConfig config;
	private final ChamberIndex index;
	private final IdentityResolver identity;
	private final PearlDetector pearls;
	private final DiscordNotifier discord;
	private final PlayerFeedback feedback;
	private final PlayerSessionTracker session;
	private final BotActivity botActivity;

	/** Trigger block (as a long) → did it hold a pearl on the last scan? */
	private final Map<Long, Boolean> loaded = new HashMap<>();
	/** Trigger block (as a long) → was it open/active (released) on the last scan? */
	private final Map<Long, Boolean> openState = new HashMap<>();
	/** Trigger block (as a long) → name of the player whose pearl is currently suspended there. */
	private final Map<Long, String> loadedThrower = new HashMap<>();
	/** Trigger block (as a long) → epoch-ms of its last pearl release, to drop the duplicate empty-open. */
	private final Map<Long, Long> recentRelease = new HashMap<>();
	/**
	 * Fresh-pearl appearances waiting before being processed. Maps trigger-block-long
	 * to the epoch-ms when the transition was first detected. Entries older than
	 * {@link #FRESH_PEARL_PENDING_MILLIS} are processed on the next tick.
	 */
	private final Map<Long, Long> pendingFreshPearls = new HashMap<>();
	/**
	 * Stasis releases waiting for TP confirmation. When a suspended pearl vanishes we
	 * record who was nearby and wait {@link #RELEASE_CONFIRM_MILLIS}; if a new player
	 * then stands at the chamber they teleported in, otherwise the pearl just broke.
	 *
	 * @param chamber       the chamber whose pearl was released
	 * @param triggeredBy   nearest player at release time (who set it off), may be null
	 * @param thrower       player whose pearl was suspended here (the one a stasis TPs), may be null
	 * @param nearAtRelease lower-cased names already next to the trigger before the release
	 * @param detectedAt    epoch-ms when the release was seen
	 */
	private record PendingRelease(StasisChamber chamber, String triggeredBy, String thrower,
	                              Set<String> nearAtRelease, long detectedAt) {}
	private final Map<Long, PendingRelease> pendingReleases = new HashMap<>();
	/** Trigger block (as a long) → lower-cased names that were next to it on the previous scan. */
	private final Map<Long, Set<String>> nearbyBefore = new HashMap<>();
	private boolean primed = false;
	private long lastCheck = 0L;

	public ChamberWatcher(MinecraftClient client, StasisBotConfig config, ChamberIndex index,
	                      IdentityResolver identity, PearlDetector pearls, DiscordNotifier discord,
	                      PlayerSessionTracker session, BotActivity botActivity) {
		this.client = client;
		this.config = config;
		this.index = index;
		this.identity = identity;
		this.pearls = pearls;
		this.discord = discord;
		this.session = session;
		this.botActivity = botActivity;
		this.feedback = new PlayerFeedback(client, config);
	}

	/** Call once per client tick. */
	public void tick() {
		boolean rechargeOn = config.discordEventEnabled(DiscordEvent.STASIS_RECHARGED);
		boolean wrongOn = config.discordEventEnabled(DiscordEvent.STASIS_WRONG_PEARL);
		boolean openOn = config.discordEventEnabled(DiscordEvent.STASIS_OPENED);
		boolean closeOn = config.discordEventEnabled(DiscordEvent.STASIS_CLOSED);
		if (!discord.isReady() || !(rechargeOn || wrongOn || openOn || closeOn)) {
			reset();
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastCheck < CHECK_INTERVAL_MILLIS) return;
		lastCheck = now;

		ClientWorld world = client.world;
		ClientPlayerEntity self = client.player;
		if (world == null || self == null) { reset(); return; }

		List<StasisChamber> chambers = index.chambers(world, self.getBlockPos());
		// A trap the bot just fired/re-armed flips its open state too; don't report that
		// as a player opening/closing the stasis (the bot announces its own actions).
		boolean botToggled = botActivity.recentlyToggledTrigger();
		Map<Long, Boolean> current = new HashMap<>();
		Map<Long, Boolean> currentOpen = new HashMap<>();
		Map<Long, Set<String>> currentNearby = new HashMap<>();
		for (StasisChamber c : chambers) {
			long pos = c.trigger().asLong();
			boolean hasPearl = pearls.hasOwnPearl(world, c, chambers);
			boolean hadPearl = Boolean.TRUE.equals(loaded.get(pos));
			current.put(pos, hasPearl);

			// While a pearl hangs here, remember whose it is: a stasis pearl always teleports its
			// own thrower when released — even when that thrower is the very player who set the trap
			// off — so this is how the teleport gets credited to the right person a moment later.
			if (hasPearl) {
				String th = pearls.ownPearlThrower(world, c, chambers);
				if (th != null) loadedThrower.put(pos, th);
			}

			// Snapshot who stands next to this trigger now, keeping last tick's set as the
			// baseline: a player who only appears *after* a release is the one who TP'd in.
			Set<String> nearPrev = nearbyBefore.getOrDefault(pos, Set.of());
			currentNearby.put(pos, playersNear(world, self, c.trigger(), TP_ARRIVAL_RADIUS));

			// empty → loaded means a pearl just appeared: a recharge (or a misplacement).
			// Don't process immediately: queue it for FRESH_PEARL_PENDING_MILLIS so the
			// PlayerWatcher has time to record any same-second reconnect first.
			if (primed && hasPearl && Boolean.FALSE.equals(loaded.get(pos))) {
				pendingFreshPearls.putIfAbsent(pos, now);
				// A pearl reappearing while a release still awaits TP confirmation means the trap was
				// simply re-armed (pearl re-thrown, or a one-tick detection flicker), NOT a teleport:
				// a real TP consumes the pearl and the chamber stays empty. Drop the pending release.
				pendingReleases.remove(pos);
			}
			// If the pearl disappeared again, cancel any pending recharge entry.
			if (!hasPearl) pendingFreshPearls.remove(pos);

			// loaded → empty is the RELIABLE "stasis was triggered" signal: the suspended pearl
			// vanished. Polling the trigger's open state alone misses quick button presses, so we
			// key the open event off the pearl instead. Unless the bot fired it, queue a release
			// and confirm a few seconds later whether anyone teleported in.
			if (primed && openOn && !botToggled && !hasPearl && hadPearl) {
				String triggeredBy = nearestPlayer(world, self, c);
				String thrower = loadedThrower.get(pos);
				feedback.debug("stasis '" + c.label() + "' pearl released"
						+ (thrower != null ? " (" + thrower + "'s pearl)" : "")
						+ (triggeredBy != null ? " — near " + triggeredBy : "") + " — confirming TP…");
				recentRelease.put(pos, now);
				pendingReleases.put(pos, new PendingRelease(c, triggeredBy, thrower, nearPrev, now));
			}

			// Also track the trigger's persistent open/closed state, for EMPTY-trap opens and
			// closes (trapdoor/lever/door). An open of a trap that holds a pearl is the release
			// case handled above, so it's skipped there to avoid a double announcement.
			Boolean active = TrapState.isTriggerActive(world, c.trigger());
			if (active != null) {
				currentOpen.put(pos, active);
				Boolean was = openState.get(pos);
				if (primed && was != null && was != active.booleanValue()) {
					feedback.debug("trap '" + c.label() + "' trigger "
							+ (active ? "opened" : "closed") + (botToggled ? " (bot — suppressed)" : ""));
					if (!botToggled) {
						handleTriggerToggle(world, self, c, active, hasPearl, hadPearl, openOn, closeOn, now);
					}
				}
			}
		}

		loaded.clear();
		loaded.putAll(current);
		openState.clear();
		openState.putAll(currentOpen);
		nearbyBefore.clear();
		nearbyBefore.putAll(currentNearby);
		// Forget remembered throwers for chambers that no longer hold a pearl (a release this
		// tick has already copied the thrower into its PendingRelease above).
		loadedThrower.keySet().removeIf(k -> !Boolean.TRUE.equals(current.get(k)));
		primed = true;

		// Process pending fresh-pearl entries that have matured (waited long enough for
		// PlayerWatcher to record any same-second reconnect in the session tracker).
		Iterator<Map.Entry<Long, Long>> it = pendingFreshPearls.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Long, Long> e = it.next();
			if (now - e.getValue() < FRESH_PEARL_PENDING_MILLIS) continue;
			it.remove();
			long triggerLong = e.getKey();
			for (StasisChamber c : chambers) {
				if (c.trigger().asLong() == triggerLong
						&& pearls.hasOwnPearl(world, c, chambers)) {
					handleFreshPearl(world, self, c, chambers, rechargeOn, wrongOn);
				}
			}
		}

		// Process pending releases that have waited long enough for TP confirmation. A player
		// who newly turned up right next to the trigger is the one who teleported in; if nobody
		// arrived, the pearl simply broke (its owner was offline or somewhere out of range).
		Iterator<Map.Entry<Long, PendingRelease>> rit = pendingReleases.entrySet().iterator();
		while (rit.hasNext()) {
			Map.Entry<Long, PendingRelease> e = rit.next();
			if (now - e.getValue().detectedAt() < RELEASE_CONFIRM_MILLIS) continue;
			rit.remove();
			PendingRelease pr = e.getValue();
			StasisChamber c = pr.chamber();
			// If a pearl is back in the chamber, it was re-armed (re-thrown), not teleported out:
			// a genuine teleport consumes the pearl and leaves the chamber empty. Don't report a TP.
			if (pearls.hasOwnPearl(world, c, chambers)) {
				feedback.debug("stasis '" + c.label() + "' release cancelled — pearl is back (re-armed, no TP)");
				continue;
			}
			// A stasis pearl always teleports its own thrower, so if we know whose pearl it was and
			// they're now standing at the chamber, that's the teleport — even when they're the same
			// person who triggered it. Otherwise fall back to spotting a brand-new face arriving.
			String tpTarget;
			if (pr.thrower() != null && playerNear(world, self, c.trigger(), TP_ARRIVAL_RADIUS, pr.thrower())) {
				tpTarget = pr.thrower();
			} else {
				tpTarget = newcomerNear(world, self, c.trigger(), TP_ARRIVAL_RADIUS,
						pr.nearAtRelease(), pr.triggeredBy());
			}
			logStasisReleased(world, self, c, pr.triggeredBy(), tpTarget, tpTarget != null, openOn);
		}
	}

	/** A pearl just landed in {@code c}: tell apart a correct recharge from a wrong-chamber drop. */
	private void handleFreshPearl(ClientWorld world, ClientPlayerEntity self, StasisChamber c,
	                              List<StasisChamber> chambers, boolean rechargeOn, boolean wrongOn) {
		BlockPos trigger = c.trigger();
		int dist = distanceTo(self, trigger);
		String owner = pearls.ownPearlThrower(world, c, chambers);

		// Not a real recharge: a player's stasis pearl despawns on logout and respawns on
		// login, which looks just like a fresh pearl. If this chamber belongs to (or the
		// pearl is owned by) someone who just reconnected, ignore it.
		if (isReconnectRespawn(owner, c)) {
			feedback.debug("ignored '" + c.label() + "' recharge — pearl respawned after a reconnect");
			return;
		}

		// Wrong chamber: we know whose pearl it is and their name isn't on this sign.
		boolean wrong = owner != null && !c.matchesAny(identity.tokensFor(owner));
		if (wrong) {
			if (wrongOn) {
				feedback.debug("WRONG pearl: '" + owner + "' in stasis '" + c.label() + "'");
				feedback.whisper(owner, Messages.Key.WRONG_PEARL, c.label());
				discord.notify(DiscordEvent.STASIS_WRONG_PEARL, !isBaseMember(owner),
						DiscordText.wrongPearl(config.language(), owner, c.label()), trigger, dist);
			}
			return; // a misplacement isn't also a "recharge"
		}

		if (rechargeOn) {
			String who = owner != null ? owner : nearestPlayer(world, self, c);
			feedback.debug("stasis '" + c.label() + "' was recharged"
					+ (who != null ? " by " + who : "") + " (pearl appeared)");
			discord.notify(DiscordEvent.STASIS_RECHARGED,
					DiscordText.recharged(config.language(), c.label(), who), trigger, dist);
		}
	}

	/**
	 * Whether a freshly-detected pearl in {@code c} is really just a stasis pearl that
	 * respawned because its owner reconnected (and so is <em>not</em> a recharge). True
	 * when the resolved pearl owner reconnected within the grace window, or — when the
	 * owner can't be resolved — when this chamber's sign matches anyone who just did.
	 */
	private boolean isReconnectRespawn(String owner, StasisChamber c) {
		if (owner != null && session.recentlyConnected(owner)) return true;
		for (String name : session.recentNames()) {
			if (c.matchesAny(identity.tokensFor(name))) return true;
		}
		return false;
	}

	/**
	 * A trigger's persistent open/closed state flipped. Pearl releases (the important
	 * "someone triggered the stasis" case) are detected separately off the vanished
	 * pearl, so here we only announce opens of an <em>empty</em> trap and closes.
	 */
	private void handleTriggerToggle(ClientWorld world, ClientPlayerEntity self, StasisChamber c,
	                                 boolean nowOpen, boolean hasPearlNow, boolean hadPearl,
	                                 boolean openOn, boolean closeOn, long now) {
		if (nowOpen) {
			if (!openOn || hasPearlNow || hadPearl) return; // a loaded trap opening is the release case
			if (recentlyReleased(c.trigger().asLong(), now)) return; // the release path already announced this open
			String who = nearestPlayer(world, self, c);
			feedback.debug("stasis '" + c.label() + "' opened"
					+ (who != null ? " by " + who : "") + " (empty)");
			logStasisOpened(world, self, c, who, false, false, openOn);
		} else {
			if (!closeOn) return;
			String who = nearestPlayer(world, self, c);
			BlockPos trigger = c.trigger();
			int dist = distanceTo(self, trigger);
			boolean outsider = who == null || !isBaseMember(who);
			feedback.debug("stasis '" + c.label() + "' closed" + (who != null ? " by " + who : ""));
			discord.notify(DiscordEvent.STASIS_CLOSED, outsider,
					DiscordText.stasisClosed(config.language(), c.label(), who), trigger, dist);
		}
	}

	/** Emit the STASIS_OPENED Discord notification with pearl and TP context. */
	private void logStasisOpened(ClientWorld world, ClientPlayerEntity self, StasisChamber c,
	                             String who, boolean hadPearl, boolean tpConfirmed, boolean openOn) {
		if (!openOn) return;
		BlockPos trigger = c.trigger();
		int dist = distanceTo(self, trigger);
		boolean outsider = who != null ? !isBaseMember(who) : true;
		discord.notify(DiscordEvent.STASIS_OPENED, outsider,
				DiscordText.stasisOpened(config.language(), c.label(), who, hadPearl, tpConfirmed),
				trigger, dist);
	}

	/** Emit STASIS_OPENED for a pearl release: who set it off and whether anyone teleported in. */
	private void logStasisReleased(ClientWorld world, ClientPlayerEntity self, StasisChamber c,
	                               String triggeredBy, String tpTarget, boolean tpConfirmed, boolean openOn) {
		if (!openOn) return;
		BlockPos trigger = c.trigger();
		int dist = distanceTo(self, trigger);
		String who = triggeredBy != null ? triggeredBy : tpTarget;
		boolean outsider = who == null || !isBaseMember(who);
		feedback.debug("stasis '" + c.label() + "' released — "
				+ (tpConfirmed ? "TP'd " + tpTarget : "no TP (pearl broke)"));
		discord.notify(DiscordEvent.STASIS_OPENED, outsider,
				DiscordText.stasisReleased(config.language(), c.label(), triggeredBy, tpTarget, tpConfirmed),
				trigger, dist);
	}

	/** Lower-cased names of other players within {@code radius} of {@code trigger} right now. */
	private Set<String> playersNear(ClientWorld world, ClientPlayerEntity self, BlockPos trigger, double radius) {
		Vec3d centre = Vec3d.ofCenter(trigger);
		String selfName = self.getGameProfile().name();
		double rSq = radius * radius;
		Set<String> near = new HashSet<>();
		for (var p : world.getPlayers()) {
			String name = p.getGameProfile().name();
			if (name == null || name.equalsIgnoreCase(selfName)) continue;
			if (p.squaredDistanceTo(centre) <= rSq) near.add(name.toLowerCase());
		}
		return near;
	}

	/**
	 * A player now within {@code radius} of {@code trigger} who was neither there
	 * {@code before} the release nor the one who {@code triggeredBy} set it off — i.e.
	 * somebody who teleported in. {@code null} when nobody new arrived (pearl just broke).
	 */
	private String newcomerNear(ClientWorld world, ClientPlayerEntity self, BlockPos trigger,
	                            double radius, Set<String> before, String triggeredBy) {
		Vec3d centre = Vec3d.ofCenter(trigger);
		String selfName = self.getGameProfile().name();
		double rSq = radius * radius;
		for (var p : world.getPlayers()) {
			String name = p.getGameProfile().name();
			if (name == null || name.equalsIgnoreCase(selfName)) continue;
			if (p.squaredDistanceTo(centre) > rSq) continue;
			if (before.contains(name.toLowerCase())) continue;
			if (triggeredBy != null && name.equalsIgnoreCase(triggeredBy)) continue;
			return name;
		}
		return null;
	}

	/** Whether a player with {@code name} (not the bot) is within {@code radius} of {@code trigger} now. */
	private boolean playerNear(ClientWorld world, ClientPlayerEntity self, BlockPos trigger,
	                           double radius, String name) {
		if (name == null) return false;
		Vec3d centre = Vec3d.ofCenter(trigger);
		String selfName = self.getGameProfile().name();
		double rSq = radius * radius;
		for (var p : world.getPlayers()) {
			String n = p.getGameProfile().name();
			if (n == null || n.equalsIgnoreCase(selfName)) continue;
			if (n.equalsIgnoreCase(name) && p.squaredDistanceTo(centre) <= rSq) return true;
		}
		return false;
	}

	/** True if this trigger released a pearl within {@link #RELEASE_SUPPRESS_MILLIS}. */
	private boolean recentlyReleased(long triggerLong, long now) {
		Long t = recentRelease.get(triggerLong);
		return t != null && now - t < RELEASE_SUPPRESS_MILLIS;
	}

	/**
	 * Best-effort guess at who recharged a chamber: the nearest other player to
	 * its trigger within {@link #ATTRIBUTION_RADIUS}, or {@code null} when nobody
	 * is close enough to credit. The bot itself is always excluded.
	 */
	private String nearestPlayer(ClientWorld world, ClientPlayerEntity self, StasisChamber c) {
		Vec3d centre = Vec3d.ofCenter(c.trigger());
		String selfName = self.getGameProfile().name();
		String best = null;
		double bestSq = ATTRIBUTION_RADIUS * ATTRIBUTION_RADIUS;
		for (var p : world.getPlayers()) {
			String name = p.getGameProfile().name();
			if (name == null || name.equalsIgnoreCase(selfName)) continue;
			double d = p.squaredDistanceTo(centre);
			if (d <= bestSq) {
				bestSq = d;
				best = name;
			}
		}
		return best;
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

	private void reset() {
		loaded.clear();
		openState.clear();
		pendingFreshPearls.clear();
		pendingReleases.clear();
		nearbyBefore.clear();
		loadedThrower.clear();
		recentRelease.clear();
		primed = false;
	}
}
