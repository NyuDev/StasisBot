package fr.nyuway.stasisbot.chat;

import fr.nyuway.stasisbot.StasisBot;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.service.HomeService;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/**
 * Bridges Fabric chat events to {@link HomeService}. Listens to both verified
 * player chat and generic game messages, because 2b2t routes chat (and whispers)
 * through the latter. It only extracts "(sender, body)"; all decisions — trigger
 * word, master config commands — live in the service.
 */
public final class HomeRequestListener {

	private final StasisBotConfig config;
	private final HomeService homeService;

	public HomeRequestListener(StasisBotConfig config, HomeService homeService) {
		this.config = config;
		this.homeService = homeService;
	}

	public void register() {
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> {
			if (sender != null) {
				homeService.onChatMessage(sender.name(), message.getString());
			} else {
				ChatMessageParser.fromRaw(message.getString())
						.ifPresent(p -> homeService.onChatMessage(p.sender(), p.body()));
			}
		});

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (overlay) return;
			String raw = message.getString();
			var parsed = ChatMessageParser.fromRaw(raw);
			if (parsed.isPresent()) {
				homeService.onChatMessage(parsed.get().sender(), parsed.get().body());
			} else if (config.debug() && config.matchesTrigger(raw)) {
				// A line carries a trigger word but no sender could be extracted — log the
				// raw shape so an unseen DM format can be added to ChatMessageParser.
				StasisBot.LOGGER.info("[chat] trigger seen but unparsed: \"{}\"", raw);
			}
		});
	}
}
