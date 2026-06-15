package fr.nyuway.stasisbot.service;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Remembers exactly where the bot was standing (block, sub-block position and
 * facing) when the <em>first</em> request of a chain began, so it can walk back
 * and face the same way afterwards.
 *
 * <p>The recorded origin deliberately survives across chained requests — it is
 * only ever cleared by {@link #reset()} once the bot has actually returned home.
 * {@link #recordIfAbsent} is therefore a no-op while a chain is in progress.
 */
final class AnchorMemory {

	private BlockPos pos;     // block the bot started on
	private Vec3d vec;        // exact sub-block position, for precise re-centring
	private float yaw;
	private float pitch;
	private boolean moved;    // did the bot leave the anchor during this chain?

	/** Record the current stance, but only if nothing is remembered yet. */
	void recordIfAbsent(ClientPlayerEntity self) {
		if (pos != null) return;
		pos = self.getBlockPos();
		vec = new Vec3d(self.getX(), self.getY(), self.getZ());
		yaw = self.getYaw();
		pitch = self.getPitch();
	}

	boolean isRecorded() {
		return pos != null;
	}

	void markMoved() {
		moved = true;
	}

	boolean hasMoved() {
		return moved;
	}

	BlockPos pos() {
		return pos;
	}

	Vec3d vec() {
		return vec;
	}

	float yaw() {
		return yaw;
	}

	float pitch() {
		return pitch;
	}

	/** Clear all memory — only after the bot has truly returned home. */
	void reset() {
		pos = null;
		vec = null;
		yaw = 0f;
		pitch = 0f;
		moved = false;
	}
}
