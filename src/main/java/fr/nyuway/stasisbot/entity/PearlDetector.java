package fr.nyuway.stasisbot.entity;

import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.model.StasisChamber;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Tells whether a chamber currently holds a suspended ender pearl, using an
 * indexed AABB entity query rather than iterating every entity in the world.
 *
 * <p>When chambers sit right next to each other (several trapdoors in a row),
 * a single radius query around one trigger would also see the neighbour's
 * pearl. To avoid firing an empty chamber, a pearl only counts for a chamber
 * when <em>this</em> chamber's trigger is the closest trigger to that pearl.
 */
public final class PearlDetector {

	private final StasisBotConfig config;

	public PearlDetector(StasisBotConfig config) {
		this.config = config;
	}

	/**
	 * True if a pearl is suspended in {@code chamber} and that pearl belongs to
	 * this chamber (its trigger is the nearest one among {@code allChambers}).
	 */
	public boolean hasOwnPearl(ClientWorld world, StasisChamber chamber, List<StasisChamber> allChambers) {
		double r = config.pearlSearchRadius();
		Vec3d centre = Vec3d.ofCenter(chamber.trigger());
		Box box = Box.of(centre, r * 2, r * 2, r * 2);
		double rSq = r * r;
		var pearls = world.getEntitiesByType(EntityType.ENDER_PEARL, box,
				pearl -> pearl.squaredDistanceTo(centre) <= rSq);
		for (var pearl : pearls) {
			Vec3d pearlPos = new Vec3d(pearl.getX(), pearl.getY(), pearl.getZ());
			if (ownsPearl(chamber, allChambers, pearlPos)) return true;
		}
		return false;
	}

	/**
	 * The name of the player who owns the pearl currently suspended in {@code chamber}
	 * (i.e. the one who threw it), or {@code null} when there is no pearl here or its
	 * owner can't be resolved (thrower not loaded). Used to spot a pearl placed in the
	 * wrong chamber by comparing this name against the chamber's sign.
	 */
	public String ownPearlThrower(ClientWorld world, StasisChamber chamber, List<StasisChamber> allChambers) {
		double r = config.pearlSearchRadius();
		Vec3d centre = Vec3d.ofCenter(chamber.trigger());
		Box box = Box.of(centre, r * 2, r * 2, r * 2);
		double rSq = r * r;
		var pearls = world.getEntitiesByType(EntityType.ENDER_PEARL, box,
				pearl -> pearl.squaredDistanceTo(centre) <= rSq);
		for (var pearl : pearls) {
			Vec3d pearlPos = new Vec3d(pearl.getX(), pearl.getY(), pearl.getZ());
			if (!ownsPearl(chamber, allChambers, pearlPos)) continue;
			Entity owner = pearl.getOwner();
			if (owner instanceof PlayerEntity p) {
				return p.getGameProfile().name();
			}
			return null; // pearl is here but we can't tell whose it is
		}
		return null;
	}

	/** A pearl belongs to whichever chamber's trigger is physically closest to it. */
	private static boolean ownsPearl(StasisChamber chamber, List<StasisChamber> allChambers, Vec3d pearlPos) {
		StasisChamber nearest = null;
		double best = Double.MAX_VALUE;
		for (StasisChamber c : allChambers) {
			double d = Vec3d.ofCenter(c.trigger()).squaredDistanceTo(pearlPos);
			if (d < best) {
				best = d;
				nearest = c;
			}
		}
		return nearest != null && nearest.trigger().equals(chamber.trigger());
	}
}
