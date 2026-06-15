package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.config.StasisBotConfig;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Self-contained chest-restock routine: when the bot runs low on ender pearls it
 * finds a nearby container, opens it and transfers pearls one at a time (gently,
 * to stay server-friendly) up to a target count.
 *
 * <p>It is its own little tick-driven state machine, completely separate from the
 * main home-request flow. {@link #begin()} decides whether a restock is needed
 * and {@link #tick()} advances it, returning {@code false} once finished.
 */
final class RestockController {

	/** How many blocks around the bot to scan for a restock chest. */
	private static final int RESTOCK_RADIUS = 4;
	/** How long to wait (ms) for a chest screen to open after interacting. */
	private static final long RESTOCK_SCREEN_TIMEOUT = 2000L;
	/** Restock only when the bot's pearl count drops below this. */
	private static final int REFILL_THRESHOLD = 8;
	/** Refill up to (never above) this many pearls. */
	private static final int REFILL_TARGET = 16;
	/** Delay (ms) between each single-pearl transfer, to be gentle on the server. */
	private static final long RESTOCK_STEP_DELAY = 200L;

	private final MinecraftClient client;
	private final StasisBotConfig config;
	private final PlayerFeedback feedback;

	private BlockPos chest;
	private long openedAt;
	private boolean interacted;
	private long lastActionAt;
	private int cursorSource = -1;

	RestockController(MinecraftClient client, StasisBotConfig config, PlayerFeedback feedback) {
		this.client = client;
		this.config = config;
		this.feedback = feedback;
	}

	/**
	 * Decide whether a restock should run now. When it returns {@code true} a chest
	 * has been located and the caller should switch into the restock phase; when it
	 * returns {@code false} there is nothing to do.
	 */
	boolean begin() {
		if (!(config.dropPearlForPlayer() && countPearls() < REFILL_THRESHOLD)) return false;
		ClientPlayerEntity self = client.player;
		ClientWorld world = client.world;
		if (self == null || world == null) return false;

		BlockPos found = findNearbyChest(self, world);
		if (found == null) return false;

		feedback.debug("Low on pearls (" + countPearls() + "/" + REFILL_THRESHOLD
				+ ") — restocking from chest at " + found.toShortString());
		chest = found;
		openedAt = 0L;
		interacted = false;
		lastActionAt = 0L;
		cursorSource = -1;
		return true;
	}

	/** Drive one tick; {@code true} while still busy, {@code false} once finished. */
	boolean tick() {
		ClientPlayerEntity self = client.player;
		ClientWorld world = client.world;
		if (self == null || world == null || chest == null) { reset(); return false; }

		// Screen is open — pull pearls ONE at a time, gently, up to REFILL_TARGET.
		if (self.currentScreenHandler instanceof GenericContainerScreenHandler screen) {
			long now = System.currentTimeMillis();
			if (now - lastActionAt < RESTOCK_STEP_DELAY) return true; // pace it for the server

			int chestSlots = screen.getRows() * 9;
			ItemStack cursor = self.currentScreenHandler.getCursorStack();

			// Reached the target: drop any leftover cursor pearls back, then close.
			if (countPearls() >= REFILL_TARGET) {
				if (!cursor.isEmpty() && cursorSource >= 0) {
					client.interactionManager.clickSlot(screen.syncId, cursorSource, 0, SlotActionType.PICKUP, self);
					lastActionAt = now;
					return true;
				}
				feedback.debug("Restock done — now holding " + countPearls() + " pearls");
				self.closeHandledScreen();
				reset();
				return false;
			}

			if (cursor.isEmpty() || !cursor.isOf(Items.ENDER_PEARL)) {
				// Grab a chest pearl stack onto the cursor.
				int src = findChestPearlSlot(screen, chestSlots);
				if (src < 0) {
					feedback.debug("Chest has no more pearls — stopping at " + countPearls());
					self.closeHandledScreen();
					reset();
					return false;
				}
				cursorSource = src;
				client.interactionManager.clickSlot(screen.syncId, src, 0, SlotActionType.PICKUP, self);
			} else {
				// Deposit ONE pearl from the cursor into a bot inventory slot.
				int dst = findDepositSlot(screen, chestSlots);
				if (dst < 0) {
					// Inventory full — give up gracefully.
					if (cursorSource >= 0) {
						client.interactionManager.clickSlot(screen.syncId, cursorSource, 0, SlotActionType.PICKUP, self);
					}
					feedback.debug("Inventory full during restock — stopping at " + countPearls());
					self.closeHandledScreen();
					reset();
					return false;
				}
				client.interactionManager.clickSlot(screen.syncId, dst, 1, SlotActionType.PICKUP, self); // right-click = place one
			}
			lastActionAt = now;
			return true;
		}

		// Haven't sent the interact packet yet.
		if (!interacted) {
			Vec3d centre = Vec3d.ofCenter(chest);
			if (self.getEyePos().distanceTo(centre) > config.reach()) {
				feedback.debug("Restock chest is out of reach — skipping");
				reset();
				return false;
			}
			client.interactionManager.interactBlock(self, Hand.MAIN_HAND,
					new BlockHitResult(centre, Direction.UP, chest, false));
			interacted = true;
			openedAt = System.currentTimeMillis();
			return true;
		}

		// Waiting for the server to send the screen open packet.
		if (System.currentTimeMillis() - openedAt > RESTOCK_SCREEN_TIMEOUT) {
			feedback.debug("Restock chest did not open in time");
			reset();
			return false;
		}
		return true;
	}

	/** Clear all restock state. */
	void reset() {
		chest = null;
		interacted = false;
		lastActionAt = 0L;
		cursorSource = -1;
	}

	/** Total ender pearls in the bot's inventory (main + hotbar). */
	private int countPearls() {
		ClientPlayerEntity self = client.player;
		if (self == null) return 0;
		int total = 0;
		for (int s = 0; s < self.getInventory().size(); s++) {
			ItemStack st = self.getInventory().getStack(s);
			if (st.isOf(Items.ENDER_PEARL)) total += st.getCount();
		}
		return total;
	}

	/** First chest slot (0..chestSlots) holding ender pearls, or -1. */
	private int findChestPearlSlot(GenericContainerScreenHandler screen, int chestSlots) {
		for (int i = 0; i < chestSlots; i++) {
			if (screen.getSlot(i).getStack().isOf(Items.ENDER_PEARL)) return i;
		}
		return -1;
	}

	/** A bot-inventory slot (after the chest section) that can accept a pearl, or -1. */
	private int findDepositSlot(GenericContainerScreenHandler screen, int chestSlots) {
		int total = screen.slots.size();
		for (int i = chestSlots; i < total; i++) {
			ItemStack st = screen.getSlot(i).getStack();
			if (st.isEmpty()) return i;
			if (st.isOf(Items.ENDER_PEARL) && st.getCount() < st.getMaxCount()) return i;
		}
		return -1;
	}

	/** Scan blocks within {@value #RESTOCK_RADIUS} blocks for an accessible chest/barrel. */
	private BlockPos findNearbyChest(ClientPlayerEntity self, ClientWorld world) {
		BlockPos origin = self.getBlockPos();
		for (BlockPos p : BlockPos.iterateOutwards(origin, RESTOCK_RADIUS, RESTOCK_RADIUS, RESTOCK_RADIUS)) {
			var block = world.getBlockState(p).getBlock();
			if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL) {
				return p.toImmutable();
			}
		}
		return null;
	}
}
