package fr.nyuway.stasisbot.activation;

import fr.nyuway.stasisbot.StasisBot;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.model.StasisChamber;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/**
 * Routes navigation to either {@link BaritoneNavigator} (when Baritone is
 * installed and enabled in config) or the built-in {@link PrimitiveNavigator}.
 *
 * <p>The choice is made at the start of each movement and kept for its whole
 * duration (start → tick… → stop), so a single path never switches engines
 * mid-way. Baritone is preferred by default whenever it's available.
 */
public final class Navigation implements Navigator {

	private final StasisBotConfig config;
	private final PrimitiveNavigator primitive;
	private Navigator baritone;   // lazily created; null when Baritone isn't usable
	private Navigator current;    // the engine driving the active movement

	public Navigation(StasisBotConfig config) {
		this.config = config;
		this.primitive = new PrimitiveNavigator(config);
	}

	/** True when Baritone is installed (so the GUI can offer the toggle). */
	public boolean baritoneAvailable() {
		return BaritoneSupport.isAvailable();
	}

	/** Decide which engine to use for the movement that's about to start. */
	private Navigator pick() {
		if (config.useBaritone() && BaritoneSupport.isAvailable()) {
			if (baritone == null) {
				baritone = BaritoneSupport.create(config);
			}
			if (baritone != null) return baritone;
		}
		return primitive;
	}

	@Override
	public void start(StasisChamber chamber) {
		current = pick();
		logEngine();
		current.start(chamber);
	}

	@Override
	public void start(BlockPos target) {
		current = pick();
		logEngine();
		current.start(target);
	}

	@Override
	public void startExact(BlockPos target) {
		current = pick();
		logEngine();
		current.startExact(target);
	}

	@Override
	public Status tick(MinecraftClient client, StasisChamber chamber) {
		return current != null ? current.tick(client, chamber) : Status.FAILED;
	}

	@Override
	public Status tick(MinecraftClient client, BlockPos target, double arriveDist) {
		return current != null ? current.tick(client, target, arriveDist) : Status.FAILED;
	}

	@Override
	public void stop(MinecraftClient client) {
		// Stop whatever is active; also release primitive keys defensively.
		if (current != null) current.stop(client);
		primitive.stop(client);
	}

	@Override
	public boolean isActive() {
		return current != null && current.isActive();
	}

	private void logEngine() {
		StasisBot.LOGGER.info("[StasisBot] Navigating with {}",
				current == primitive ? "primitive walker" : "Baritone");
	}
}
