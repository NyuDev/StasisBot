package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.config.DiscordEvent;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.identity.IdentityResolver;
import fr.nyuway.stasisbot.model.StasisChamber;
import fr.nyuway.stasisbot.scan.ChamberIndex;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Watches the entities around the bot and reports two combat-relevant things to
 * Discord: ender-pearl teleports ({@link DiscordEvent#PEARL_THROWN}) and end
 * crystal placement / explosion ({@link DiscordEvent#CRYSTAL_PLACED} /
 * {@link DiscordEvent#CRYSTAL_BROKEN}). Everything is diff-based, polled once a
 * second:
 *
 * <ul>
 *   <li>a pearl id that wasn't there last scan = someone just threw one; the
 *       thrower is read from the projectile's owner when available, otherwise the
 *       nearest player is credited;</li>
 *   <li>a crystal id that newly appears = placed; one that vanishes (while the
 *       bot is still next to where it was) = exploded.</li>
 * </ul>
 *
 * <p>All of this only ever sees entities inside render distance, so it's
 * naturally scoped to "around the bot". Attribution is best-effort.
 */
public final class EntityWatcher {

	private static final long CHECK_INTERVAL_MILLIS = 1000L;
	/** Max distance (blocks) for crediting a nearby player with an action. */
	private static final double ATTRIBUTION_RADIUS = 8.0;
	/**
	 * A vanished crystal further than this from the bot is assumed to have simply
	 * left render distance (bot moved away), not exploded — so it isn't reported.
	 */
	private static final double EXPLOSION_REPORT_RADIUS = 24.0;
	/** A pearl suspended for longer than this before vanishing was a stasis pearl, not a quick teleport. */
	private static final long TELEPORT_MAX_MILLIS = 8000L;
	/** A pearl that vanished further than this from the bot just left render, rather than landing in view. */
	private static final double TELEPORT_REPORT_RADIUS = 24.0;

	private final MinecraftClient client;
	private final StasisBotConfig config;
	private final ChamberIndex index;
	private final IdentityResolver identity;
	private final DiscordNotifier discord;
	private final PlayerFeedback feedback;

	private final Map<Integer, PearlTrack> pearls = new HashMap<>();
	/** Crystal entity id → last known position (for explosion attribution). */
	private final Map<Integer, Vec3d> crystals = new HashMap<>();
	private final Set<Integer> tnts = new HashSet<>();
	private boolean primed = false;
	private long lastCheck = 0L;

	public EntityWatcher(MinecraftClient client, StasisBotConfig config, ChamberIndex index,
	                     IdentityResolver identity, DiscordNotifier discord) {
		this.client = client;
		this.config = config;
		this.index = index;
		this.identity = identity;
		this.discord = discord;
		this.feedback = new PlayerFeedback(client, config);
	}

	/** Call once per client tick. */
	public void tick() {
		boolean pearlOn = config.discordEventEnabled(DiscordEvent.PEARL_THROWN);
		boolean placedOn = config.discordEventEnabled(DiscordEvent.CRYSTAL_PLACED);
		boolean brokenOn = config.discordEventEnabled(DiscordEvent.CRYSTAL_BROKEN);
		boolean tntOn = config.discordEventEnabled(DiscordEvent.TNT_PRIMED);
		if (!discord.isReady() || !(pearlOn || placedOn || brokenOn || tntOn)) {
			reset();
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastCheck < CHECK_INTERVAL_MILLIS) return;
		lastCheck = now;

		ClientWorld world = client.world;
		ClientPlayerEntity self = client.player;
		if (world == null || self == null) { reset(); return; }

		Map<Integer, PearlTrack> currentPearls = new HashMap<>();
		Map<Integer, Vec3d> currentCrystals = new HashMap<>();
		Set<Integer> currentTnts = new HashSet<>();

		for (Entity e : world.getEntities()) {
			EntityType<?> type = e.getType();
			if (type == EntityType.ENDER_PEARL) {
				int id = e.getId();
				Vec3d pos = new Vec3d(e.getX(), e.getY(), e.getZ());
				PearlTrack t = pearls.get(id);
				if (t == null) {
					// First sight: note where it appeared, who threw it, and whether it's a
					// suspended stasis pearl (those persist and are a recharge, not a teleport).
					t = new PearlTrack(pos, now, pearlThrower(world, self, e), inAnyChamber(world, self, pos));
				}
				t.last = pos;
				currentPearls.put(id, t);
			} else if (type == EntityType.END_CRYSTAL) {
				int id = e.getId();
				BlockPos pos = e.getBlockPos();
				currentCrystals.put(id, Vec3d.ofCenter(pos));
				if (primed && !crystals.containsKey(id) && placedOn) {
					String who = nearestPlayer(world, self, Vec3d.ofCenter(pos));
					feedback.debug("end crystal placed" + (who != null ? " near " + who : ""));
					discord.notify(DiscordEvent.CRYSTAL_PLACED, who == null || !isBaseMember(who),
							DiscordText.crystalPlaced(config.language(), who), pos, distanceTo(self, pos));
				}
			} else if (type == EntityType.TNT) {
				int id = e.getId();
				currentTnts.add(id);
				if (primed && !tnts.contains(id) && tntOn) {
					BlockPos pos = e.getBlockPos();
					String who = nearestPlayer(world, self, Vec3d.ofCenter(pos));
					feedback.debug("TNT primed" + (who != null ? " near " + who : ""));
					discord.notify(DiscordEvent.TNT_PRIMED, who == null || !isBaseMember(who),
							DiscordText.tntPrimed(config.language(), who), pos, distanceTo(self, pos));
				}
			}
		}

		// Pearls that vanished since last scan: a free (non-stasis) pearl consumed quickly
		// and nearby is a teleport — report it with from→to (departure→arrival) coords.
		if (primed && pearlOn) {
			Vec3d selfPos = Vec3d.ofCenter(self.getBlockPos());
			for (Map.Entry<Integer, PearlTrack> gone : pearls.entrySet()) {
				if (currentPearls.containsKey(gone.getKey())) continue;
				PearlTrack t = gone.getValue();
				if (t.stasis) continue;                                  // suspended stasis pearl, not a throw
				if (inAnyChamber(world, self, t.last)) continue;         // dernière pos dans une chambre — vue en vol au 1er tick mais en stasis au final
				if (now - t.firstMillis > TELEPORT_MAX_MILLIS) continue; // sat around — not a quick teleport
				if (selfPos.squaredDistanceTo(t.last) > TELEPORT_REPORT_RADIUS * TELEPORT_REPORT_RADIUS) {
					continue; // vanished far away — it left render rather than landing in view
				}
				String who = t.thrower;
				BlockPos from = BlockPos.ofFloored(t.first);
				BlockPos to = BlockPos.ofFloored(t.last);
				feedback.debug("pearl teleport" + (who != null ? " by " + who : " (unknown thrower)"));
				discord.notify(DiscordEvent.PEARL_THROWN, who == null || !isBaseMember(who),
						DiscordText.pearlThrown(config.language(), who), from, to, distanceTo(self, to));
			}
		}

		// Crystals that vanished since last scan: likely explosions (if still nearby).
		if (primed && brokenOn) {
			Vec3d selfPos = Vec3d.ofCenter(self.getBlockPos());
			for (Map.Entry<Integer, Vec3d> gone : crystals.entrySet()) {
				if (currentCrystals.containsKey(gone.getKey())) continue;
				Vec3d at = gone.getValue();
				if (at == null || selfPos.squaredDistanceTo(at) > EXPLOSION_REPORT_RADIUS * EXPLOSION_REPORT_RADIUS) {
					continue; // too far — it just left render, not an explosion.
				}
				String who = nearestPlayer(world, self, at);
				BlockPos pos = BlockPos.ofFloored(at);
				feedback.debug("end crystal exploded" + (who != null ? " near " + who : ""));
				discord.notify(DiscordEvent.CRYSTAL_BROKEN, who == null || !isBaseMember(who),
						DiscordText.crystalBroken(config.language(), who), pos, distanceTo(self, pos));
			}
		}

		pearls.clear();
		pearls.putAll(currentPearls);
		crystals.clear();
		crystals.putAll(currentCrystals);
		tnts.clear();
		tnts.addAll(currentTnts);
		primed = true;
	}

	/** The pearl's thrower if known (owner), else the nearest player, else null. */
	private String pearlThrower(ClientWorld world, ClientPlayerEntity self, Entity pearl) {
		if (pearl instanceof ProjectileEntity proj) {
			Entity owner = proj.getOwner();
			if (owner instanceof PlayerEntity p) {
				String name = p.getGameProfile().name();
				if (name != null && !name.equalsIgnoreCase(self.getGameProfile().name())) return name;
			}
		}
		return nearestPlayer(world, self, Vec3d.ofCenter(pearl.getBlockPos()));
	}

	/** True when {@code pos} sits within a chamber's pearl radius — i.e. it's a suspended stasis pearl. */
	private boolean inAnyChamber(ClientWorld world, ClientPlayerEntity self, Vec3d pos) {
		double rSq = config.pearlSearchRadius() * config.pearlSearchRadius();
		for (StasisChamber c : index.chambers(world, self.getBlockPos())) {
			if (Vec3d.ofCenter(c.trigger()).squaredDistanceTo(pos) <= rSq) return true;
		}
		return false;
	}

	/**
	 * Nearest other player to a position within {@link #ATTRIBUTION_RADIUS}, or
	 * {@code null} when nobody is close enough. The bot itself is always excluded.
	 */
	private String nearestPlayer(ClientWorld world, ClientPlayerEntity self, Vec3d at) {
		String selfName = self.getGameProfile().name();
		String best = null;
		double bestSq = ATTRIBUTION_RADIUS * ATTRIBUTION_RADIUS;
		for (PlayerEntity p : world.getPlayers()) {
			String name = p.getGameProfile().name();
			if (name == null || name.equalsIgnoreCase(selfName)) continue;
			double d = p.squaredDistanceTo(at);
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

	/** Whole-block distance from the bot to a position, for the optional distance tag. */
	private static int distanceTo(ClientPlayerEntity self, BlockPos pos) {
		return (int) Math.round(Math.sqrt(self.squaredDistanceTo(Vec3d.ofCenter(pos))));
	}

	private void reset() {
		pearls.clear();
		crystals.clear();
		tnts.clear();
		primed = false;
	}

	/** A pearl we've seen, with enough trail to tell a teleport from a suspended stasis pearl. */
	private static final class PearlTrack {
		final Vec3d first;
		final long firstMillis;
		final String thrower;
		final boolean stasis;
		Vec3d last;

		PearlTrack(Vec3d first, long firstMillis, String thrower, boolean stasis) {
			this.first = first;
			this.last = first;
			this.firstMillis = firstMillis;
			this.thrower = thrower;
			this.stasis = stasis;
		}
	}
}
