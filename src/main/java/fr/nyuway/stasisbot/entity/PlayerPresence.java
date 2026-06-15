package fr.nyuway.stasisbot.entity;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/**
 * Answers the two safety questions asked around a teleport: is the requester
 * actually connected, and have they materialised next to the trap after the pull.
 */
public final class PlayerPresence {

	private PlayerPresence() {
	}

	/** True if {@code name} appears in the server player list (i.e. is online). */
	public static boolean isOnline(MinecraftClient client, String name) {
		if (client.player == null || client.player.networkHandler == null || name == null) return false;
		return client.player.networkHandler.getPlayerListEntry(name) != null;
	}

	/**
	 * True once a loaded player entity named {@code name} is within {@code radius}
	 * blocks of {@code trigger} — our proof the pull actually landed them here.
	 */
	public static boolean hasArrived(MinecraftClient client, String name, BlockPos trigger, double radius) {
		if (client.world == null || name == null) return false;
		Vec3d centre = Vec3d.ofCenter(trigger);
		double rSq = radius * radius;
		String key = name.toLowerCase(Locale.ROOT);
		for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
			if (!p.getGameProfile().name().toLowerCase(Locale.ROOT).equals(key)) continue;
			double dx = p.getX() - centre.x;
			double dy = p.getY() - centre.y;
			double dz = p.getZ() - centre.z;
			if (dx * dx + dy * dy + dz * dz <= rSq) return true;
		}
		return false;
	}
}
