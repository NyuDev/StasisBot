package fr.nyuway.stasisbot.activation;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.ICustomGoalProcess;
import fr.nyuway.stasisbot.StasisBot;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.model.StasisChamber;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Walks the bot to a target using Baritone's pathfinder, so it routes
 * <em>around</em> holes, walls and drops instead of marching blindly forward.
 *
 * <p>Block breaking and placing are forced off while we navigate, so the bot can
 * never grief the route — it only walks/jumps/parkours along existing terrain.
 *
 * <p><strong>Baritone is an optional dependency.</strong> This class directly
 * references the Baritone API, so it is only ever instantiated (via
 * {@link BaritoneSupport}) when the Baritone mod is actually loaded — otherwise
 * the JVM would fail to link these classes. {@link Navigation} guards that.
 */
public final class BaritoneNavigator implements Navigator {

	/** Ignore Baritone's "not pathing yet" state for this long after starting. */
	private static final long CALC_GRACE_MILLIS = 2500L;

	private final StasisBotConfig config;

	private long startedAt;
	private boolean active;

	// Saved so we can restore the user's Baritone settings when we're done.
	private boolean savedAllowBreak;
	private boolean savedAllowPlace;
	private boolean settingsTouched;

	public BaritoneNavigator(StasisBotConfig config) {
		this.config = config;
	}

	@Override
	public boolean isActive() {
		return active;
	}

	@Override
	public void start(StasisChamber chamber) {
		start(chamber.trigger());
	}

	@Override
	public void start(BlockPos target) {
		startedAt = System.currentTimeMillis();
		active = true;
		try {
			IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
			applySafeSettings();
			ICustomGoalProcess goal = baritone.getCustomGoalProcess();
			int range = Math.max(1, config.navGoalRange());
			goal.setGoalAndPath(new GoalNear(target, range));
		} catch (Throwable t) {
			StasisBot.LOGGER.error("Baritone navigation failed to start", t);
			active = false;
		}
	}

	@Override
	public void startExact(BlockPos target) {
		startedAt = System.currentTimeMillis();
		active = true;
		try {
			IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
			applySafeSettings();
			baritone.getCustomGoalProcess().setGoalAndPath(
					new GoalBlock(target.getX(), target.getY(), target.getZ()));
		} catch (Throwable t) {
			StasisBot.LOGGER.error("Baritone exact navigation failed to start", t);
			active = false;
		}
	}

	@Override
	public Status tick(MinecraftClient client, StasisChamber chamber) {
		return tick(client, chamber.trigger(), config.reach() * 0.85);
	}

	@Override
	public Status tick(MinecraftClient client, BlockPos target, double arriveDist) {
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null) {
			stop(client);
			return Status.FAILED;
		}

		Vec3d centre = Vec3d.ofCenter(target);
		if (player.getEyePos().distanceTo(centre) <= arriveDist) {
			stop(client);
			return Status.ARRIVED;
		}

		long now = System.currentTimeMillis();
		if (now - startedAt > config.navTimeoutMillis()) {
			StasisBot.LOGGER.warn("Baritone navigation timed out");
			stop(client);
			return Status.FAILED;
		}

		// Once past the initial calc window, if Baritone is no longer pathing and
		// its goal process has gone inactive, it has either finished (but we're not
		// in reach) or found no path — either way we're done trying.
		if (now - startedAt > CALC_GRACE_MILLIS) {
			try {
				IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
				boolean pathing = baritone.getPathingBehavior().isPathing();
				boolean processActive = baritone.getCustomGoalProcess().isActive();
				if (!pathing && !processActive) {
					StasisBot.LOGGER.warn("Baritone stopped without reaching the trigger");
					stop(client);
					return Status.FAILED;
				}
			} catch (Throwable t) {
				StasisBot.LOGGER.error("Baritone navigation error", t);
				stop(client);
				return Status.FAILED;
			}
		}

		return Status.MOVING;
	}

	@Override
	public void stop(MinecraftClient client) {
		active = false;
		try {
			BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
		} catch (Throwable ignored) {
			// Baritone may not be initialised yet; nothing to cancel.
		}
		restoreSettings();
	}

	private void applySafeSettings() {
		Settings s = BaritoneAPI.getSettings();
		if (!settingsTouched) {
			savedAllowBreak = s.allowBreak.value;
			savedAllowPlace = s.allowPlace.value;
			settingsTouched = true;
		}
		// Never modify the world to reach a target: route around obstacles instead.
		s.allowBreak.value = false;
		s.allowPlace.value = false;
	}

	private void restoreSettings() {
		if (!settingsTouched) return;
		try {
			Settings s = BaritoneAPI.getSettings();
			s.allowBreak.value = savedAllowBreak;
			s.allowPlace.value = savedAllowPlace;
		} catch (Throwable ignored) {
			// ignore
		}
		settingsTouched = false;
	}
}
