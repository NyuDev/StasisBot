package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.StasisBot;
import fr.nyuway.stasisbot.config.DiscordEvent;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Owns the bot's death/respawn lifecycle: detect a death, abort whatever the bot
 * was doing, auto-respawn, and — when {@code returnHomeOnDeath} is on — walk back
 * home once it's alive again. Kept apart from {@link HomeService}'s home-request
 * state machine, which it only touches through the small {@link Owner} callback.
 */
final class DeathRespawnController {

	/** Past this distance (blocks) the post-death walk home is abandoned as hopeless. */
	private static final double RETURN_ABANDON_DISTANCE = 1024.0;
	/** How long to wait for the server's death message before announcing without a cause. */
	private static final long DEATH_REASON_GRACE_MILLIS = 800L;

	/** Callbacks into the host so this controller never reaches into its state machine directly. */
	interface Owner {
		/** Abort all current work: stop nav, abort restock, clear the queue, finish the request, reset the anchor. */
		void abortForDeath();
		/** Begin the precise walk back to {@code home} (the host's RETURN phase). */
		void beginReturn(BlockPos home);
		/** Announce a global (bot/base) Discord event. */
		void announceGlobal(DiscordEvent event, String message);
	}

	private final MinecraftClient client;
	private final StasisBotConfig config;
	private final BotDeathInfo deathInfo;
	private final PlayerFeedback feedback;
	private final Owner owner;

	private boolean deadHandled;          // already reacted to the current death?
	private boolean returningFromDeath;   // respawned and owe a walk back home?
	private boolean deathAnnouncePending; // death detected, waiting to announce (with a cause if it arrives)
	private long deathDetectedAt;         // when the current death was first seen (for the cause grace window)
	private BlockPos homeBase;            // the bot's resting spot, remembered while idle
	private BlockPos deathReturnPos;      // where to walk once respawned (locked in at death)

	DeathRespawnController(MinecraftClient client, StasisBotConfig config, BotDeathInfo deathInfo,
	                       PlayerFeedback feedback, Owner owner) {
		this.client = client;
		this.config = config;
		this.deathInfo = deathInfo;
		this.feedback = feedback;
		this.owner = owner;
	}

	/** Remember the bot's resting spot so it can walk back here after a death (called while genuinely idle). */
	void rememberBase(BlockPos pos) {
		homeBase = pos;
	}

	/**
	 * If the bot died, abort whatever it was doing, auto-respawn, and — when
	 * {@code returnHomeOnDeath} is on — walk back home once it's alive again. Returns
	 * true while the death is being handled, so the caller skips the rest of its tick.
	 * The walk-back target is locked in at the moment of death: the fixed home if one is
	 * pinned, otherwise the last spot the bot was resting at (its {@code homeBase}). After
	 * a death the bot respawns at world spawn / its bed, so the per-request anchor is
	 * meaningless — the remembered base is what brings it back where it belongs.
	 */
	boolean tick() {
		ClientPlayerEntity self = client.player;
		if (self == null) return false;

		// Flush a deferred bot-death announcement once the server's death message has
		// landed (so we can quote the cause), or after a short grace if it never does.
		if (deathAnnouncePending) {
			String reason = deathInfo != null ? deathInfo.recentReason() : null;
			if (reason != null || System.currentTimeMillis() - deathDetectedAt > DEATH_REASON_GRACE_MILLIS) {
				deathAnnouncePending = false;
				owner.announceGlobal(DiscordEvent.BOT_DIED, DiscordText.died(config.language(), reason));
				if (deathInfo != null) deathInfo.clear();
			}
		}

		boolean dead = self.isDead() || self.getHealth() <= 0.0f;
		if (dead) {
			if (!deadHandled) {
				deadHandled = true;
				StasisBot.LOGGER.info("[death] bot died — aborting current work and respawning");
				feedback.debug("bot DIED — aborting and respawning");
				// Defer the Discord announce a moment so the parsed death cause can catch up.
				deathAnnouncePending = true;
				deathDetectedAt = System.currentTimeMillis();
				owner.abortForDeath();
				// Lock the return target now, while we still know where the bot was: the fixed
				// home if pinned, else its last resting spot, else (never idled yet) right here.
				deathReturnPos = config.hasReturnPos()
						? new BlockPos(config.returnX(), config.returnY(), config.returnZ())
						: (homeBase != null ? homeBase : self.getBlockPos());
				returningFromDeath = config.returnHomeOnDeath();
				self.requestRespawn();
			}
			return true; // still dead / on the respawn screen
		}

		// Alive again after a death we handled.
		if (deadHandled) {
			deadHandled = false;
			owner.announceGlobal(DiscordEvent.BOT_RESPAWNED, DiscordText.respawned(config.language()));
			if (returningFromDeath) {
				returningFromDeath = false;
				if (deathReturnPos != null) {
					BlockPos home = deathReturnPos;
					deathReturnPos = null;
					int dist = (int) Math.round(Math.sqrt(self.squaredDistanceTo(Vec3d.ofCenter(home))));
					// If the bot respawned far away (world spawn / distant bed), walking back
					// could take forever — give up and just say so rather than trek across the map.
					if (dist > RETURN_ABANDON_DISTANCE) {
						StasisBot.LOGGER.info("[death] home is {} blocks away — abandoning the walk back", dist);
						feedback.debug("home too far (" + dist + " blocks) — giving up the return");
						owner.announceGlobal(DiscordEvent.RETURN_TOO_FAR, DiscordText.returnTooFar(config.language(), dist));
						return false;
					}
					StasisBot.LOGGER.info("[death] respawned — walking back to base");
					feedback.debug("respawned — walking back home");
					// Route through the host's RETURN phase (exact GoalBlock + on-block arrival
					// check) so the bot lands precisely on its home block, not ~2 blocks short.
					owner.beginReturn(home);
					return true; // RETURN drives the precise walk home from the next tick
				}
			}
		}
		return false;
	}
}
