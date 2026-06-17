package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.config.DiscordEvent;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Tracks the pearl the bot just dropped for a player and reports when it gets
 * picked up ({@link DiscordEvent#PEARL_PICKED_UP}). After a drop we watch the
 * dropped item entity near the drop spot; once we've actually seen it and it then
 * vanishes while the bot is still close enough to observe the area, we credit the
 * recipient with the pickup.
 *
 * <p>Deliberately conservative: if the bot walks too far from the drop (so the
 * item could just have unloaded) or the window elapses, tracking stops silently
 * rather than reporting a pickup that may not have happened.
 */
final class PearlDropTracker {

	/** Box half-extent (blocks) around the drop spot to look for the item. */
	private static final double SEARCH_RADIUS = 4.0;
	/** If the bot strays past this from the drop, we can't trust "item gone" = pickup. */
	private static final double OBSERVE_RANGE = 28.0;
	/** Give up waiting for a pickup after this long. */
	private static final long WINDOW_MILLIS = 60_000L;

	private final MinecraftClient client;
	private final StasisBotConfig config;
	private final DiscordNotifier discord;
	private final PlayerFeedback feedback;

	private boolean active;
	private boolean seen;       // have we actually observed the dropped item yet?
	private Vec3d dropPos;
	private String player;
	private boolean outsider;
	private long deadline;

	PearlDropTracker(MinecraftClient client, StasisBotConfig config, DiscordNotifier discord, PlayerFeedback feedback) {
		this.client = client;
		this.config = config;
		this.discord = discord;
		this.feedback = feedback;
	}

	/** Begin watching for the pearl dropped at {@code pos} to be picked up by {@code player}. */
	void track(BlockPos pos, String player, boolean outsider) {
		this.active = true;
		this.seen = false;
		this.dropPos = Vec3d.ofCenter(pos);
		this.player = player;
		this.outsider = outsider;
		this.deadline = System.currentTimeMillis() + WINDOW_MILLIS;
	}

	/** Call once per client tick. */
	void tick() {
		if (!active) return;
		if (!discord.isReady() || !config.discordEventEnabled(DiscordEvent.PEARL_PICKED_UP)) { stop(); return; }

		ClientWorld world = client.world;
		ClientPlayerEntity self = client.player;
		if (world == null || self == null) { stop(); return; }

		// Too far to trust what we see: the item may simply have unloaded, not been taken.
		if (self.getEyePos().squaredDistanceTo(dropPos) > OBSERVE_RANGE * OBSERVE_RANGE) { stop(); return; }

		if (pearlItemPresent(world)) {
			seen = true;
		} else if (seen) {
			feedback.debug(player + " picked up their pearl");
			discord.notify(DiscordEvent.PEARL_PICKED_UP, outsider, DiscordText.pickedUp(config.language(), player));
			stop();
			return;
		}

		if (System.currentTimeMillis() > deadline) stop();
	}

	private boolean pearlItemPresent(ClientWorld world) {
		Box box = Box.of(dropPos, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2);
		double rSq = SEARCH_RADIUS * SEARCH_RADIUS;
		var items = world.getEntitiesByType(EntityType.ITEM, box,
				(ItemEntity e) -> e.getStack().getItem() == Items.ENDER_PEARL
						&& e.squaredDistanceTo(dropPos) <= rSq);
		return !items.isEmpty();
	}

	private void stop() {
		active = false;
		seen = false;
		dropPos = null;
		player = null;
	}
}
