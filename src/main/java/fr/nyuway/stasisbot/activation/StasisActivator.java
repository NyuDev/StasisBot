package fr.nyuway.stasisbot.activation;

import fr.nyuway.stasisbot.StasisBot;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.model.StasisChamber;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
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

	public boolean fire(MinecraftClient client, StasisChamber chamber) {
		ClientPlayerEntity player = client.player;
		ClientPlayerInteractionManager interaction = client.interactionManager;
		if (player == null || interaction == null) return false;

		BlockPos trigger = chamber.trigger();
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
