package fr.nyuway.stasisbot;

import fr.nyuway.stasisbot.activation.StasisActivator;
import fr.nyuway.stasisbot.chat.HomeRequestListener;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.entity.PearlDetector;
import fr.nyuway.stasisbot.gui.StasisMonitorScreen;
import fr.nyuway.stasisbot.identity.IdentityResolver;
import fr.nyuway.stasisbot.scan.ChamberIndex;
import fr.nyuway.stasisbot.scan.ChamberScanner;
import fr.nyuway.stasisbot.service.AutoReconnect;
import fr.nyuway.stasisbot.service.ConfigWatcher;
import fr.nyuway.stasisbot.service.BotDeathInfo;
import fr.nyuway.stasisbot.service.BotActivity;
import fr.nyuway.stasisbot.service.ChamberWatcher;
import fr.nyuway.stasisbot.service.DeathWatcher;
import fr.nyuway.stasisbot.service.DiscordNotifier;
import fr.nyuway.stasisbot.service.EntityWatcher;
import fr.nyuway.stasisbot.service.HomeService;
import fr.nyuway.stasisbot.service.LagMonitor;
import fr.nyuway.stasisbot.service.PlayerSessionTracker;
import fr.nyuway.stasisbot.service.PlayerWatcher;
import fr.nyuway.stasisbot.service.RenderPresence;
import fr.nyuway.stasisbot.service.WorldSettleTracker;
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
		DiscordNotifier discord = new DiscordNotifier(config);
		RenderPresence presence = new RenderPresence();
		BotDeathInfo deathInfo = new BotDeathInfo();
		PlayerSessionTracker session = new PlayerSessionTracker();
		BotActivity botActivity = new BotActivity();
		WorldSettleTracker settle = new WorldSettleTracker(client);
		discord.setPresence(presence);
		HomeService homeService = new HomeService(client, config, index, identity, pearls, activator, lag, discord, deathInfo, botActivity);
		PlayerWatcher playerWatcher = new PlayerWatcher(client, config, index, identity, discord, presence, session, settle);
		ChamberWatcher chamberWatcher = new ChamberWatcher(client, config, index, identity, pearls, discord, session, botActivity, settle);
		DeathWatcher deathWatcher = new DeathWatcher(client, config, index, identity, discord, presence, deathInfo);
		EntityWatcher entityWatcher = new EntityWatcher(client, config, index, identity, discord, settle);
		AutoReconnect autoReconnect = new AutoReconnect(client);
		ConfigWatcher configWatcher = new ConfigWatcher(config);

		new HomeRequestListener(config, homeService).register();
		deathWatcher.register();

		KeyBinding openMonitor = KeyBindings.registerOpenMonitor();
		ClientTickEvents.END_CLIENT_TICK.register(c -> {
			settle.tick();
			configWatcher.tick();
			autoReconnect.tick();
			lag.onTick();
			homeService.tick();
			playerWatcher.tick();
			chamberWatcher.tick();
			entityWatcher.tick();
			while (openMonitor.wasPressed()) {
				if (c.player != null) {
					c.setScreen(new StasisMonitorScreen(config, index, pearls, identity));
				}
			}
		});

		StasisBot.LOGGER.info("StasisBot ready — listening for [{}] in chat", config.triggerWordsDisplay());
	}
}
