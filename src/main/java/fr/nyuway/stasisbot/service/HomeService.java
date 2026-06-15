package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.StasisBot;
import fr.nyuway.stasisbot.activation.MeteorBridge;
import fr.nyuway.stasisbot.activation.Navigation;
import fr.nyuway.stasisbot.activation.Navigator;
import fr.nyuway.stasisbot.activation.PlayerActions;
import fr.nyuway.stasisbot.activation.StasisActivator;
import fr.nyuway.stasisbot.activation.TrapState;
import fr.nyuway.stasisbot.config.MasterCommands;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.entity.PearlDetector;
import fr.nyuway.stasisbot.entity.PlayerPresence;
import fr.nyuway.stasisbot.i18n.Messages;
import fr.nyuway.stasisbot.identity.IdentityResolver;
import fr.nyuway.stasisbot.model.StasisChamber;
import fr.nyuway.stasisbot.scan.ChamberIndex;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Orchestrates home requests as a one-at-a-time queue driven by the client tick.
 * The full lifecycle of a single request:
 * <pre>
 *   resolve the chamber bearing the sender's name (no sign → ignore them)
 *     → safety gate: sender online? server not lagging?
 *     → if out of reach, walk to it; re-read the sign on arrival
 *     → fire the trigger to release the pearl
 *     → confirm the player actually appears; else try another trap
 *     → reset the trap, hand them a fresh pearl
 *     → walk back to the start (or a fixed return point)
 * </pre>
 *
 * <p>This class is intentionally only the <em>orchestration</em>: cohesive
 * sub-concerns live in their own collaborators — {@link RequestQueue} (who is
 * waiting), {@link AnchorMemory} (where home is), {@link RestockController}
 * (chest refills) and {@link PlayerFeedback} (all messaging). Everything runs on
 * the client thread (the listener marshals onto it).
 */
public final class HomeService {

	/** How close a player must spawn to the trigger to count as "arrived". */
	private static final double ARRIVAL_RADIUS = 6.0;
	/** Distance (blocks) within which a player is considered "still in the chamber". */
	private static final double EXIT_RADIUS = 2.0;
	/** Minimum settle time (ms) after a TP before we even consider the player has left. */
	private static final long AFTER_MIN_WAIT_MILLIS = 2_500L;
	/** Hard cap on waiting for a lag spike to clear before giving up. */
	private static final long LAG_WAIT_CAP_MILLIS = 15_000L;
	/** Assumed travel speed (blocks/second) for the ETA estimate. */
	private static final double EST_SPEED_BPS = 4.0;
	/** Max time (ms) to wait for the player to leave the stasis zone before reopening the trap. */
	private static final long AFTER_WAIT_CAP_MILLIS = 30_000L;

	private enum Phase { IDLE, SAFETY, MOVING, FIRING, VERIFY, AFTER, RETURN, RESTOCK }

	private final MinecraftClient client;
	private final StasisBotConfig config;
	private final ChamberIndex index;
	private final IdentityResolver identity;
	private final PearlDetector pearls;
	private final StasisActivator activator;
	private final Navigation navigator;
	private final LagMonitor lag;

	private final PlayerFeedback feedback;
	private final RequestQueue requests = new RequestQueue();
	private final AnchorMemory anchor = new AnchorMemory();
	private final RestockController restock;

	private Phase phase = Phase.IDLE;
	private String currentSender;
	private Set<String> currentTokens = Set.of();
	private List<StasisChamber> candidates = new ArrayList<>();
	private int candidateIdx = 0;
	private StasisChamber currentTarget;

	private BlockPos returnTarget;    // where it should walk back to
	private boolean teleportConfirmed;
	private boolean pearlGiven;       // did we already hand the player their fresh pearl?

	private long fireAt;
	private long safetyDeadline;
	private long firingDeadline;
	private long afterWaitStart;      // when we started waiting for the player to leave

	public HomeService(MinecraftClient client, StasisBotConfig config, ChamberIndex index,
	                   IdentityResolver identity, PearlDetector pearls, StasisActivator activator,
	                   LagMonitor lag) {
		this.client = client;
		this.config = config;
		this.index = index;
		this.identity = identity;
		this.pearls = pearls;
		this.activator = activator;
		this.lag = lag;
		this.navigator = new Navigation(config);
		this.feedback = new PlayerFeedback(client, config);
		this.restock = new RestockController(client, config, feedback);
	}

	// --- chat entry points ---------------------------------------------------

	/** Single front door for every chat line (may be called off the client thread). */
	public void onChatMessage(String sender, String body) {
		if (sender == null || body == null) return;
		if (MasterCommands.looksLikeCommand(config, body)) {
			final String s = sender, b = body;
			client.execute(() -> handleCommand(s, b));
			return;
		}
		if (containsTrigger(body)) onHomeRequest(sender);
	}

	private void handleCommand(String sender, String body) {
		// Until a master is claimed, anyone may configure (so the owner can run
		// "!sb master <name>" once to lock it down); after that, master-only.
		if (!config.master().isBlank() && !config.isMaster(sender)) return;
		MasterCommands.Result r = MasterCommands.apply(config, body);
		if (r.handled()) feedback.reply(sender, r.key(), r.args());
	}

	private boolean containsTrigger(String body) {
		return body.toLowerCase(Locale.ROOT).contains(config.triggerWord().toLowerCase(Locale.ROOT));
	}

	/** Entry point from the chat listener (may be invoked off the client thread). */
	public void onHomeRequest(String senderName) {
		if (senderName == null || senderName.isBlank()) return;
		if (requests.shouldDebounce(senderName)) return;
		client.execute(() -> enqueue(senderName));
	}

	private void enqueue(String senderName) {
		ClientPlayerEntity self = client.player;
		if (self == null) return;
		if (senderName.equalsIgnoreCase(self.getGameProfile().name())) return; // ignore the bot itself
		if (senderName.equalsIgnoreCase(currentSender)) return;                // already being served
		if (!requests.offer(senderName)) return;                               // already queued

		int ahead = requests.size() - 1 + (phase != Phase.IDLE ? 1 : 0);
		if (ahead > 0) {
			feedback.whisper(senderName, Messages.Key.QUEUED, ahead);
		}
	}

	// --- tick dispatch -------------------------------------------------------

	/** Drive the active request; call once per client tick. */
	public void tick() {
		// Meteor Anti-AFK: keep it off while the bot is busy, restore it once fully idle.
		// No-op when Meteor isn't installed, and never re-enables a module the user left off.
		if (phase != Phase.IDLE || !requests.isEmpty()) {
			MeteorBridge.suspendAntiAfk();
		} else {
			MeteorBridge.restoreAntiAfk();
		}

		switch (phase) {
			case IDLE -> startNext();
			case SAFETY -> driveSafety();
			case MOVING -> driveMoving();
			case FIRING -> driveFiring();
			case VERIFY -> driveVerify();
			case AFTER -> driveAfter();
			case RETURN -> driveReturn();
			case RESTOCK -> { if (!restock.tick()) finishCurrent(); }
		}
	}

	private void startNext() {
		if (requests.isEmpty()) return;
		ClientWorld world = client.world;
		ClientPlayerEntity self = client.player;
		if (world == null || self == null) return;

		String sender = requests.poll();
		currentSender = sender;
		currentTokens = identity.tokensFor(sender);
		BlockPos origin = self.getBlockPos();

		List<StasisChamber> all = index.chambers(world, origin);
		List<StasisChamber> matches = all.stream()
				.filter(c -> c.matchesAny(currentTokens))
				.sorted(Comparator.comparingDouble(c -> c.trigger().getSquaredDistance(origin)))
				.toList();

		// No sign with their name → they're not in the base. Stay silent.
		if (matches.isEmpty()) {
			StasisBot.LOGGER.info("No chamber for '{}' — ignoring (not in base)", sender);
			startReturn(); // still return home if the bot already moved for a previous request
			return;
		}

		candidates = new ArrayList<>(matches.stream()
				.filter(c -> pearls.hasOwnPearl(world, c, all))
				.toList());
		if (candidates.isEmpty()) {
			feedback.notifySelf("§e[StasisBot] " + sender + ": stasis found but empty.");
			feedback.whisper(sender, Messages.Key.EMPTY);
			startReturn(); // still return home if the bot already moved for a previous request
			return;
		}

		candidateIdx = 0;
		currentTarget = candidates.get(0);
		// Only the very first request of a chain records the start position; mid-chain
		// the anchor is already set to the true origin and must not be overwritten.
		anchor.recordIfAbsent(self);
		returnTarget = null;
		teleportConfirmed = false;

		// Online gate: don't bother for someone who isn't connected.
		if (config.requireOnline() && !PlayerPresence.isOnline(client, sender)) {
			StasisBot.LOGGER.info("'{}' is offline — not teleporting", sender);
			startReturn(); // still return home if the bot already moved for a previous request
			return;
		}

		// Lag gate: wait for a spike to pass before committing.
		if (lag.isLagging()) {
			safetyDeadline = System.currentTimeMillis() + LAG_WAIT_CAP_MILLIS;
			phase = Phase.SAFETY;
			return;
		}

		commitTarget(true);
	}

	/** Decide whether the current target is fired now or walked to first. */
	private void commitTarget(boolean announce) {
		ClientPlayerEntity self = client.player;
		if (self == null) { finishCurrent(); return; }

		if (inReach(self, currentTarget)) {
			if (announce) feedback.whisper(currentSender, Messages.Key.RELEASING);
			firingDeadline = System.currentTimeMillis() + LAG_WAIT_CAP_MILLIS;
			phase = Phase.FIRING;
		} else if (config.autoWalk()) {
			int eta = estimateSeconds(self, currentTarget);
			feedback.whisper(currentSender, Messages.Key.ON_MY_WAY, eta);
			navigator.start(currentTarget);
			anchor.markMoved();
			phase = Phase.MOVING;
		} else {
			feedback.whisper(currentSender, Messages.Key.TOO_FAR);
			startReturn();
		}
	}

	private void driveSafety() {
		if (client.world == null || client.player == null) { finishCurrent(); return; }
		long now = System.currentTimeMillis();
		if (lag.isLagging() && now < safetyDeadline) return; // keep waiting
		if (lag.isLagging()) { // capped out, still laggy
			feedback.whisper(currentSender, Messages.Key.TP_FAILED_LAG);
			finishCurrent();
			return;
		}
		commitTarget(true);
	}

	private void driveMoving() {
		ClientWorld world = client.world;
		ClientPlayerEntity self = client.player;
		if (world == null || self == null || currentTarget == null) {
			navigator.stop(client);
			startReturn();
			return;
		}

		// Abort if the player disconnected mid-walk — no point reaching the trap.
		if (config.requireOnline() && !PlayerPresence.isOnline(client, currentSender)) {
			StasisBot.LOGGER.info("'{}' went offline during navigation — aborting", currentSender);
			navigator.stop(client);
			feedback.whisper(currentSender, Messages.Key.TP_FAILED_OFFLINE);
			startReturn();
			return;
		}

		Navigator.Status status = navigator.tick(client, currentTarget);
		switch (status) {
			case ARRIVED -> onArrived();
			case FAILED -> {
				StasisBot.LOGGER.warn("Could not reach '{}' for '{}'", currentTarget.label(), currentSender);
				feedback.whisper(currentSender, Messages.Key.PATH_FAIL);
				startReturn();
			}
			case MOVING -> { /* keep walking next tick */ }
		}
	}

	/** Re-read the sign now that we're close; the world may have changed mid-walk. */
	private void onArrived() {
		ClientWorld world = client.world;
		ClientPlayerEntity self = client.player;
		if (world == null || self == null) { startReturn(); return; }

		StasisChamber fresh = index.verify(world, self.getBlockPos(), currentTarget.trigger(), currentTokens);
		if (fresh == null) {
			// Sign gone / no longer matches — try the next candidate, else cancel.
			if (advanceCandidate()) { commitTarget(false); return; }
			feedback.whisper(currentSender, Messages.Key.PEARL_GONE);
			startReturn();
			return;
		}
		currentTarget = fresh;

		List<StasisChamber> all = index.chambers(world, self.getBlockPos());
		if (!pearls.hasOwnPearl(world, currentTarget, all)) {
			if (advanceCandidate()) { commitTarget(false); return; }
			feedback.whisper(currentSender, Messages.Key.PEARL_GONE);
			startReturn();
			return;
		}

		firingDeadline = System.currentTimeMillis() + LAG_WAIT_CAP_MILLIS;
		phase = Phase.FIRING;
	}

	private void driveFiring() {
		navigator.stop(client);
		ClientWorld world = client.world;
		ClientPlayerEntity self = client.player;
		if (self == null || world == null || currentTarget == null) { startReturn(); return; }

		long now = System.currentTimeMillis();

		// Final safety gate, right at the moment of firing.
		if (config.requireOnline() && !PlayerPresence.isOnline(client, currentSender)) {
			StasisBot.LOGGER.info("'{}' went offline before firing", currentSender);
			feedback.whisper(currentSender, Messages.Key.TP_FAILED_OFFLINE);
			startReturn();
			return;
		}
		if (lag.isLagging()) {
			if (now < firingDeadline) return; // hold the pearl until the lag clears
			feedback.whisper(currentSender, Messages.Key.TP_FAILED_LAG);
			startReturn();
			return;
		}

		// The pearl may have despawned or been claimed; re-check (try next if gone).
		List<StasisChamber> all = index.chambers(world, self.getBlockPos());
		if (!pearls.hasOwnPearl(world, currentTarget, all)) {
			if (advanceCandidate()) { commitTarget(false); return; }
			feedback.whisper(currentSender, Messages.Key.PEARL_GONE);
			startReturn();
			return;
		}

		if (!inReach(self, currentTarget)) {
			// Drifted out of reach; walk back in if allowed.
			if (config.autoWalk()) { navigator.start(currentTarget); anchor.markMoved(); phase = Phase.MOVING; return; }
			feedback.whisper(currentSender, Messages.Key.PATH_FAIL);
			startReturn();
			return;
		}

		if (activator.fire(client, currentTarget)) {
			StasisBot.LOGGER.info("Fired '{}' for '{}'", currentTarget.label(), currentSender);
			feedback.notifySelf("§a[StasisBot] §f" + currentSender + "§a → released §f" + currentTarget.label());
			fireAt = now;
			phase = Phase.VERIFY;
		} else {
			feedback.whisper(currentSender, Messages.Key.PATH_FAIL);
			startReturn();
		}
	}

	/** Confirm the player materialised; if not within the window, try another trap. */
	private void driveVerify() {
		if (client.world == null || client.player == null || currentTarget == null) { startReturn(); return; }

		if (PlayerPresence.hasArrived(client, currentSender, currentTarget.trigger(), ARRIVAL_RADIUS)) {
			teleportConfirmed = true;
			feedback.whisper(currentSender, Messages.Key.DONE);
			feedback.debug("TP confirmed for " + currentSender + " — waiting for them to leave the trap");
			phase = Phase.AFTER;
			return;
		}

		if (System.currentTimeMillis() - fireAt > config.arrivalTimeoutMillis()) {
			if (advanceCandidate()) {
				feedback.whisper(currentSender, Messages.Key.RETRYING);
				commitTarget(false);
				return;
			}
			feedback.whisper(currentSender, Messages.Key.NOT_CONFIRMED);
			phase = Phase.AFTER; // still reset the trap, then head home
		}
	}

	/** Reset the trap and hand the player a fresh pearl, then return home. */
	private void driveAfter() {
		ClientPlayerEntity self = client.player;
		if (self == null || currentTarget == null) { startReturn(); return; }

		if (!teleportConfirmed) {
			// TP failed — don't reopen the trap (player may still be inside).
			startReturn();
			return;
		}

		long now = System.currentTimeMillis();

		// Give the player their fresh pearl right away, while they're still close.
		if (!pearlGiven && config.dropPearlForPlayer() && PlayerActions.hasPearl(client)) {
			PlayerActions.faceToward(client, currentSender);
			boolean dropped = PlayerActions.dropPearl(client);
			feedback.debug("Drop pearl for " + currentSender + ": " + (dropped ? "OK" : "FAILED"));
			pearlGiven = true;
		}

		// If the player re-armed the trap themselves (opened the trapdoor to reset their
		// own pearl), don't touch it — just head home. Toggling it now would close their
		// freshly-armed trap and could even re-teleport them. Trapdoor triggers only; for
		// levers/buttons we can't read the state, so the normal flow below applies.
		if (Boolean.TRUE.equals(TrapState.isOpen(client.world, currentTarget.trigger()))) {
			feedback.debug("Player re-armed the trap themselves — leaving without touching it");
			startReturn();
			return;
		}

		// Wait for the player to actually LEAVE the chamber before re-arming the trap,
		// so we never re-trap them. Only wait when nobody else is queued. EXIT_RADIUS is
		// generous so clipping into the water below the trapdoor doesn't look like an
		// instant exit, and we always honour a minimum settle time first.
		if (requests.isEmpty()) {
			if (afterWaitStart == 0L) afterWaitStart = now;
			long elapsed = now - afterWaitStart;
			boolean stillHere = PlayerPresence.hasArrived(
					client, currentSender, currentTarget.trigger(), EXIT_RADIUS);
			boolean minWaitDone = elapsed >= AFTER_MIN_WAIT_MILLIS;
			boolean timedOut = elapsed > AFTER_WAIT_CAP_MILLIS;
			if (!timedOut && (!minWaitDone || stillHere)) return; // keep waiting
			if (timedOut) feedback.debug("Player " + currentSender + " still near after timeout — re-arming anyway");
			else feedback.debug("Player " + currentSender + " left the chamber — re-arming trap");
		}

		// Re-arm (reopen) the trap, unconditionally — this is the whole point: put the
		// trapdoor back into its armed/open state so a new pearl can be stored.
		if (config.reopenTrigger() && inReach(self, currentTarget)) {
			activator.fire(client, currentTarget);
			feedback.debug("Re-armed (reopened) trap for " + currentSender);
		}
		startReturn();
	}

	// --- returning home ------------------------------------------------------

	private void startReturn() {
		navigator.stop(client);
		// If the bot never left its spot, it is already at home — try restock.
		if (!anchor.hasMoved()) {
			anchor.reset();
			maybeRestock();
			return;
		}
		// Queue still has people waiting — rush to the next stasis, return later.
		if (!requests.isEmpty()) {
			finishCurrent();
			return;
		}
		// Nothing left: head back to the true origin.
		if (config.returnHome() && client.player != null) {
			returnTarget = config.hasReturnPos()
					? new BlockPos(config.returnX(), config.returnY(), config.returnZ())
					: anchor.pos();
			if (returnTarget != null) {
				navigator.startExact(returnTarget);
				phase = Phase.RETURN;
				return;
			}
		}
		anchor.reset();
		finishCurrent();
	}

	private void driveReturn() {
		ClientPlayerEntity self = client.player;
		if (self == null || returnTarget == null) { navigator.stop(client); anchor.reset(); maybeRestock(); return; }
		// Primary check: bot is standing on the exact return block.
		if (self.getBlockPos().equals(returnTarget)) {
			arriveHome(self);
			return;
		}
		// Use the anchor's exact sub-block position as the arrival target so the
		// navigator aims for the precise centre of the block (x+0.5, z+0.5).
		double arriveDist = anchor.vec() != null
				? anchor.vec().distanceTo(Vec3d.ofCenter(returnTarget)) + 0.6
				: 1.5;
		Navigator.Status status = navigator.tick(client, returnTarget, Math.max(0.6, arriveDist));
		if (status != Navigator.Status.MOVING) {
			arriveHome(self);
		}
	}

	/** Settle on the home block, restore the original facing, then maybe restock. */
	private void arriveHome(ClientPlayerEntity self) {
		navigator.stop(client);
		self.setYaw(anchor.yaw());
		self.setPitch(anchor.pitch());
		anchor.reset();
		maybeRestock();
	}

	/** Enter the restock phase if pearls are low and a chest is near, else finish. */
	private void maybeRestock() {
		if (restock.begin()) {
			phase = Phase.RESTOCK;
		} else {
			finishCurrent();
		}
	}

	private void finishCurrent() {
		requests.release(currentSender);
		currentSender = null;
		currentTarget = null;
		currentTokens = Set.of();
		candidates = new ArrayList<>();
		candidateIdx = 0;
		// The anchor is intentionally NOT reset here — it persists across chained
		// requests so the bot remembers its true origin. AnchorMemory#reset() is the
		// only place that clears it, after the bot actually returns home.
		restock.reset();
		afterWaitStart = 0L;
		returnTarget = null;
		teleportConfirmed = false;
		pearlGiven = false;
		phase = Phase.IDLE;
	}

	// --- small helpers -------------------------------------------------------

	/** Advance to the next loaded candidate chamber; false when none remain. */
	private boolean advanceCandidate() {
		candidateIdx++;
		if (candidateIdx >= candidates.size()) return false;
		currentTarget = candidates.get(candidateIdx);
		return true;
	}

	private boolean inReach(ClientPlayerEntity self, StasisChamber chamber) {
		return self.getEyePos().distanceTo(Vec3d.ofCenter(chamber.trigger())) <= config.reach();
	}

	private int estimateSeconds(ClientPlayerEntity self, StasisChamber chamber) {
		double dist = Math.sqrt(self.getBlockPos().getSquaredDistance(chamber.trigger()));
		return Math.max(1, (int) Math.ceil(dist / EST_SPEED_BPS) + 1);
	}
}
