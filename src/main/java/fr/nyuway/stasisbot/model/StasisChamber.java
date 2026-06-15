package fr.nyuway.stasisbot.model;

import net.minecraft.util.math.BlockPos;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable snapshot of a stasis chamber found in the world: the sign that
 * labels it, the lever/button that releases its pearl, and the lower-cased words
 * on the sign (pre-computed once for fast matching).
 */
public final class StasisChamber {

	private final BlockPos sign;
	private final BlockPos trigger;
	private final List<String> signLines;
	private final Set<String> signTokens;

	public StasisChamber(BlockPos sign, BlockPos trigger, List<String> signLines) {
		this.sign = sign.toImmutable();
		this.trigger = trigger.toImmutable();
		this.signLines = List.copyOf(signLines);
		this.signTokens = Collections.unmodifiableSet(tokenise(signLines));
	}

	public BlockPos sign() { return sign; }
	public BlockPos trigger() { return trigger; }
	public List<String> signLines() { return signLines; }
	public Set<String> signTokens() { return signTokens; }

	public String label() {
		return signLines.isEmpty() ? "(blank)" : String.join(" ", signLines);
	}

	/** True if any of the given lower-cased tokens is a word on this sign. */
	public boolean matchesAny(Set<String> lowerTokens) {
		if (lowerTokens.isEmpty() || signTokens.isEmpty()) return false;
		// Iterate the smaller set for speed; both lookups are O(1).
		if (lowerTokens.size() < signTokens.size()) {
			for (String token : lowerTokens) {
				if (signTokens.contains(token)) return true;
			}
			return false;
		}
		for (String token : signTokens) {
			if (lowerTokens.contains(token)) return true;
		}
		return false;
	}

	/** Split sign text into lower-cased alphanumeric words. */
	public static Set<String> tokenise(List<String> lines) {
		Set<String> tokens = new HashSet<>();
		for (String line : lines) {
			for (String word : line.toLowerCase().split("[^a-z0-9_]+")) {
				if (!word.isBlank()) tokens.add(word);
			}
		}
		return tokens;
	}

	@Override
	public String toString() {
		return "StasisChamber{label='" + label() + "', trigger=" + trigger.toShortString() + "}";
	}
}
