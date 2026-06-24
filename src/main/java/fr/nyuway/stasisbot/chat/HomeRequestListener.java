package fr.nyuway.stasisbot.chat;

import fr.nyuway.stasisbot.StasisBot;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.service.HomeService;
import fr.nyuway.stasisbot.service.SurveillanceService;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/**
 * Bridges Fabric chat events to {@link HomeService} (home requests / master
 * commands) and {@link SurveillanceService} (watched players + chat relay).
 * Listens to both verified player chat and generic game messages, because 2b2t
 * routes chat (and whispers) through the latter. It only extracts "(sender, body,
 * dm)"; all decisions live in the services.
 */
public final class HomeRequestListener {

	private final StasisBotConfig config;
	private final HomeService homeService;
	private final SurveillanceService surveillance;

	public HomeRequestListener(StasisBotConfig config, HomeService homeService, SurveillanceService surveillance) {
		this.config = config;
		this.homeService = homeService;
		this.surveillance = surveillance;
	}

	public void register() {
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> {
			String name = sender != null ? sender.name() : null;
			dispatch(name, message.getString());
		});

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) return;
			dispatch(null, message.getString());
		});
	}

	/**
	 * Route a raw chat line into the services. Even when the event hands us a
	 * verified {@code knownSender}, the {@code message} text can still arrive
	 * server-decorated ({@code <Name> body}, {@code Name whispers: body}), so we
	 * always run it through {@link ChatMessageParser} to peel the decoration off,
	 * recover the bare body, and tell a whisper (DM) from public chat. A verified
	 * sender means it came through the signed chat channel — never a DM.
	 */
	private void dispatch(String knownSender, String raw) {
		var parsed = ChatMessageParser.fromRaw(raw);
		String sender = knownSender;
		String body = raw;
		boolean dm = false;
		if (parsed.isPresent()) {
			if (sender == null) sender = parsed.get().sender();
			body = parsed.get().body();
			dm = knownSender == null && parsed.get().dm();
		}
		if (sender == null || body == null) {
			if (config.debug() && config.matchesTrigger(raw)) {
				// A line carries a trigger word but no sender could be extracted — log the
				// raw shape so an unseen DM format can be added to ChatMessageParser.
				StasisBot.LOGGER.info("[chat] trigger seen but unparsed: \"{}\"", raw);
			}
			return;
		}
		homeService.onChatMessage(sender, body, dm);
		if (surveillance != null) surveillance.onChat(sender, body, dm);
	}
}
