package fr.nyuway.stasisbot.activation;

import java.util.Locale;

import fr.nyuway.stasisbot.StasisBot;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Optional, reflection-only bridge to <a href="https://meteorclient.com">Meteor Client</a>.
 *
 * <p>Meteor is <strong>not</strong> a compile-time dependency: every call here is guarded so
 * the mod compiles and runs perfectly fine without Meteor installed (all methods become
 * no-ops). When Meteor <em>is</em> present at runtime, the bridge temporarily switches the
 * "Anti AFK" module off while the bot is performing an operation (walking, firing, returning)
 * and switches it back on once everything is finished — but only if <em>we</em> were the ones
 * who turned it off (a module the user left disabled stays disabled).
 */
public final class MeteorBridge {

	private static Boolean present;       // cached "is Meteor loaded?" lookup
	private static boolean suspendedByUs; // did we disable Anti-AFK ourselves?

	private MeteorBridge() {
	}

	/** True when Meteor Client is loaded in this runtime. */
	public static boolean isPresent() {
		if (present == null) {
			boolean loaded;
			try {
				loaded = FabricLoader.getInstance().isModLoaded("meteor-client");
			} catch (Throwable t) {
				loaded = false;
			}
			present = loaded;
		}
		return present;
	}

	/** Disable Meteor's Anti-AFK if it is currently active, remembering to restore it later. */
	public static void suspendAntiAfk() {
		if (suspendedByUs || !isPresent()) return;
		try {
			Object module = antiAfkModule();
			if (module != null && isActive(module)) {
				toggle(module); // active -> inactive
				suspendedByUs = true;
				StasisBot.LOGGER.info("[StasisBot] Disabled Meteor Anti-AFK for the operation");
			}
		} catch (Throwable t) {
			StasisBot.LOGGER.warn("[StasisBot] Could not suspend Meteor Anti-AFK", t);
		}
	}

	/** Re-enable Anti-AFK, but only if we were the ones who turned it off. */
	public static void restoreAntiAfk() {
		if (!suspendedByUs) return;
		suspendedByUs = false;
		if (!isPresent()) return;
		try {
			Object module = antiAfkModule();
			if (module != null && !isActive(module)) {
				toggle(module); // inactive -> active
				StasisBot.LOGGER.info("[StasisBot] Re-enabled Meteor Anti-AFK after the operation");
			}
		} catch (Throwable t) {
			StasisBot.LOGGER.warn("[StasisBot] Could not restore Meteor Anti-AFK", t);
		}
	}

	// --- reflection helpers --------------------------------------------------

	/** Locate the "Anti AFK" module instance, or null if Meteor/the module isn't available. */
	private static Object antiAfkModule() throws Exception {
		Class<?> modulesCls = Class.forName("meteordevelopment.meteorclient.systems.modules.Modules");
		Object modules = modulesCls.getMethod("get").invoke(null);
		Object all = modulesCls.getMethod("getAll").invoke(modules); // List<Module>
		if (!(all instanceof Iterable<?> iterable)) return null;
		for (Object m : iterable) {
			if (matchesAntiAfk(stringField(m, "title")) || matchesAntiAfk(stringField(m, "name"))) {
				return m;
			}
		}
		return null;
	}

	private static boolean isActive(Object module) throws Exception {
		Object active = module.getClass().getMethod("isActive").invoke(module);
		return Boolean.TRUE.equals(active);
	}

	private static void toggle(Object module) throws Exception {
		module.getClass().getMethod("toggle").invoke(module);
	}

	private static String stringField(Object module, String field) {
		try {
			Object value = module.getClass().getField(field).get(module);
			return value == null ? null : value.toString();
		} catch (Throwable t) {
			return null;
		}
	}

	/** Matches "Anti AFK", "anti-afk", "AntiAFK", etc. regardless of spacing/casing. */
	private static boolean matchesAntiAfk(String s) {
		if (s == null) return false;
		String norm = s.toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "").replace("_", "");
		return norm.equals("antiafk");
	}
}
