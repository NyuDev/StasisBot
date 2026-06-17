package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.activation.Navigation;
import fr.nyuway.stasisbot.activation.Navigator;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;

/**
 * Drives a master-directed move ({@code come} / {@code goto}) to a fixed block,
 * independent of the home-request state machine. It shares {@link HomeService}'s
 * {@link Navigation} instance so only one path is ever active at a time, but owns
 * its own destination so the two concerns don't tangle.
 */
final class ManualNavController {

	private final MinecraftClient client;
	private final StasisBotConfig config;
	private final Navigation navigator;

	/** Current master-directed destination, or {@code null} when idle. */
	private BlockPos target;

	ManualNavController(MinecraftClient client, StasisBotConfig config, Navigation navigator) {
		this.client = client;
		this.config = config;
		this.navigator = navigator;
	}

	/** Whether a manual move is currently in progress. */
	boolean active() {
		return target != null;
	}

	/** Begin walking to {@code dest} (exact block arrival). */
	void start(BlockPos dest) {
		target = dest;
		navigator.startExact(dest);
	}

	/** Stop the navigator and forget the destination. */
	void stop() {
		navigator.stop(client);
		target = null;
	}

	/** Forget the destination without touching the navigator (caller already stopped it). */
	void clear() {
		target = null;
	}

	/**
	 * Advance the move one tick.
	 *
	 * @return {@code true} while still walking (the caller should keep owning the
	 *         tick), {@code false} once arrived, failed, or stopped.
	 */
	boolean tick() {
		ClientPlayerEntity self = client.player;
		if (self == null || target == null) { stop(); return false; }
		if (self.getBlockPos().equals(target)) { stop(); return false; }
		Navigator.Status status = navigator.tick(client, target, Math.max(0.6, config.navGoalRange()));
		if (status != Navigator.Status.MOVING) { stop(); return false; }
		return true;
	}
}
