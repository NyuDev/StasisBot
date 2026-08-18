package fr.nyuway.stasisbot.activation;

import fr.nyuway.stasisbot.StasisBot;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.model.StasisChamber;
import fr.nyuway.stasisbot.scan.ChamberScanner;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Fires a chamber by making the bot look at and right-click its trigger block —
 * the same interaction packet a player sends when flipping the lever.
 *
 * <p>Must be invoked on the client thread (the {@code HomeService} guarantees it).
 */
public final class StasisActivator {

	private final StasisBotConfig config;

	public StasisActivator(StasisBotConfig config) {
		this.config = config;
	}

	/** Fire the chamber's own (sign-paired) trigger. */
	public boolean fire(MinecraftClient client, StasisChamber chamber) {
		return fireAt(client, chamber.trigger());
	}

	/**
	 * Right-click a specific trigger block. The pearl-aware fire path resolves which trapdoor to
	 * open (see {@link #resolveTrigger}) and passes it here, so a stasis with several trapdoors
	 * opens the one actually holding the pearl rather than whichever sits nearest the sign.
	 */
	public boolean fireAt(MinecraftClient client, BlockPos trigger) {
		ClientPlayerEntity player = client.player;
		ClientPlayerInteractionManager interaction = client.interactionManager;
		if (player == null || interaction == null || trigger == null) return false;

		Vec3d target = Vec3d.ofCenter(trigger);
		if (player.getEyePos().distanceTo(target) > config.reach()) {
			StasisBot.LOGGER.warn("Trigger {} is out of reach; park the bot closer", trigger.toShortString());
			return false;
		}

		if (config.autoLook()) {
			aimAt(player, target);
		}
		BlockHitResult hit = new BlockHitResult(target, Direction.UP, trigger, false);
		interaction.interactBlock(player, Hand.MAIN_HAND, hit);
		player.swingHand(Hand.MAIN_HAND);
		return true;
	}

	/**
	 * Which trigger block to open for {@code chamber}, given where its pearl actually hangs.
	 * A stasis can have several trapdoors; the one to open is the one the pearl rests on — the
	 * trigger nearest the pearl, searched within {@link StasisBotConfig#triggerSearchRadius()} of
	 * it. Falls back to the chamber's sign-paired trigger when the pearl is unknown or no trigger
	 * sits by it (e.g. a lever/button design), so single-trapdoor stasis behave exactly as before.
	 */
	public BlockPos resolveTrigger(ClientWorld world, StasisChamber chamber, Vec3d pearlPos) {
		if (world == null || pearlPos == null) return chamber.trigger();
		int r = config.triggerSearchRadius();
		BlockPos pearlBlock = BlockPos.ofFloored(pearlPos);
		BlockPos best = null;
		double bestDistSq = Double.MAX_VALUE;
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					cursor.set(pearlBlock.getX() + dx, pearlBlock.getY() + dy, pearlBlock.getZ() + dz);
					if (!ChamberScanner.isTriggerBlock(world.getBlockState(cursor))) continue;
					double distSq = Vec3d.ofCenter(cursor).squaredDistanceTo(pearlPos);
					if (distSq < bestDistSq) {
						bestDistSq = distSq;
						best = cursor.toImmutable();
					}
				}
			}
		}
		return best != null ? best : chamber.trigger();
	}

	private static void aimAt(ClientPlayerEntity player, Vec3d target) {
		Vec3d eye = player.getEyePos();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) Math.toDegrees(MathHelper.atan2(dz, dx)) - 90.0f;
		float pitch = (float) -Math.toDegrees(MathHelper.atan2(dy, horizontal));
		player.setYaw(yaw);
		player.setPitch(MathHelper.clamp(pitch, -90.0f, 90.0f));
	}
}
