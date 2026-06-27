package fr.nyuway.stasisbot.activation;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import net.minecraft.entity.player.PlayerEntity;

import java.util.Locale;

/**
 * Native Baritone "follow a player" control. Like {@link BaritoneNavigator} this directly
 * references the Baritone API, so it must only be touched when Baritone is actually loaded
 * (callers guard with {@link BaritoneSupport#isAvailable()}). Following keeps re-pathing to
 * the target on its own — unlike a one-shot walk, it tracks the player as they move.
 */
public final class BaritoneFollow {

	private BaritoneFollow() {
	}

	/** Continuously follow the player with this (case-insensitive) name. */
	public static void followPlayer(String name) {
		if (name == null || name.isBlank()) return;
		String key = name.trim().toLowerCase(Locale.ROOT);
		IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
		baritone.getFollowProcess().follow(e ->
				e instanceof PlayerEntity p
						&& p.getGameProfile().name() != null
						&& p.getGameProfile().name().toLowerCase(Locale.ROOT).equals(key));
	}

	/** Stop following and cancel any pathing (cancelEverything also ends the follow process). */
	public static void stop() {
		IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
		baritone.getPathingBehavior().cancelEverything();
	}
}
