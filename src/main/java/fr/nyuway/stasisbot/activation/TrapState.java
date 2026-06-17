package fr.nyuway.stasisbot.activation;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;

/**
 * Reads the open/closed state of a trapdoor trigger. Used to tell whether a
 * player re-armed their own stasis trap (so the bot can leave it alone).
 */
public final class TrapState {

	private TrapState() {
	}

	/**
	 * @return {@code TRUE}/{@code FALSE} for a trapdoor's "open" state, or
	 *         {@code null} when the block isn't a trapdoor (lever/button/door)
	 *         or its state can't be read.
	 */
	public static Boolean isOpen(ClientWorld world, BlockPos pos) {
		if (world == null) return null;
		var state = world.getBlockState(pos);
		if (!state.isIn(BlockTags.WOODEN_TRAPDOORS) && !state.isIn(BlockTags.TRAPDOORS)) {
			return null;
		}
		try {
			for (var entry : state.getEntries().entrySet()) {
				if (entry.getKey().getName().equals("open")) {
					return Boolean.TRUE.equals(entry.getValue());
				}
			}
		} catch (Throwable ignored) {
			// can't read state
		}
		return null;
	}

	/**
	 * Generalised "is this trigger currently released/active?" reader that works for
	 * every trigger kind, not just trapdoors: trapdoors and doors expose an
	 * {@code open} property, while levers and buttons expose {@code powered}.
	 *
	 * @return {@code TRUE} when open/powered, {@code FALSE} when closed/unpowered,
	 *         or {@code null} when the block has neither property (state unreadable).
	 */
	public static Boolean isTriggerActive(ClientWorld world, BlockPos pos) {
		if (world == null) return null;
		var state = world.getBlockState(pos);
		try {
			Boolean powered = null;
			for (var entry : state.getEntries().entrySet()) {
				String name = entry.getKey().getName();
				if (name.equals("open")) return Boolean.TRUE.equals(entry.getValue());
				if (name.equals("powered")) powered = Boolean.TRUE.equals(entry.getValue());
			}
			return powered; // null when the trigger exposes neither "open" nor "powered"
		} catch (Throwable ignored) {
			return null;
		}
	}
}
