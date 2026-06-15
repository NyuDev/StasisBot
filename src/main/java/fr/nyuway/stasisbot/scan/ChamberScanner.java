package fr.nyuway.stasisbot.scan;

import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.model.StasisChamber;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Discovers stasis chambers near an origin by walking only the <em>already
 * loaded</em> block entities of nearby chunks — never a brute-force block sweep —
 * then pairing each labelled sign with the closest lever/button.
 *
 * <p>Stateless: a {@link ChamberIndex} decides when this runs so it can't be
 * called more than necessary.
 */
public final class ChamberScanner {

	private final StasisBotConfig config;

	public ChamberScanner(StasisBotConfig config) {
		this.config = config;
	}

	public List<StasisChamber> scan(ClientWorld world, BlockPos origin) {
		List<StasisChamber> chambers = new ArrayList<>();
		int originChunkX = origin.getX() >> 4;
		int originChunkZ = origin.getZ() >> 4;
		int chunkR = config.scanChunkRadius();
		long maxDistSq = (long) config.maxChamberDistance() * config.maxChamberDistance();

		for (int cx = originChunkX - chunkR; cx <= originChunkX + chunkR; cx++) {
			for (int cz = originChunkZ - chunkR; cz <= originChunkZ + chunkR; cz++) {
				WorldChunk chunk = world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) continue;

				for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
					if (!(entry.getValue() instanceof SignBlockEntity sign)) continue;

					BlockPos signPos = entry.getKey();
					if (signPos.getSquaredDistance(origin) > maxDistSq) continue;

					List<String> lines = readSign(sign);
					if (lines.isEmpty()) continue; // a blank sign can't be matched to anyone

					BlockPos trigger = findTrigger(world, signPos);
					if (trigger == null) continue; // a sign with no lever/button isn't a chamber

					chambers.add(new StasisChamber(signPos, trigger, lines));
				}
			}
		}
		return chambers;
	}

	private BlockPos findTrigger(ClientWorld world, BlockPos signPos) {
		int r = config.triggerSearchRadius();
		BlockPos best = null;
		double bestDistSq = Double.MAX_VALUE;
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					cursor.set(signPos.getX() + dx, signPos.getY() + dy, signPos.getZ() + dz);
					BlockState state = world.getBlockState(cursor);
					if (!isTrigger(state)) continue;
					double distSq = cursor.getSquaredDistance(signPos);
					if (distSq < bestDistSq) {
						bestDistSq = distSq;
						best = cursor.toImmutable();
					}
				}
			}
		}
		return best;
	}

	/**
	 * A "trigger" is any block a player can right-click to release the pearl:
	 * trapdoors (the classic stasis design), doors, levers, or buttons.
	 */
	private static boolean isTrigger(BlockState state) {
		return state.isIn(BlockTags.WOODEN_TRAPDOORS)
				|| state.isIn(BlockTags.WOODEN_DOORS)
				|| state.isOf(Blocks.LEVER)
				|| state.isIn(BlockTags.BUTTONS);
	}

	private static List<String> readSign(SignBlockEntity sign) {
		List<String> lines = new ArrayList<>(8);
		collect(sign.getFrontText(), lines);
		collect(sign.getBackText(), lines);
		return lines;
	}

	private static void collect(SignText text, List<String> out) {
		for (Text line : text.getMessages(false)) {
			String s = line.getString().trim();
			if (!s.isEmpty()) out.add(s);
		}
	}
}
