package fr.nyuway.stasisbot.scan;

import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.model.StasisChamber;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Caches {@link ChamberScanner} results and only re-scans when the cache has
 * expired (TTL) or the bot moved to a new chunk. This is the throttle that keeps
 * chat activity from ever turning into world-scan spam.
 *
 * <p>It also <em>remembers</em> chambers it has seen recently: when the bot walks
 * away (or a chunk briefly unloads) a stasis would otherwise vanish from a fresh
 * scan, making the bot "forget" someone's base. Remembered chambers are merged
 * back in until they go unseen for {@code rememberMillis}, and they refresh the
 * moment a real scan re-sees them — so a sign that was actually removed still
 * expires on its own.
 *
 * <p>All access happens on the client thread, so no synchronisation is needed.
 */
public final class ChamberIndex {

	private final ChamberScanner scanner;
	private final StasisBotConfig config;

	private List<StasisChamber> cached = List.of();
	private long lastScanAt = 0L;
	private long lastChunkKey = Long.MIN_VALUE;

	/** trigger position → last-seen chamber + timestamp, for the memory union. */
	private final Map<BlockPos, Remembered> remembered = new HashMap<>();

	private record Remembered(StasisChamber chamber, long seenAt) {}

	public ChamberIndex(ChamberScanner scanner, StasisBotConfig config) {
		this.scanner = scanner;
		this.config = config;
	}

	/** Chambers near the origin, scanning at most once per TTL (or on chunk change). */
	public List<StasisChamber> chambers(ClientWorld world, BlockPos origin) {
		long now = System.currentTimeMillis();
		long chunkKey = chunkKey(origin);
		boolean expired = now - lastScanAt >= config.indexTtlMillis();
		if (expired || chunkKey != lastChunkKey) {
			List<StasisChamber> fresh = scanner.scan(world, origin);
			remember(fresh, now);
			cached = mergeWithMemory(world, origin, fresh, now);
			lastScanAt = now;
			lastChunkKey = chunkKey;
		}
		return cached;
	}

	/**
	 * Re-read the world around {@code origin} and confirm a chamber still exists
	 * at {@code trigger} bearing at least one of {@code tokens}. Returns the
	 * up-to-date chamber, or {@code null} if it's gone / no longer matches.
	 */
	public StasisChamber verify(ClientWorld world, BlockPos origin, BlockPos trigger, Set<String> tokens) {
		long now = System.currentTimeMillis();
		List<StasisChamber> fresh = scanner.scan(world, origin);
		remember(fresh, now);
		cached = mergeWithMemory(world, origin, fresh, now);
		lastScanAt = now;
		lastChunkKey = chunkKey(origin);
		for (StasisChamber c : fresh) {
			if (c.trigger().equals(trigger) && c.matchesAny(tokens)) return c;
		}
		return null;
	}

	/** Drop both the cache and the memory so the next access re-scans cleanly. */
	public void invalidate() {
		lastScanAt = 0L;
		lastChunkKey = Long.MIN_VALUE;
		remembered.clear();
	}

	private void remember(List<StasisChamber> fresh, long now) {
		for (StasisChamber c : fresh) {
			remembered.put(c.trigger(), new Remembered(c, now));
		}
	}

	private List<StasisChamber> mergeWithMemory(ClientWorld world, BlockPos origin, List<StasisChamber> fresh, long now) {
		long ttl = config.rememberMillis();
		Set<BlockPos> freshTriggers = new HashSet<>();
		for (StasisChamber c : fresh) freshTriggers.add(c.trigger());
		// Expire by age, but ALSO forget at once any remembered chamber that is currently
		// scannable (its sign chunk is loaded and within range) yet absent from this fresh
		// scan: that means its sign or trigger was just removed, so it's no longer a stasis
		// and must drop out of the list immediately instead of lingering for the TTL.
		remembered.entrySet().removeIf(e -> {
			if (now - e.getValue().seenAt() > ttl) return true;
			StasisChamber c = e.getValue().chamber();
			if (freshTriggers.contains(c.trigger())) return false; // re-seen, keep
			return isScannable(world, origin, c.sign()); // gone while in view → drop now
		});
		Map<BlockPos, StasisChamber> union = new HashMap<>();
		for (Remembered r : remembered.values()) {
			union.put(r.chamber().trigger(), r.chamber());
		}
		for (StasisChamber c : fresh) {
			union.put(c.trigger(), c);
		}
		return new ArrayList<>(union.values());
	}

	/**
	 * Whether {@code sign} sits inside the region a scan actually covers right now:
	 * its chunk is within {@link StasisBotConfig#scanChunkRadius()} of the origin, the
	 * chunk is loaded, and it's within {@link StasisBotConfig#maxChamberDistance()}.
	 * When true but the chamber is missing from a fresh scan, the sign/trap is really
	 * gone (not merely out of range), so the memory can forget it on the spot.
	 */
	private boolean isScannable(ClientWorld world, BlockPos origin, BlockPos sign) {
		if (world == null) return false;
		int r = config.scanChunkRadius();
		int ocx = origin.getX() >> 4, ocz = origin.getZ() >> 4;
		int scx = sign.getX() >> 4, scz = sign.getZ() >> 4;
		if (Math.abs(scx - ocx) > r || Math.abs(scz - ocz) > r) return false;
		long maxSq = (long) config.maxChamberDistance() * config.maxChamberDistance();
		if (sign.getSquaredDistance(origin) > maxSq) return false;
		return world.getChunkManager().getChunk(scx, scz, ChunkStatus.FULL, false) != null;
	}

	private static long chunkKey(BlockPos pos) {
		return (((long) (pos.getX() >> 4)) << 32) ^ ((pos.getZ() >> 4) & 0xffffffffL);
	}
}
