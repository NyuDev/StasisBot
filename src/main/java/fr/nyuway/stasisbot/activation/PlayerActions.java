package fr.nyuway.stasisbot.activation;

import fr.nyuway.stasisbot.StasisBot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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

	/**
	 * Drop a single ender pearl on the ground next to the bot so the arriving
	 * player can grab it. No-op (returns false) when no pearl is in the inventory.
	 *
	 * <p>Drops the pearl <strong>straight from its inventory slot via a THROW
	 * click</strong>, so the pearl is <em>never</em> moved into the bot's hand —
	 * the held hotbar slot is left completely untouched and stays empty of pearls.
	 * (The old SWAP-then-drop path left the remainder of a pearl stack in the held
	 * slot, making the bot visibly hold pearls — which must never happen.)
	 */
	public static boolean dropPearl(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || client.interactionManager == null) return false;
		int slot = findPearlSlot(client);
		if (slot < 0) return false;

		try {
			// Screen-handler slot map: hotbar n -> 36+n, main inv n (9-35) -> n.
			int screenSlot = slot < 9 ? 36 + slot : slot;
			// THROW with button 0 drops exactly one item from that slot, with an empty
			// cursor — the item is dropped in place, never picked up into the hand.
			client.interactionManager.clickSlot(
					player.playerScreenHandler.syncId, screenSlot, 0, SlotActionType.THROW, player);
			return true;
		} catch (Throwable t) {
			StasisBot.LOGGER.warn("Could not drop a pearl for the player", t);
			return false;
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
