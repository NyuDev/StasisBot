package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.StasisBot;
import fr.nyuway.stasisbot.activation.MeteorBridge;
import fr.nyuway.stasisbot.activation.Navigation;
import fr.nyuway.stasisbot.activation.Navigator;
import fr.nyuway.stasisbot.activation.PlayerActions;
import fr.nyuway.stasisbot.activation.StasisActivator;
import fr.nyuway.stasisbot.activation.TrapState;
import fr.nyuway.stasisbot.config.DiscordEvent;
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
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
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
	/** Hold the facing toward the player this long before dropping, so the look packet is sent first. */
	private static final long PEARL_FACE_SETTLE_MILLIS = 300L;
	/** How close (blocks, eye→bed-centre) the bot must be before it right-clicks a bed. */
	private static final double BED_INTERACT_DISTANCE = 2.2;
	/** Hard cap on waiting for a lag spike to clear before giving up. */
	private static final long LAG_WAIT_CAP_MILLIS = 15_000L;
	/** Assumed travel speed (blocks/second) for the ETA estimate. */
	private static final double EST_SPEED_BPS = 4.0;

	private enum Phase { IDLE, SAFETY, MOVING, FIRING, VERIFY, AFTER, RETURN, RESTOCK, MANUAL }

	private final MinecraftClient client;
	private final StasisBotConfig config;
	private final ChamberIndex index;
	private final IdentityResolver identity;
	private final PearlDetector pearls;
	private final StasisActivator activator;
	private final Navigation navigator;
	private final LagMonitor lag;
	private final DiscordNotifier discord;
	private final BotActivity botActivity;

	private final PlayerFeedback feedback;
	private final RequestQueue requests = new RequestQueue();
	private final AnchorMemory anchor = new AnchorMemory();
	private final RestockController restock;
	private final PearlDropTracker pearlTracker;
	private final ManualNavController manual;
	private final MasterCommandHandler commands;
	private final DeathRespawnController death;

	private Phase phase = Phase.IDLE;
	private String currentSender;
	private Set<String> currentTokens = Set.of();
	private List<StasisChamber> candidates = new ArrayList<>();
	private int candidateIdx = 0;
	private StasisChamber currentTarget;

	private BlockPos returnTarget;    // where it should walk back to
	private boolean teleportConfirmed;
	private boolean pearlGiven;       // did we already hand the player their fresh pearl?
	private long pearlFaceAt;         // when we started facing the player before dropping (0 = not yet)
	private BlockPos bedTarget;       // a bed the bot is walking to (to set spawn), or null
	private BlockPos lastBed;         // the last bed the bot set as its spawn (for "go to spawn")

	private long fireAt;
	private long safetyDeadline;
	private long firingDeadline;

	public HomeService(MinecraftClient client, StasisBotConfig config, ChamberIndex index,
	                   IdentityResolver identity, PearlDetector pearls, StasisActivator activator,
	                   LagMonitor lag, DiscordNotifier discord, BotDeathInfo deathInfo,
	                   BotActivity botActivity) {
		this.client = client;
		this.config = config;
		this.index = index;
		this.identity = identity;
		this.pearls = pearls;
		this.activator = activator;
		this.lag = lag;
		this.discord = discord;
		this.botActivity = botActivity;
		this.navigator = new Navigation(config);
		this.feedback = new PlayerFeedback(client, config);
		this.restock = new RestockController(client, config, feedback);
		this.pearlTracker = new PearlDropTracker(client, config, discord, feedback);
		this.manual = new ManualNavController(client, config, navigator);
		this.commands = new MasterCommandHandler(this, config, client, feedback);
		this.death = new DeathRespawnController(client, config, deathInfo, feedback, new DeathRespawnController.Owner() {
			@Override public void abortForDeath() { onBotDied(); }
			@Override public void beginReturn(BlockPos home) {
				returnTarget = home;
				navigator.startExact(home);
				phase = Phase.RETURN;
			}
			@Override public void announceGlobal(DiscordEvent event, String message) { discordGlobal(event, message); }
		});
	}

	// --- Discord helpers -----------------------------------------------------

	/** Announce a player-centric event, computing the all-vs-outsiders flag from their base membership. */
	private void discordPlayer(DiscordEvent event, String sender, String message) {
		if (discord != null) discord.notify(event, !isBaseMember(sender), message);
	}

	/** Announce a non-scoped (bot/base) event. */
	private void discordGlobal(DiscordEvent event, String message) {
		if (discord != null) discord.notify(event, message);
	}

	/** Abort all in-flight work when the bot dies (driven by {@link DeathRespawnController}). */
	private void onBotDied() {
		navigator.stop(client);
		if (phase == Phase.RESTOCK) restock.abort();
		requests.clear(); // queued positions are stale once we respawn far away
		finishCurrent();
		anchor.reset();
	}

	// --- chat entry points ---------------------------------------------------

	/** Single front door for every chat line (may be called off the client thread). */
	public void onChatMessage(String sender, String body, boolean dm) {
		if (sender == null || body == null) return;
		if (MasterCommands.looksLikeCommand(config, body)) {
			final String s = sender, b = body;
			client.execute(() -> commands.handle(s, b));
			return;
		}
		if (isMemberWatchCommand(body)) {
			// Only base members or the master can use watch commands; everyone else is silently ignored.
			final String s = sender, b = body;
			client.execute(() -> { if (isBaseMember(s) || config.isMaster(s)) handleMemberWatch(s, b); });
			return;
		}
		if (containsTrigger(body)) onHomeRequest(sender, body, dm);
	}

	private static boolean isMemberWatchCommand(String body) {
		if (body == null) return false;
		String b = body.trim().toLowerCase(Locale.ROOT);
		return b.equals("!watch") || b.startsWith("!watch ")
			|| b.equals("!unwatch") || b.startsWith("!unwatch ")
			|| b.equals("!watching")
			|| b.equals("!watchmode") || b.startsWith("!watchmode ");
	}

	/**
	 * Handle {@code !watch}, {@code !unwatch}, {@code !watching}, {@code !watchmode} for base members.
	 * Runs on the client thread (already marshalled by the caller).
	 */
	private void handleMemberWatch(String sender, String body) {
		String[] parts = body.trim().split("\\s+");
		String cmd = parts[0].toLowerCase(Locale.ROOT);
		switch (cmd) {
			case "!watch" -> {
				if (parts.length < 2) { feedback.replyText(sender, "[Watch] Usage: !watch <player>"); return; }
				feedback.replyText(sender, config.addMemberWatch(sender, parts[1]));
			}
			case "!unwatch" -> {
				if (parts.length < 2) { feedback.replyText(sender, "[Watch] Usage: !unwatch <player>"); return; }
				feedback.replyText(sender, config.removeMemberWatch(sender, parts[1]));
			}
			case "!watching" -> {
				List<String> list = config.memberWatchPlayers(sender);
				String mode = config.memberWatchOutput(sender);
				String reply = list.isEmpty()
					? "[Watch] Your list is empty. Mode: " + mode
					: "[Watch] Watching: " + String.join(", ", list) + " | Mode: " + mode;
				feedback.replyText(sender, reply);
			}
			case "!watchmode" -> {
				if (parts.length < 2) { feedback.replyText(sender, "[Watch] Usage: !watchmode dm|discord"); return; }
				String mode = parts[1].toLowerCase(Locale.ROOT);
				if (!"dm".equals(mode) && !"discord".equals(mode)) {
					feedback.replyText(sender, "[Watch] Mode must be 'dm' or 'discord'.");
					return;
				}
				config.setMemberWatchOutput(sender, mode);
				feedback.replyText(sender, "[Watch] Output mode set to: " + mode);
			}
		}
	}

	/** A base member is anyone whose name/alias matches a currently detected stasis sign. */
	boolean isBaseMember(String sender) {
		ClientWorld world = client.world;
		ClientPlayerEntity self = client.player;
		if (world == null || self == null) return false;
		Set<String> tokens = identity.tokensFor(sender);
		if (tokens.isEmpty()) return false;
		for (StasisChamber c : index.chambers(world, self.getBlockPos())) {
			if (c.matchesAny(tokens)) return true;
		}
		return false;
	}

	/** Whether the bot is currently busy (mid-request or moving), for the come/goto guard. */
	boolean isBusy() {
		return phase != Phase.IDLE || !requests.isEmpty();
	}

	/** Begin a master-directed move. Yields to home requests and never auto-returns home. */
	void startManual(BlockPos target) {
		manual.start(target);
		phase = Phase.MANUAL;
	}

	/** Cancel a master-directed move and drop back to idle. */
	void stopManual() {
		manual.stop();
		if (phase == Phase.MANUAL) phase = Phase.IDLE;
	}

	// --- remote control (called on the client thread by the control API) --------

	/** Walk the bot to fixed coordinates (ignored while it's busy serving a request). */
	public void remoteGoto(int x, int y, int z) {
		if (!isBusy()) startManual(new BlockPos(x, y, z));
	}

	/** Walk the bot to the named player's current position, if visible and not busy. */
	public void remoteCome(String name) {
		if (name == null || client.world == null || isBusy()) return;
		for (var p : client.world.getPlayers()) {
			if (name.equalsIgnoreCase(p.getGameProfile().name())) {
				startManual(p.getBlockPos());
				return;
			}
		}
	}

	/** Cancel any master-directed move. */
	public void remoteStop() {
		stopManual();
	}

	/** Walk the bot back to its pinned home and settle (centre + facing), when idle. */
	public void remoteGoHome() {
		if (!config.hasReturnPos() || phase != Phase.IDLE || client.player == null) return;
		returnTarget = new BlockPos(config.returnX(), config.returnY(), config.returnZ());
		anchor.markMoved(); // so startReturn/arriveHome treat this as a real trip
		navigator.startExact(returnTarget);
		phase = Phase.RETURN;
	}

	/** True when the bot is standing on its pinned home block (for the remote panel's indicator). */
	public boolean atHome() {
		if (!config.hasReturnPos() || client.player == null) return false;
		BlockPos p = client.player.getBlockPos();
		return p.getX() == config.returnX() && p.getY() == config.returnY() && p.getZ() == config.returnZ();
	}

	/** True when the named player (the operator) is standing on the bot's home block. */
	public boolean watcherAtHome(String name) {
		if (!config.hasReturnPos() || client.world == null || name == null || name.isBlank()) return false;
		for (var p : client.world.getPlayers()) {
			if (name.equalsIgnoreCase(p.getGameProfile().name())) {
				BlockPos bp = p.getBlockPos();
				return bp.getX() == config.returnX() && bp.getY() == config.returnY() && bp.getZ() == config.returnZ();
			}
		}
		return false;
	}

	/** Walk the bot to a bed and right-click it to set its spawn there (when idle). */
	public void remoteUseBed(int x, int y, int z) {
		if (phase != Phase.IDLE || client.player == null) return;
		bedTarget = new BlockPos(x, y, z);
		startManual(bedTarget); // walk there; the per-tick reach check fires the interact
	}

	/** Get up if the bot is asleep — it must stay active. */
	private void wakeIfSleeping() {
		ClientPlayerEntity self = client.player;
		if (self != null && self.isSleeping() && self.networkHandler != null) {
			self.networkHandler.sendPacket(new ClientCommandC2SPacket(self, ClientCommandC2SPacket.Mode.STOP_SLEEPING));
		}
	}

	/** Face and right-click the targeted bed, then clear the task. */
	private void interactBed() {
		ClientPlayerEntity self = client.player;
		if (self == null || bedTarget == null) { bedTarget = null; return; }
		navigator.stop(client);
		Vec3d c = Vec3d.ofCenter(bedTarget);
		double dx = c.x - self.getX();
		double dz = c.z - self.getZ();
		self.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
		self.setPitch(45.0f);
		if (client.interactionManager != null) {
			client.interactionManager.interactBlock(self, Hand.MAIN_HAND,
					new BlockHitResult(c, Direction.UP, bedTarget, false));
		}
		feedback.debug("bed: interacted to set spawn at " + bedTarget.toShortString());
		lastBed = bedTarget;
		bedTarget = null;
		stopManual();
		StasisBot.LOGGER.info("[bed] set spawn via bed at {}", lastBed.toShortString());
	}

	/** Walk the bot to the last bed it set as its spawn (when idle). */
	public void remoteGoSpawn() {
		if (lastBed == null || phase != Phase.IDLE) return;
		startManual(lastBed);
	}

	/** Trigger a pearl restock from a chest next to the bot's current position (when idle). */
	public void remoteRestock() {
		if (phase != Phase.IDLE) return;
		if (restock.begin()) {
			feedback.debug("remote restock — pulling pearls from a nearby chest");
			phase = Phase.RESTOCK;
		}
	}

	/** Fire the detected chamber whose trigger sits at these coords (must be in reach). */
	public boolean remoteFireChamber(int x, int y, int z) {
		ClientWorld world = client.world;
		ClientPlayerEntity self = client.player;
		if (world == null || self == null) return false;
		BlockPos trigger = new BlockPos(x, y, z);
		for (StasisChamber c : index.chambers(world, self.getBlockPos())) {
			if (c.trigger().equals(trigger)) {
				if (self.getEyePos().distanceTo(Vec3d.ofCenter(trigger)) > config.reach()) {
					StasisBot.LOGGER.info("[control] remote-fire '{}' skipped — out of reach", c.label());
					return false;
				}
				boolean ok = activator.fire(client, c);
				if (ok) botActivity.markTriggerUse();
				StasisBot.LOGGER.info("[control] remote-fired chamber '{}' -> {}", c.label(), ok);
				return ok;
			}
		}
		return false;
	}

	private boolean containsTrigger(String body) {
		return config.matchesTrigger(body);
	}

	/** Entry point from the chat listener (may be invoked off the client thread). */
	public void onHomeRequest(String senderName, String message, boolean dm) {
		if (senderName == null || senderName.isBlank()) return;
		if (requests.shouldDebounce(senderName)) {
			StasisBot.LOGGER.info("[home] '{}' debounced (duplicate within window)", senderName);
			return;
		}
		client.execute(() -> enqueue(senderName, message, dm));
	}

	private void enqueue(String senderName, String message, boolean dm) {
		ClientPlayerEntity self = client.player;
		if (self == null) return;
		if (senderName.equalsIgnoreCase(self.getGameProfile().name())) return; // ignore the bot itself
		// Only base members may request, and only their requests are logged: a player
		// whose name/alias is on a detected stasis sign. Stops ordinary chat that merely
		// contains a trigger word from being processed or announced. Disable with
		// requireBaseMemberForHome=false.
		if (config.requireBaseMemberForHome() && !isBaseMember(senderName)) {
			if (config.debug()) StasisBot.LOGGER.info("[home] '{}' ignored — not a base member", senderName);
			return;
		}
		// Already at the base: don't waste a pearl pulling in someone who's standing in
		// the bot's render distance. On by default; turn off with skipIfPresent=false.
		if (config.skipIfPresent() && isInRenderDistance(senderName)) {
			StasisBot.LOGGER.info("[home] '{}' ignored — already in render distance", senderName);
			feedback.debug("home from '" + senderName + "' ignored — already here");
			feedback.whisper(senderName, Messages.Key.ALREADY_HERE);
			return;
		}
		if (senderName.equalsIgnoreCase(currentSender)) {                      // already being served
			StasisBot.LOGGER.info("[home] '{}' ignored — already being served", senderName);
			return;
		}
		if (!requests.offer(senderName)) {                                     // already queued
			StasisBot.LOGGER.info("[home] '{}' ignored — already queued", senderName);
			return;
		}
		StasisBot.LOGGER.info("[home] '{}' enqueued (queue size {})", senderName, requests.size());
		feedback.debug("home requested by '" + senderName + "' (queue " + requests.size() + ")");
		discordPlayer(DiscordEvent.HOME_REQUESTED, senderName,
				DiscordText.homeRequested(config.language(), senderName, message, dm));

		int ahead = requests.size() - 1 + (phase != Phase.IDLE ? 1 : 0);
		if (ahead > 0) {
			feedback.whisper(senderName, Messages.Key.QUEUED, ahead);
		}
	}

	/** True when a player with this name is currently within the bot's render distance. */
	private boolean isInRenderDistance(String name) {
		ClientWorld world = client.world;
		if (world == null || name == null) return false;
		for (var p : world.getPlayers()) {
			String n = p.getGameProfile().name();
			if (n != null && n.equalsIgnoreCase(name)) return true;
		}
		return false;
	}

	// --- tick dispatch -------------------------------------------------------

	/** Drive the active request; call once per client tick. */
	public void tick() {
		// Always check whether a pearl we dropped just got picked up — this outlives the
		// request that dropped it (the bot may already be walking home).
		pearlTracker.tick();

		// The bot should never stay asleep — if it dozed off in a bed, get up immediately.
		wakeIfSleeping();
		// Keep the bot dead-centre on its home block while idle (x+0.5 / z+0.5, not a corner).
		holdHomeCentre();
		// Bed task: once we've walked RIGHT UP to a targeted bed (within ~2 blocks, not just
		// general reach), interact to set its spawn — bed-use needs the bot to be very close.
		if (bedTarget != null && client.player != null
				&& client.player.getEyePos().distanceTo(Vec3d.ofCenter(bedTarget)) <= BED_INTERACT_DISTANCE) {
			interactBed();
		}

		// Death first: if the bot got killed, respawn it and (optionally) walk it home
		// before doing anything else. While dead/respawning this owns the tick.
		if (death.tick()) return;

		// Remember where the bot rests so it can walk back here after a death even when no
		// fixed home is pinned. Only updates while genuinely idle, so a request or a walk
		// never drags the remembered base along with it.
		if (phase == Phase.IDLE && requests.isEmpty() && client.player != null) {
			death.rememberBase(client.player.getBlockPos());
		}

		// Meteor Anti-AFK: keep it off while the bot is busy, restore it once fully idle.
		// No-op when Meteor isn't installed, and never re-enables a module the user left off.
		if (phase != Phase.IDLE || !requests.isEmpty()) {
			MeteorBridge.suspendAntiAfk();
		} else {
			MeteorBridge.restoreAntiAfk();
		}

		// Home requests are priority 1: never make a waiting player sit through the
		// bot's "dead time". If someone is queued while we're merely heading back home,
		// restocking or running a master come/goto, drop that and go serve them now. The
		// home anchor is preserved, so any trip home simply resumes once the queue drains.
		if (!requests.isEmpty() && (phase == Phase.RETURN || phase == Phase.RESTOCK || phase == Phase.MANUAL)) {
			preemptForRequest();
			return;
		}

		switch (phase) {
			case IDLE -> startNext();
			case SAFETY -> driveSafety();
			case MOVING -> driveMoving();
			case FIRING -> driveFiring();
			case VERIFY -> driveVerify();
			case AFTER -> driveAfter();
			case RETURN -> driveReturn();
			case RESTOCK -> driveRestock();
			case MANUAL -> { if (!manual.tick()) phase = Phase.IDLE; }
		}
	}

	/**
	 * Interrupt the going-home / restocking housekeeping to serve a freshly queued
	 * home request. Stops navigation, closes the restock chest if one is open, then
	 * drops back to IDLE so {@link #startNext()} picks up the waiting player on the
	 * next tick. The anchor is left untouched on purpose — the bot still owes a trip
	 * home and must remember where that is.
	 */
	private void preemptForRequest() {
		navigator.stop(client);
		if (phase == Phase.RESTOCK) restock.abort();
		StasisBot.LOGGER.info("[home] preempting {} to serve a waiting request", phase);
		finishCurrent();
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
			discordPlayer(DiscordEvent.STASIS_EMPTY, sender, DiscordText.empty(config.language(), sender));
			startReturn(); // still return home if the bot already moved for a previous request
			return;
		}

		StasisBot.LOGGER.info("[home] serving '{}' — {} matching chamber(s), {} loaded, pearls in inv: {}",
				sender, matches.size(), candidates.size(), PlayerActions.countPearls(client));

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
			feedback.debug("walking to '" + currentTarget.label() + "' for " + currentSender + " (~" + eta + "s)");
			discordPlayer(DiscordEvent.BOT_EN_ROUTE, currentSender,
					DiscordText.enRoute(config.language(), currentSender, currentTarget.label(), eta));
			navigator.start(currentTarget);
			anchor.markMoved();
			phase = Phase.MOVING;
		} else {
			feedback.whisper(currentSender, Messages.Key.TOO_FAR);
			discordPlayer(DiscordEvent.PATH_FAILED, currentSender,
					DiscordText.pathFailed(config.language(), currentSender, currentTarget.label()));
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
			discordPlayer(DiscordEvent.TP_FAILED, currentSender,
					DiscordText.failed(config.language(), currentSender, "left mid-walk"));
			startReturn();
			return;
		}

		Navigator.Status status = navigator.tick(client, currentTarget);
		switch (status) {
			case ARRIVED -> onArrived();
			case FAILED -> {
				StasisBot.LOGGER.warn("Could not reach '{}' for '{}'", currentTarget.label(), currentSender);
				feedback.whisper(currentSender, Messages.Key.PATH_FAIL);
				discordPlayer(DiscordEvent.PATH_FAILED, currentSender,
						DiscordText.pathFailed(config.language(), currentSender, currentTarget.label()));
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
			discordPlayer(DiscordEvent.TP_FAILED, currentSender,
					DiscordText.failed(config.language(), currentSender, "went offline"));
			startReturn();
			return;
		}
		if (lag.isLagging()) {
			if (now < firingDeadline) return; // hold the pearl until the lag clears
			feedback.whisper(currentSender, Messages.Key.TP_FAILED_LAG);
			discordPlayer(DiscordEvent.TP_FAILED, currentSender,
					DiscordText.failed(config.language(), currentSender, "server lag"));
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
			discordPlayer(DiscordEvent.PATH_FAILED, currentSender,
					DiscordText.pathFailed(config.language(), currentSender, currentTarget.label()));
			startReturn();
			return;
		}

		if (activator.fire(client, currentTarget)) {
			botActivity.markTriggerUse(); // our own toggle — not a player opening the stasis
			StasisBot.LOGGER.info("Fired '{}' for '{}'", currentTarget.label(), currentSender);
			feedback.debug(currentSender + ": fired '" + currentTarget.label() + "'");
			feedback.notifySelf("§a[StasisBot] §f" + currentSender + "§a → released §f" + currentTarget.label());
			discordPlayer(DiscordEvent.STASIS_FIRED, currentSender,
					DiscordText.fired(config.language(), currentSender, currentTarget.label()));
			fireAt = now;
			phase = Phase.VERIFY;
		} else {
			feedback.whisper(currentSender, Messages.Key.PATH_FAIL);
			startReturn();
		}
	}

	/** Confirm the player materialised; re-arm and head home either way. */
	private void driveVerify() {
		if (client.world == null || client.player == null || currentTarget == null) { startReturn(); return; }

		if (PlayerPresence.hasArrived(client, currentSender, currentTarget.trigger(), ARRIVAL_RADIUS)) {
			teleportConfirmed = true;
			feedback.whisper(currentSender, Messages.Key.DONE);
			feedback.debug("TP confirmed for " + currentSender + " — re-arming the trap now");
			discordPlayer(DiscordEvent.TP_CONFIRMED, currentSender, DiscordText.confirmed(config.language(), currentSender));
			phase = Phase.AFTER;
			return;
		}

		if (System.currentTimeMillis() - fireAt > config.arrivalTimeoutMillis()) {
			// We've already fired one chamber and released the player's pearl. Never
			// fire a second candidate here: the player only ever uses one pearl per
			// teleport, so firing another chamber would just drop a second pearl on
			// the ground. Proceed to re-arm the trap we fired and head home, even if
			// arrival couldn't be confirmed within the window.
			feedback.whisper(currentSender, Messages.Key.NOT_CONFIRMED);
			discordPlayer(DiscordEvent.TP_FAILED, currentSender,
					DiscordText.failed(config.language(), currentSender, "not confirmed"));
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

		// Hand the player exactly ONE fresh pearl. Firing the trap consumed the pearl
		// that was suspended in the chamber they just teleported to, so THAT chamber is
		// now empty and they need a pearl to re-arm it. One teleport = one pearl, always —
		// any other stasis they keep elsewhere is irrelevant (it wasn't the one consumed).
		// Either way, privately remind them to re-arm — and warn LOUDLY if I had no pearl
		// to give, because without one they have no way back next time.
		if (!pearlGiven) {
			if (config.dropPearlForPlayer()) {
				if (PlayerActions.hasPearl(client)) {
					// Face the player FIRST and let the look packet reach the server before the
					// drop, so the pearl is thrown toward them — not wherever the bot last faced.
					// We do it across a couple of ticks: start facing, settle, then drop.
					if (pearlFaceAt == 0L) {
						PlayerActions.faceToward(client, currentSender);
						pearlFaceAt = System.currentTimeMillis();
						return; // come back next tick once the rotation has been sent
					}
					if (System.currentTimeMillis() - pearlFaceAt < PEARL_FACE_SETTLE_MILLIS) {
						PlayerActions.faceToward(client, currentSender); // keep facing while settling
						return;
					}
					PlayerActions.faceToward(client, currentSender); // re-assert right before dropping
					boolean dropped = PlayerActions.dropPearl(client);
					feedback.debug(currentSender + ": drop 1 pearl — " + (dropped ? "OK" : "FAILED")
							+ " (inv " + PlayerActions.countPearls(client) + ")");
					feedback.whisper(currentSender, dropped ? Messages.Key.REARM_OK : Messages.Key.REARM_NOPEARL);
					if (dropped) {
						boolean outsider = !isBaseMember(currentSender);
						discord.notify(DiscordEvent.PEARL_DROPPED, outsider,
								DiscordText.pearlDropped(config.language(), currentSender, PlayerActions.countPearls(client)));
						pearlTracker.track(self.getBlockPos(), currentSender, outsider);
					} else {
						discordPlayer(DiscordEvent.OUT_OF_PEARLS, currentSender,
								DiscordText.outOfPearls(config.language(), currentSender));
					}
				} else {
					feedback.debug(currentSender + ": NO pearl to give (inventory empty)");
					feedback.whisper(currentSender, Messages.Key.REARM_NOPEARL);
					discordPlayer(DiscordEvent.OUT_OF_PEARLS, currentSender,
							DiscordText.outOfPearls(config.language(), currentSender));
				}
			}
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

		// Re-arm (reopen) the trap immediately. The teleport is already confirmed, so the
		// player is back — there's no reason to wait for them to step off the trap first.
		// Re-arming only puts the trapdoor back into its armed state for the next pearl; the
		// pearl that pulled them here was already consumed by the fire, so this can never
		// re-teleport them.
		if (config.reopenTrigger() && inReach(self, currentTarget)) {
			activator.fire(client, currentTarget);
			botActivity.markTriggerUse(); // our own re-arm — not a player closing the stasis
			feedback.debug("Reopened the trap for " + currentSender);
			discordPlayer(DiscordEvent.STASIS_REOPENED, currentSender,
					DiscordText.reopened(config.language(), currentSender, currentTarget.label()));
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
				: 0.8;
		Navigator.Status status = navigator.tick(client, returnTarget, Math.max(0.6, arriveDist));
		if (status != Navigator.Status.MOVING) {
			arriveHome(self);
		}
	}

	/** Settle on the home block, restore the original facing, then maybe restock. */
	private void arriveHome(ClientPlayerEntity self) {
		navigator.stop(client);
		// Decide the facing to settle into first (operator-chosen on a remote set-home, else
		// the facing the bot left with — but only if we still have a recorded anchor).
		float yaw = self.getYaw();
		float pitch = self.getPitch();
		if (config.hasReturnPos() && config.hasReturnFacing()) {
			yaw = config.returnYaw();
			pitch = config.returnPitch();
		} else if (anchor.isRecorded()) {
			yaw = anchor.yaw();
			pitch = anchor.pitch();
		}
		// Snap onto the EXACT CENTRE of the home block: x+0.5 / z+0.5 (not the corner). Use a
		// full teleport (resets prev-position) and zero the velocity so it doesn't slide off.
		// The per-tick centre-hold below then keeps it pinned there against any drift.
		if (config.hasReturnPos()) {
			double cx = config.returnX() + 0.5;
			double cy = config.returnY();
			double cz = config.returnZ() + 0.5;
			self.refreshPositionAndAngles(cx, cy, cz, yaw, pitch);
			self.setVelocity(Vec3d.ZERO);
			StasisBot.LOGGER.info("[home] arrived & centred on home block (fract x={}, z={})",
					cx - Math.floor(cx), cz - Math.floor(cz));
		} else {
			self.setYaw(yaw);
			self.setPitch(pitch);
		}
		anchor.reset();
		maybeRestock();
	}

	/**
	 * While idle and standing on the pinned home block, keep the bot snapped to the block's
	 * exact centre (x+0.5 / z+0.5). A single teleport on arrival can drift back to a corner
	 * (physics, residual movement, a server position tweak); re-asserting the centre each tick
	 * — only a sub-block nudge, never a fast move — guarantees it sits dead-centre.
	 */
	private void holdHomeCentre() {
		if (phase != Phase.IDLE || !requests.isEmpty() || bedTarget != null) return;
		if (!config.hasReturnPos()) return;
		ClientPlayerEntity self = client.player;
		if (self == null) return;
		BlockPos bp = self.getBlockPos();
		if (bp.getX() != config.returnX() || bp.getY() != config.returnY() || bp.getZ() != config.returnZ()) return;
		double cx = config.returnX() + 0.5;
		double cz = config.returnZ() + 0.5;
		if (Math.abs(self.getX() - cx) > 0.02 || Math.abs(self.getZ() - cz) > 0.02) {
			self.setPosition(cx, self.getY(), cz);
			self.setVelocity(0.0, self.getVelocity().y, 0.0);
		}
	}

	/** Enter the restock phase if pearls are low and a chest is near, else finish. */
	private void maybeRestock() {
		if (restock.begin()) {
			feedback.debug("restocking pearls from chest");
			phase = Phase.RESTOCK;
		} else {
			finishCurrent();
		}
	}

	/** Drive the restock state machine; announce the result with counts when it finishes. */
	private void driveRestock() {
		if (restock.tick()) return; // still working
		if (restock.didRestock()) {
			feedback.debug("restock done: +" + restock.taken() + " (now " + restock.held()
					+ " on bot, " + restock.chestRemaining() + " left in chest)");
			discordGlobal(DiscordEvent.RESTOCKED, DiscordText.restocked(config.language(),
					restock.taken(), restock.held(), restock.chestRemaining()));
		}
		finishCurrent();
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
		returnTarget = null;
		manual.clear();
		teleportConfirmed = false;
		pearlGiven = false;
		pearlFaceAt = 0L;
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
