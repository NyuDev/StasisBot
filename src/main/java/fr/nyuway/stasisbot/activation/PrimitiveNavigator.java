package fr.nyuway.stasisbot.activation;

import fr.nyuway.stasisbot.StasisBot;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.model.StasisChamber;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * A built-in, dependency-free fallback navigator: it simply faces the target and
 * walks straight toward it, auto-jumping over one-block steps and obstacles.
 *
 * <p>It cannot route around large walls, holes or cliffs the way Baritone does —
 * it is the "primitive but functional" mode used when Baritone isn't installed.
 * Driven by simulating the movement key inputs the player would press.
 */
public final class PrimitiveNavigator implements Navigator {

	/** Consider us stuck if horizontal distance to the target stops shrinking for this long. */
	private static final double PROGRESS_EPSILON = 0.5;

	private final StasisBotConfig config;

	private boolean active;
	private long startedAt;
	private long lastProgressAt;
	private double bestDistance;

	public PrimitiveNavigator(StasisBotConfig config) {
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
		beginMove();
	}

	@Override
	public void startExact(BlockPos target) {
		beginMove();
	}

	private void beginMove() {
		active = true;
		startedAt = System.currentTimeMillis();
		lastProgressAt = startedAt;
		bestDistance = Double.MAX_VALUE;
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
		double dx = centre.x - player.getX();
		double dz = centre.z - player.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);

		// Arrived: stop pressing keys.
		if (player.getEyePos().distanceTo(centre) <= arriveDist || horizontal <= arriveDist) {
			stop(client);
			return Status.ARRIVED;
		}

		long now = System.currentTimeMillis();
		if (now - startedAt > config.navTimeoutMillis()) {
			StasisBot.LOGGER.warn("Primitive navigation timed out");
			stop(client);
			return Status.FAILED;
		}

		// Stuck detection: if we haven't gotten meaningfully closer in a while, give up.
		if (horizontal < bestDistance - PROGRESS_EPSILON) {
			bestDistance = horizontal;
			lastProgressAt = now;
		} else if (now - lastProgressAt > config.navStuckMillis()) {
			StasisBot.LOGGER.warn("Primitive navigation stuck — no progress");
			stop(client);
			return Status.FAILED;
		}

		// Face the target and walk straight at it.
		float yaw = (float) Math.toDegrees(MathHelper.atan2(dz, dx)) - 90.0f;
		player.setYaw(yaw);
		player.setPitch(0.0f);

		press(client, true);
		// Auto-jump over steps/obstacles, or when the target is clearly above us.
		boolean needJump = player.horizontalCollision || centre.y - player.getY() > 0.6;
		client.options.jumpKey.setPressed(needJump && player.isOnGround());

		return Status.MOVING;
	}

	@Override
	public void stop(MinecraftClient client) {
		active = false;
		press(client, false);
		client.options.jumpKey.setPressed(false);
	}

	/** Press or release the forward + sprint movement keys. */
	private void press(MinecraftClient client, boolean down) {
		client.options.forwardKey.setPressed(down);
		client.options.sprintKey.setPressed(down);
	}
}
