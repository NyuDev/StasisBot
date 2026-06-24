package fr.nyuway.stasisbot.service;

/**
 * Localised one-line messages for Discord notifications (English default, French
 * when {@code lang} is "fr"). Kept separate from the in-game {@link
 * fr.nyuway.stasisbot.i18n.Messages} table because these read like log lines and
 * use Discord markdown/emoji, not terse player whispers.
 */
final class DiscordText {

	private DiscordText() {
	}

	private static boolean fr(String lang) {
		return "fr".equalsIgnoreCase(lang);
	}

	static String enter(String lang, String name) {
		return fr(lang)
				? "\uD83D\uDFE2 **" + name + "** est entr\u00e9 dans la render distance du bot."
				: "\uD83D\uDFE2 **" + name + "** entered the bot's render distance.";
	}

	/** Same as {@link #enter} but with a gear summary appended on a second line. */
	static String enterGear(String lang, String name, String gear) {
		return withGear(lang, enter(lang, name), gear);
	}

	static String leave(String lang, String name) {
		return fr(lang)
				? "\uD83D\uDD34 **" + name + "** a quitt\u00e9 la render distance du bot."
				: "\uD83D\uDD34 **" + name + "** left the bot's render distance.";
	}

	/** Same as {@link #leave} but with a gear summary appended on a second line. */
	static String leaveGear(String lang, String name, String gear) {
		return withGear(lang, leave(lang, name), gear);
	}

	static String connected(String lang, String name) {
		return fr(lang)
				? "\uD83D\uDFE2 **" + name + "** s'est connect\u00e9 (dans la render du bot)."
				: "\uD83D\uDFE2 **" + name + "** connected (within the bot's render).";
	}

	/** Connect line with a gear summary appended. */
	static String connectedGear(String lang, String name, String gear) {
		return withGear(lang, connected(lang, name), gear);
	}

	static String disconnected(String lang, String name) {
		return fr(lang)
				? "\u26AB **" + name + "** s'est d\u00e9connect\u00e9 (dans la render du bot)."
				: "\u26AB **" + name + "** disconnected (within the bot's render).";
	}

	/** Disconnect line with a gear summary appended. */
	static String disconnectedGear(String lang, String name, String gear) {
		return withGear(lang, disconnected(lang, name), gear);
	}

	/** Append a "Gear: …" line to a head message, or return it unchanged when no gear. */
	private static String withGear(String lang, String head, String gear) {
		if (gear == null || gear.isBlank()) return head;
		return head + (fr(lang) ? "\n\u2694 Stuff : " : "\n\u2694 Gear: ") + gear;
	}

	static String homeRequested(String lang, String name, String message, boolean dm) {
		String how = dm ? "DM" : (fr(lang) ? "chat public" : "public chat");
		String head = fr(lang)
				? "\uD83C\uDFE0 **" + name + "** a demand\u00e9 une t\u00e9l\u00e9portation (via " + how + ")."
				: "\uD83C\uDFE0 **" + name + "** requested a teleport home (via " + how + ").";
		return appendQuote(message, head);
	}

	/** Append a quoted "\uD83D\uDCAC \u2026" line carrying the original message, if any. */
	private static String appendQuote(String message, String head) {
		if (message == null || message.isBlank()) return head;
		String m = message.strip();
		if (m.length() > 200) m = m.substring(0, 200) + "\u2026";
		return head + "\n\uD83D\uDCAC " + m;
	}

	static String watchedChat(String name, String body, boolean dm) {
		String b = body == null ? "" : body.strip();
		if (b.length() > 350) b = b.substring(0, 350) + "\u2026";
		return "\uD83D\uDC41\uFE0F **" + name + "**" + (dm ? " (DM)" : "") + ": " + b;
	}

	static String watchedJoin(String lang, String name) {
		return fr(lang)
				? "\uD83D\uDC41\uFE0F\uD83D\uDFE2 **" + name + "** (surveill\u00e9) s'est connect\u00e9 au serveur."
				: "\uD83D\uDC41\uFE0F\uD83D\uDFE2 **" + name + "** (watched) joined the server.";
	}

	static String watchedLeave(String lang, String name) {
		return fr(lang)
				? "\uD83D\uDC41\uFE0F\u26AB **" + name + "** (surveill\u00e9) s'est d\u00e9connect\u00e9 du serveur."
				: "\uD83D\uDC41\uFE0F\u26AB **" + name + "** (watched) left the server.";
	}

	/** One line for the general chat relay: {@code <Name> body} (or a DM marker). */
	static String chatLine(String name, String body, boolean dm) {
		String b = body == null ? "" : body.strip();
		return dm ? (name + " \u2192 DM: " + b) : ("<" + name + "> " + b);
	}

	static String fired(String lang, String name, String label) {
		return fr(lang)
				? "\u26A1 Stasis **" + label + "** activ\u00e9e pour **" + name + "**."
				: "\u26A1 Fired stasis **" + label + "** for **" + name + "**.";
	}

	static String confirmed(String lang, String name) {
		return fr(lang)
				? "\u2705 **" + name + "** a \u00e9t\u00e9 t\u00e9l\u00e9port\u00e9."
				: "\u2705 **" + name + "** was pulled in.";
	}

	static String failed(String lang, String name, String reason) {
		return fr(lang)
				? "\u26A0 TP \u00e9chou\u00e9e pour **" + name + "** (" + reason + ")."
				: "\u26A0 Teleport failed for **" + name + "** (" + reason + ").";
	}

	static String empty(String lang, String name) {
		return fr(lang)
				? "\uD83E\uDEB9 **" + name + "** a demand\u00e9 mais sa stasis est vide."
				: "\uD83E\uDEB9 **" + name + "** asked but their stasis is empty.";
	}

	static String enRoute(String lang, String name, String label, int etaSeconds) {
		return fr(lang)
				? "\uD83D\uDEB6 En route vers la stasis **" + label + "** pour **" + name + "** (~" + etaSeconds + "s)."
				: "\uD83D\uDEB6 On the way to stasis **" + label + "** for **" + name + "** (~" + etaSeconds + "s).";
	}

	static String pearlDropped(String lang, String name) {
		return fr(lang)
				? "\uD83E\uDEF3 Pearl d\u00e9pos\u00e9e pour **" + name + "**."
				: "\uD83E\uDEF3 Dropped a pearl for **" + name + "**.";
	}

	/** Pearl-dropped line with the bot's remaining pearl count appended. */
	static String pearlDropped(String lang, String name, int left) {
		return fr(lang)
				? "\uD83E\uDEF3 Pearl d\u00e9pos\u00e9e pour **" + name + "** (" + left + " en stock)."
				: "\uD83E\uDEF3 Dropped a pearl for **" + name + "** (" + left + " left).";
	}

	static String pickedUp(String lang, String name) {
		return fr(lang)
				? "\uD83E\uDD32 **" + name + "** a r\u00e9cup\u00e9r\u00e9 sa pearl."
				: "\uD83E\uDD32 **" + name + "** picked up their pearl.";
	}

	static String reopened(String lang, String name, String label) {
		return fr(lang)
				? "\uD83D\uDD01 Stasis **" + label + "** rouverte pour **" + name + "**."
				: "\uD83D\uDD01 Reopened stasis **" + label + "** for **" + name + "**.";
	}

	static String outOfPearls(String lang, String name) {
		return fr(lang)
				? "\u2757 Plus de pearls \u2014 impossible d'en donner une \u00e0 **" + name + "** !"
				: "\u2757 Out of ender pearls \u2014 couldn't give one to **" + name + "**!";
	}

	static String recharged(String lang, String label, String by) {
		if (fr(lang)) {
			return by == null
					? "\uD83D\uDD0B Stasis **" + label + "** recharg\u00e9e."
					: "\uD83D\uDD0B Stasis **" + label + "** recharg\u00e9e par **" + by + "**.";
		}
		return by == null
				? "\uD83D\uDD0B Stasis **" + label + "** was recharged."
				: "\uD83D\uDD0B Stasis **" + label + "** was recharged by **" + by + "**.";
	}

	static String stasisOpened(String lang, String label, String who,
	                            boolean hadPearl, boolean tpConfirmed) {
		String whoStr = who != null
				? "**" + who + "**"
				: (fr(lang) ? "quelqu'un" : "someone");
		String pearlInfo;
		if (!hadPearl) {
			pearlInfo = fr(lang) ? " (vide)" : " (empty)";
		} else if (tpConfirmed) {
			pearlInfo = fr(lang) ? " \u2192 pearl consomm\u00e9e \u2014 **TP confirm\u00e9**" : " \u2192 pearl consumed \u2014 **TP confirmed**";
		} else {
			pearlInfo = fr(lang) ? " (pearl toujours pr\u00e9sente \u2014 pas de TP)" : " (pearl still inside \u2014 no TP)";
		}
		return fr(lang)
				? "\uD83D\uDD13 Stasis **" + label + "** ouverte par " + whoStr + pearlInfo
				: "\uD83D\uDD13 Stasis **" + label + "** opened by " + whoStr + pearlInfo;
	}

	static String stasisClosed(String lang, String label, String who) {
		String whoStr = who != null
				? "**" + who + "**"
				: (fr(lang) ? "quelqu'un" : "someone");
		return fr(lang)
				? "\uD83D\uDD12 Stasis **" + label + "** referm\u00e9e par " + whoStr
				: "\uD83D\uDD12 Stasis **" + label + "** closed by " + whoStr;
	}

	/**
	 * A stasis released its pearl: who set it off ({@code triggeredBy}, may be null) and
	 * whether anyone teleported in ({@code tp} with {@code tpTarget}) or the pearl broke.
	 */
	static String stasisReleased(String lang, String label, String triggeredBy, String tpTarget, boolean tp) {
		if (fr(lang)) {
			String by = triggeredBy != null ? " par **" + triggeredBy + "**" : "";
			String res = tp
					? " \u2192 **" + (tpTarget != null ? tpTarget : "quelqu'un") + "** a t\u00e9l\u00e9port\u00e9"
					: " \u2192 pearl cass\u00e9e (personne n'a t\u00e9l\u00e9port\u00e9)";
			return "\uD83D\uDD13 Stasis **" + label + "** d\u00e9clench\u00e9e" + by + res;
		}
		String by = triggeredBy != null ? " by **" + triggeredBy + "**" : "";
		String res = tp
				? " \u2192 **" + (tpTarget != null ? tpTarget : "someone") + "** teleported in"
				: " \u2192 pearl broke (nobody teleported)";
		return "\uD83D\uDD13 Stasis **" + label + "** triggered" + by + res;
	}

	static String died(String lang) {
		return fr(lang) ? "\uD83D\uDC80 Le bot est mort." : "\uD83D\uDC80 The bot died.";
	}

	/** Bot-died line with the cause taken from the server's death message. */
	static String died(String lang, String reason) {
		if (reason == null || reason.isBlank()) return died(lang);
		return fr(lang)
				? "\uD83D\uDC80 Le bot est mort : " + reason
				: "\uD83D\uDC80 The bot died: " + reason;
	}

	static String respawned(String lang) {
		return fr(lang)
				? "\u267B Le bot a r\u00e9apparu et rentre \u00e0 la base."
				: "\u267B The bot respawned and is heading home.";
	}

	static String restocked(String lang) {
		return fr(lang)
				? "\uD83D\uDCE6 Le bot a refait le plein de pearls."
				: "\uD83D\uDCE6 The bot restocked ender pearls.";
	}

	/** Restock line with numbers: how many were taken, the new total held, and what's left in the chest. */
	static String restocked(String lang, int took, int held, int chestLeft) {
		return fr(lang)
				? "\uD83D\uDCE6 Restock : +" + took + " pearls (total **" + held + "** sur le bot, " + chestLeft + " restantes dans le coffre)."
				: "\uD83D\uDCE6 Restocked: +" + took + " pearls (now **" + held + "** on the bot, " + chestLeft + " left in the chest).";
	}

	static String playerDied(String lang, String victim, String killer) {
		if (fr(lang)) {
			return killer == null
					? "\u2620\uFE0F **" + victim + "** est mort \u00e0 port\u00e9e de vue du bot."
					: "\u2620\uFE0F **" + victim + "** a \u00e9t\u00e9 tu\u00e9 par **" + killer + "** \u00e0 port\u00e9e de vue du bot.";
		}
		return killer == null
				? "\u2620\uFE0F **" + victim + "** died within the bot's render distance."
				: "\u2620\uFE0F **" + victim + "** was killed by **" + killer + "** within the bot's render distance.";
	}

	static String pearlThrown(String lang, String name) {
		if (fr(lang)) {
			return name == null
					? "\uD83D\uDD2E Quelqu'un s'est t\u00e9l\u00e9port\u00e9 avec une ender pearl \u00e0 port\u00e9e de vue."
					: "\uD83D\uDD2E **" + name + "** s'est t\u00e9l\u00e9port\u00e9 avec une ender pearl.";
		}
		return name == null
				? "\uD83D\uDD2E Someone teleported with an ender pearl within render distance."
				: "\uD83D\uDD2E **" + name + "** teleported with an ender pearl.";
	}

	static String crystalPlaced(String lang, String by) {
		if (fr(lang)) {
			return by == null
					? "\uD83D\uDCA0 Un end crystal a \u00e9t\u00e9 plac\u00e9 \u00e0 port\u00e9e de vue."
					: "\uD83D\uDCA0 Un end crystal a \u00e9t\u00e9 plac\u00e9 pr\u00e8s de **" + by + "**.";
		}
		return by == null
				? "\uD83D\uDCA0 An end crystal was placed within render distance."
				: "\uD83D\uDCA0 An end crystal was placed near **" + by + "**.";
	}

	static String crystalBroken(String lang, String by) {
		if (fr(lang)) {
			return by == null
					? "\uD83D\uDCA5 Un end crystal a explos\u00e9 \u00e0 port\u00e9e de vue."
					: "\uD83D\uDCA5 Un end crystal a explos\u00e9 pr\u00e8s de **" + by + "**.";
		}
		return by == null
				? "\uD83D\uDCA5 An end crystal exploded within render distance."
				: "\uD83D\uDCA5 An end crystal exploded near **" + by + "**.";
	}

	static String pathFailed(String lang, String name, String label) {
		return fr(lang)
				? "\uD83D\uDEA7 Impossible d'atteindre la stasis **" + label + "** pour **" + name + "**."
				: "\uD83D\uDEA7 Couldn't reach stasis **" + label + "** for **" + name + "**.";
	}

	static String tntPrimed(String lang, String by) {
		if (fr(lang)) {
			return by == null
					? "\uD83E\uDDE8 De la TNT a \u00e9t\u00e9 amorc\u00e9e \u00e0 port\u00e9e de vue du bot !"
					: "\uD83E\uDDE8 De la TNT a \u00e9t\u00e9 amorc\u00e9e pr\u00e8s de **" + by + "** !";
		}
		return by == null
				? "\uD83E\uDDE8 TNT was ignited within the bot's render distance!"
				: "\uD83E\uDDE8 TNT was ignited near **" + by + "**!";
	}

	static String wrongPearl(String lang, String name, String label) {
		if (fr(lang)) {
			return name == null
					? "\uD83D\uDFE0 Une pearl a \u00e9t\u00e9 plac\u00e9e dans la mauvaise stasis (**" + label + "**)."
					: "\uD83D\uDFE0 **" + name + "** a plac\u00e9 sa pearl dans la mauvaise stasis (**" + label + "**).";
		}
		return name == null
				? "\uD83D\uDFE0 A pearl was placed in the wrong stasis (**" + label + "**)."
				: "\uD83D\uDFE0 **" + name + "** put their pearl in the wrong stasis (**" + label + "**).";
	}

	static String returnTooFar(String lang, int distance) {
		return fr(lang)
				? "\uD83D\uDEAB Maison trop loin (~" + distance + " blocs) \u2014 le bot abandonne le retour."
				: "\uD83D\uDEAB Home is too far (~" + distance + " blocks) \u2014 the bot gave up returning.";
	}
}
