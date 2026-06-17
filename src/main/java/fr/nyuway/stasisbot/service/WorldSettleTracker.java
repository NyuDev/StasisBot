package fr.nyuway.stasisbot.service;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Tracks whether the bot's view of the world has just been torn down and rebuilt
 * — on death/respawn, a reconnect, or a dimension change — so the diff-based
 * watchers don't mistake the reload for a flood of real events.
 *
 * <p>The watchers are all "diff since last scan": a crystal/pearl/player that
 * wasn't there last tick is treated as new. After a respawn the client unloads
 * and re-streams every nearby entity with fresh ids, which otherwise looks like
 * every stasis being recharged, every crystal placed, and every player walking
 * in again. While {@link #settling()} is true the watchers re-seed their baseline
 * silently instead of announcing it.
 *
 * <p>Two triggers are covered: the player going not-ready (dead / no world) and
 * back, and the {@link net.minecraft.client.world.ClientWorld} instance itself
 * being replaced (reconnect / dimension change). Either one opens a short grace
 * window after the world is ready again, long enough for chunks to stream back.
 */
public final class WorldSettleTracker {

	/** Grace after the world is ready again before live diffing resumes. */
	private static final long SETTLE_MILLIS = 6000L;

	private final MinecraftClient client;
	private Object lastWorld;
	private boolean wasReady;
	private boolean everReady;
	private long readyAt;

	public WorldSettleTracker(MinecraftClient client) {
		this.client = client;
	}

	/** Call once per client tick, before the watchers run. */
	public void tick() {
		Object world = client.world;
		if (world != lastWorld) {
			// New ClientWorld object: reconnect or dimension change — force a fresh
			// ready-transition so the settle window reopens.
			lastWorld = world;
			wasReady = false;
		}
		boolean ready = isReady();
		if (ready && !wasReady) {
			readyAt = System.currentTimeMillis();
			everReady = true;
		}
		wasReady = ready;
	}

	private boolean isReady() {
		ClientPlayerEntity self = client.player;
		return client.world != null && self != null && !self.isDead() && self.getHealth() > 0.0f;
	}

	/**
	 * True while the world isn't fully back: still loading, the bot is dead, or we're
	 * within the post-(re)load grace window. Watchers should re-seed their baseline
	 * without announcing while this holds.
	 */
	public boolean settling() {
		if (!isReady()) return true;
		return !everReady || System.currentTimeMillis() - readyAt < SETTLE_MILLIS;
	}
}
