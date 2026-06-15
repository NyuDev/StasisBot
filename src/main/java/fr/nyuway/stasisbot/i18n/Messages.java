package fr.nyuway.stasisbot.i18n;

import java.util.Map;

/**
 * Tiny localisation table for the short chat messages the bot sends. English is
 * the default; French is selectable via the {@code language} config (or the
 * master command {@code lang fr}). Keys are stable; values are kept terse on
 * purpose — players only need a quick, clear line.
 */
public final class Messages {

	public enum Key {
		QUEUED, ON_MY_WAY, RELEASING, EMPTY, TOO_FAR, PATH_FAIL, PEARL_GONE,
		DONE, NOT_CONFIRMED, RETRYING,
		TP_FAILED_LAG, TP_FAILED_OFFLINE,
		CFG_SET, CFG_UNKNOWN, CFG_BADVALUE, CFG_HELP, CFG_DENIED
	}

	private static final Map<Key, String> EN = Map.ofEntries(
			Map.entry(Key.QUEUED, "Queued — %d ahead."),
			Map.entry(Key.ON_MY_WAY, "On my way — pearl in ~%ds."),
			Map.entry(Key.RELEASING, "Releasing your pearl now."),
			Map.entry(Key.EMPTY, "Your stasis is empty — restock it."),
			Map.entry(Key.TOO_FAR, "Your stasis is too far (auto-walk off)."),
			Map.entry(Key.PATH_FAIL, "Couldn't reach your stasis."),
			Map.entry(Key.PEARL_GONE, "Pearl vanished — try again."),
			Map.entry(Key.DONE, "Done — you've been pulled."),
			Map.entry(Key.NOT_CONFIRMED, "TP fired but you didn't appear — try again."),
			Map.entry(Key.RETRYING, "TP not confirmed — trying another stasis."),
			Map.entry(Key.TP_FAILED_LAG, "TP cancelled — server was lagging. Try again when stable."),
			Map.entry(Key.TP_FAILED_OFFLINE, "TP skipped — you were offline or left mid-walk."),
			Map.entry(Key.CFG_SET, "Set %s = %s"),
			Map.entry(Key.CFG_UNKNOWN, "Unknown option: %s"),
			Map.entry(Key.CFG_BADVALUE, "Bad value for %s"),
			Map.entry(Key.CFG_HELP, "Options: lang, drop, reopen, return, walk, online, dm, trigger, whisper, master, returnpos, help"),
			Map.entry(Key.CFG_DENIED, "Only my master can configure me.")
	);

	private static final Map<Key, String> FR = Map.ofEntries(
			Map.entry(Key.QUEUED, "File d'attente — %d devant toi."),
			Map.entry(Key.ON_MY_WAY, "J'arrive — perle dans ~%ds."),
			Map.entry(Key.RELEASING, "Je libere ta perle."),
			Map.entry(Key.EMPTY, "Ta stasis est vide — recharge-la."),
			Map.entry(Key.TOO_FAR, "Ta stasis est trop loin (auto-walk off)."),
			Map.entry(Key.PATH_FAIL, "Je n'ai pas pu atteindre ta stasis."),
			Map.entry(Key.PEARL_GONE, "Perle disparue — reessaie."),
			Map.entry(Key.DONE, "C'est fait — tu es teleporte."),
			Map.entry(Key.NOT_CONFIRMED, "La TP a ete lancee mais tu n'es pas apparu — reessaie."),
			Map.entry(Key.RETRYING, "TP non confirmee — j'essaie une autre stasis."),
			Map.entry(Key.TP_FAILED_LAG, "TP annulee — le serveur lag. Reessaie quand c'est stable."),
			Map.entry(Key.TP_FAILED_OFFLINE, "TP annulee — tu etais hors ligne ou tu es parti en route."),
			Map.entry(Key.CFG_SET, "OK %s = %s"),
			Map.entry(Key.CFG_UNKNOWN, "Option inconnue : %s"),
			Map.entry(Key.CFG_BADVALUE, "Valeur invalide pour %s"),
			Map.entry(Key.CFG_HELP, "Options : lang, drop, reopen, return, walk, online, dm, trigger, whisper, master, returnpos, help"),
			Map.entry(Key.CFG_DENIED, "Seul mon maitre peut me configurer.")
	);

	private Messages() {
	}

	/** Localised, formatted message. {@code lang} is "fr" for French, anything else = English. */
	public static String get(String lang, Key key, Object... args) {
		Map<Key, String> table = "fr".equalsIgnoreCase(lang) ? FR : EN;
		String pattern = table.getOrDefault(key, EN.get(key));
		return args.length == 0 ? pattern : String.format(pattern, args);
	}
}
