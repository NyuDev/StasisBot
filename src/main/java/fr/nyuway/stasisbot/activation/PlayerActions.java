package fr.nyuway.stasisbot.activation;

import fr.nyuway.stasisbot.StasisBot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Locale;

/**
 * Small inventory helpers the bot uses around a pull: handing a fresh ender
 * pearl back to the player so they can re-arm their stasis. All client-thread.
 */
public final class PlayerActions {

	private PlayerActions() {
	}

	/** True if the bot is carrying at least one ender pearl in its inventory. */
	public static boolean hasPearl(MinecraftClient client) {
		return findPearlSlot(client) >= 0;
	}

	/** Total number of ender pearls in the bot's inventory (slots 0-35). */
	public static int countPearls(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null) return 0;
		PlayerInventory inv = player.getInventory();
		int total = 0;
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = inv.getStack(slot);
			if (!stack.isEmpty() && stack.isOf(Items.ENDER_PEARL)) total += stack.getCount();
		}
		return total;
	}

	/**
	 * Drop a single ender pearl on the ground next to the bot so the arriving
	 * player can grab it. No-op (returns false) when no pearl is in the inventory.
	 *
	 * <p>Drops it with the <strong>vanilla drop action</strong> (the same thing
	 * that happens when a player taps the drop key): the pearl's slot is briefly
	 * selected, exactly one pearl is dropped, then the previous selection is
	 * restored — so the bot is never left visibly holding pearls. This is
	 * deliberately <em>not</em> a {@code THROW} slot-click: in creative mode a
	 * THROW slot-click spawns a duplicate client-side "ghost" pearl, which looked
	 * like the bot dropping two pearls. The vanilla drop action releases a single
	 * real pearl from the inventory in both survival and creative.
	 */
	public static boolean dropPearl(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || client.interactionManager == null || player.networkHandler == null) return false;
		int slot = findPearlSlot(client);
		if (slot < 0) return false;

		PlayerInventory inv = player.getInventory();
		int before = countPearls(client);
		int prevSelected = inv.getSelectedSlot();
		int syncId = player.playerScreenHandler.syncId;
		boolean swapped = false;
		int hotbarSlot;
		try {
			if (slot < 9) {
				hotbarSlot = slot; // pearl already sits in the hotbar
			} else {
				// Bring the pearl into a hotbar slot (not the held one) so the vanilla
				// drop action can release it, then swap it straight back afterwards.
				hotbarSlot = (prevSelected == 0) ? 8 : 0;
				// Main-inventory slots (9-35) map 1:1 in the player screen handler.
				client.interactionManager.clickSlot(syncId, slot, hotbarSlot, SlotActionType.SWAP, player);
				swapped = true;
			}
			// Select the pearl, drop exactly one (Q-key mechanic), restore selection.
			inv.setSelectedSlot(hotbarSlot);
			player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(hotbarSlot));
			boolean dropped = player.dropSelectedItem(false);
			if (swapped) {
				// Put the leftover pearls back where they were: keep the hotbar unchanged.
				client.interactionManager.clickSlot(syncId, slot, hotbarSlot, SlotActionType.SWAP, player);
			}
			int after = countPearls(client);
			StasisBot.LOGGER.info("dropPearl: slot={} hotbar={} pearls {} -> {} (delta {})",
					slot, hotbarSlot, before, after, after - before);
			return dropped;
		} catch (Throwable t) {
			StasisBot.LOGGER.warn("Could not drop a pearl for the player", t);
			return false;
		} finally {
			inv.setSelectedSlot(prevSelected);
			player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(prevSelected));
		}
	}

	/** Index of the first inventory slot (0-35) holding an ender pearl, or -1. */
	private static int findPearlSlot(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null) return -1;
		PlayerInventory inv = player.getInventory();
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = inv.getStack(slot);
			if (!stack.isEmpty() && stack.isOf(Items.ENDER_PEARL)) return slot;
		}
		return -1;
	}

	/**
	 * Turn the bot to face the named player (and tilt slightly down), so a pearl
	 * dropped right after lands toward their feet. No-op if the player isn't loaded.
	 */
	public static void faceToward(MinecraftClient client, String name) {
		ClientPlayerEntity self = client.player;
		if (self == null || client.world == null || name == null) return;
		String key = name.toLowerCase(Locale.ROOT);
		for (var p : client.world.getPlayers()) {
			if (!p.getGameProfile().name().toLowerCase(Locale.ROOT).equals(key)) continue;
			double dx = p.getX() - self.getX();
			double dz = p.getZ() - self.getZ();
			float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
			self.setYaw(yaw);
			self.setPitch(20.0f); // look slightly down so the pearl drops near their feet
			return;
		}
	}
}
