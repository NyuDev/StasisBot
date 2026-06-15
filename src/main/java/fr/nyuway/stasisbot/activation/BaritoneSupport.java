package fr.nyuway.stasisbot.activation;

import fr.nyuway.stasisbot.StasisBot;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Detects whether the Baritone mod is present and, only then, creates a
 * {@link BaritoneNavigator}. Keeping the {@code new BaritoneNavigator(...)} call
 * isolated here means the Baritone-referencing class is never linked when the mod
 * is absent, so StasisBot runs fine on its own with the primitive navigator.
 */
public final class BaritoneSupport {

	private static Boolean available;

	private BaritoneSupport() {
	}

	/** Fabric mod ids various Baritone builds ship under. */
	private static final String[] BARITONE_IDS = { "baritone", "baritone-meteor", "baritone-api", "baritone-standalone" };

	/** True when a Baritone mod is loaded in this runtime (any known fork). */
	public static boolean isAvailable() {
		if (available == null) {
			boolean ok = false;
			try {
				FabricLoader loader = FabricLoader.getInstance();
				for (String id : BARITONE_IDS) {
					if (loader.isModLoaded(id)) { ok = true; break; }
				}
				// Fallback: any mod whose id starts with "baritone" (covers future forks).
				if (!ok) {
					ok = loader.getAllMods().stream()
							.anyMatch(m -> m.getMetadata().getId().toLowerCase().startsWith("baritone"));
				}
			} catch (Throwable t) {
				ok = false;
			}
			available = ok;
		}
		return available;
	}

	/** Create a Baritone-backed navigator, or {@code null} if Baritone isn't usable. */
	public static Navigator create(StasisBotConfig config) {
		if (!isAvailable()) return null;
		try {
			return new BaritoneNavigator(config);
		} catch (Throwable t) {
			StasisBot.LOGGER.error("[StasisBot] Baritone present but failed to initialise", t);
			return null;
		}
	}
}
