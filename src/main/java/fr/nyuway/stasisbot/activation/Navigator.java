package fr.nyuway.stasisbot.activation;

import fr.nyuway.stasisbot.model.StasisChamber;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/**
 * Abstraction over "walk the bot to a target block". Implemented by
 * {@link BaritoneNavigator} (smart pathfinding, used when Baritone is installed)
 * and {@link PrimitiveNavigator} (a built-in walk-forward fallback that needs no
 * external mod). {@link Navigation} picks between them at runtime.
 *
 * <p>All methods run on the client thread, driven one step per tick by
 * {@link fr.nyuway.stasisbot.service.HomeService}.
 */
public interface Navigator {

	enum Status { MOVING, ARRIVED, FAILED }

	/** Begin pathing toward the chamber's trigger. */
	void start(StasisChamber chamber);

	/** Begin pathing toward an arbitrary block position. */
	void start(BlockPos target);

	/** Begin pathing to the exact block (used to land precisely on the return point). */
	void startExact(BlockPos target);

	/** Advance one tick toward a chamber; arrives within interaction reach. */
	Status tick(MinecraftClient client, StasisChamber chamber);

	/** Advance one tick toward a block; arrives within {@code arriveDist} blocks. */
	Status tick(MinecraftClient client, BlockPos target, double arriveDist);

	/** Cancel any active movement and release inputs. Safe to call repeatedly. */
	void stop(MinecraftClient client);

	/** True while a path/movement is in progress. */
	boolean isActive();
}
