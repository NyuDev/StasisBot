package fr.nyuway.stasisbot.service;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Small shared geometry helpers used by the watchers. */
final class Proximity {

	private Proximity() {}

	/** Whole-block distance from the bot to a position, for the optional distance tag. */
	static int distanceTo(ClientPlayerEntity self, BlockPos pos) {
		return (int) Math.round(Math.sqrt(self.squaredDistanceTo(Vec3d.ofCenter(pos))));
	}
}
