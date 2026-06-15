package fr.nyuway.stasisbot;

import fr.nyuway.stasisbot.activation.StasisActivator;
import fr.nyuway.stasisbot.chat.HomeRequestListener;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.entity.PearlDetector;
import fr.nyuway.stasisbot.gui.StasisMonitorScreen;
import fr.nyuway.stasisbot.identity.IdentityResolver;
import fr.nyuway.stasisbot.scan.ChamberIndex;
import fr.nyuway.stasisbot.scan.ChamberScanner;
import fr.nyuway.stasisbot.service.HomeService;
import fr.nyuway.stasisbot.service.LagMonitor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

/**
 * Client entry point. Builds the object graph (constructor injection — no static
 * singletons) and wires the chat listener and keybind to it.
 */
public final class StasisBotClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		MinecraftClient client = MinecraftClient.getInstance();

		StasisBotConfig config = StasisBotConfig.load();
		ChamberIndex index = new ChamberIndex(new ChamberScanner(config), config);
		PearlDetector pearls = new PearlDetector(config);
		IdentityResolver identity = new IdentityResolver(config);
		StasisActivator activator = new StasisActivator(config);
		LagMonitor lag = new LagMonitor(config.lagThresholdMillis());
		HomeService homeService = new HomeService(client, config, index, identity, pearls, activator, lag);

		new HomeRequestListener(config, homeService).register();

		KeyBinding openMonitor = KeyBindings.registerOpenMonitor();
		ClientTickEvents.END_CLIENT_TICK.register(c -> {
			lag.onTick();
			homeService.tick();
			while (openMonitor.wasPressed()) {
				if (c.player != null) {
					c.setScreen(new StasisMonitorScreen(config, index, pearls));
				}
			}
		});

		StasisBot.LOGGER.info("StasisBot ready — listening for [{}] in chat", config.triggerWordsDisplay());
	}
}
