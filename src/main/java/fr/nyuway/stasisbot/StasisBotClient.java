package fr.nyuway.stasisbot;

import fr.nyuway.stasisbot.activation.StasisActivator;
import fr.nyuway.stasisbot.chat.HomeRequestListener;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.entity.PearlDetector;
import fr.nyuway.stasisbot.control.ControlHttpServer;
import fr.nyuway.stasisbot.gui.StasisMonitorScreen;
import fr.nyuway.stasisbot.identity.IdentityResolver;
import fr.nyuway.stasisbot.scan.ChamberIndex;
import fr.nyuway.stasisbot.scan.ChamberScanner;
import fr.nyuway.stasisbot.service.AutoReconnect;
import fr.nyuway.stasisbot.service.ChatTap;
import fr.nyuway.stasisbot.service.ConfigWatcher;
import fr.nyuway.stasisbot.service.ControllerService;
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
import fr.nyuway.stasisbot.service.SurveillanceService;
import fr.nyuway.stasisbot.service.WorldSettleTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

/**
 * Client entry point. Builds the object graph (constructor injection — no static
 * singletons) and wires the chat listener and keybind to it.
 *
 * <p>The same mod runs in two modes: as the <b>bot</b> (default — detects chambers,
 * serves home requests, watches the base) or as a <b>controller</b> (an in-game GUI
 * that drives a headless bot over the encrypted control channel). The mode is the
 * config flag {@code controllerMode}, overridable at launch with {@code STASIS_MODE}.
 */
public final class StasisBotClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		MinecraftClient client = MinecraftClient.getInstance();
		StasisBotConfig config = StasisBotConfig.load();

		// Mode select: config flag, with an env override for headless/Docker convenience.
		String envMode = System.getenv("STASIS_MODE");
		boolean controller = config.controllerMode();
		if (envMode != null && !envMode.isBlank()) controller = envMode.trim().equalsIgnoreCase("controller");

		if (controller) {
			initController(client, config);
		} else {
			initBot(client, config);
		}
	}

	/** Controller mode: no bot services — the same panel, driving a remote bot over HTTP. */
	private void initController(MinecraftClient client, StasisBotConfig config) {
		ControllerService controllerSvc = new ControllerService(config);
		controllerSvc.register();

		KeyBinding openPanel = KeyBindings.registerOpenMonitor();
		ClientTickEvents.END_CLIENT_TICK.register(c -> {
			while (openPanel.wasPressed()) {
				if (c.player != null) c.setScreen(new StasisMonitorScreen(config, controllerSvc));
			}
		});
		StasisBot.LOGGER.info("StasisBot CONTROLLER mode — press the keybind to open the remote panel");
	}

	/** Bot mode: the full home-bot graph plus the (opt-in) control endpoint. */
	private void initBot(MinecraftClient client, StasisBotConfig config) {
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
		SurveillanceService surveillance = new SurveillanceService(client, config, discord);
		ChatTap chatTap = new ChatTap();
		ControlHttpServer control = new ControlHttpServer(config,
				new fr.nyuway.stasisbot.control.BotIntrospection() {
					@Override public String chambers() {
						var world = client.world;
						var self = client.player;
						if (world == null || self == null) return "";
						var list = index.chambers(world, self.getBlockPos());
						StringBuilder sb = new StringBuilder();
						for (var c : list) {
							boolean pearl = pearls.hasOwnPearl(world, c, list);
							boolean wrong = false;
							if (pearl) {
								String owner = pearls.ownPearlThrower(world, c, list);
								wrong = owner != null && !c.matchesAny(identity.tokensFor(owner));
							}
							char st = pearl ? (wrong ? 'w' : '1') : '0';
							if (sb.length() > 0) sb.append('\n');
							sb.append(c.label()).append('|')
								.append(c.trigger().getX()).append(' ').append(c.trigger().getY()).append(' ').append(c.trigger().getZ())
								.append('|').append(st);
						}
						return sb.toString();
					}
					@Override public boolean setHome() {
						var self = client.player;
						if (self == null) return false;
						var p = self.getBlockPos();
						config.setReturnPos(p.getX(), p.getY(), p.getZ());
						return true;
					}
					@Override public void rescan() { index.invalidate(); }
					@Override public String chatLog() { return chatTap.dump(); }
					@Override public void say(String text) {
						var nh = client.getNetworkHandler();
						if (nh == null || text == null) return;
						String t = text.trim();
						if (t.isEmpty()) return;
						if (t.startsWith("/")) nh.sendChatCommand(t.substring(1));
						else nh.sendChatMessage(t);
					}
					@Override public String posInfo(String watcher) {
						var self = client.player;
						if (self == null) return "";
						var bp = self.getBlockPos();
						double dist = -1;
						if (watcher != null && !watcher.isBlank() && client.world != null) {
							for (var pl : client.world.getPlayers()) {
								if (watcher.equalsIgnoreCase(pl.getGameProfile().name())) { dist = self.distanceTo(pl); break; }
							}
						}
						return bp.getX() + " " + bp.getY() + " " + bp.getZ() + "|" + (dist < 0 ? "-1" : String.valueOf(Math.round(dist)));
					}
					@Override public void goTo(int x, int y, int z) { homeService.remoteGoto(x, y, z); }
					@Override public void come(String player) { homeService.remoteCome(player); }
					@Override public void stopNav() { homeService.remoteStop(); }
					@Override public void serverDisconnect() {
						autoReconnect.setEnabled(false);
						var nh = client.getNetworkHandler();
						if (nh != null) nh.getConnection().disconnect(net.minecraft.text.Text.literal("StasisBot: remote disconnect"));
					}
					@Override public void serverConnect(String hostPort) {
						if (hostPort != null && !hostPort.isBlank()) autoReconnect.setServer(hostPort);
						var nh = client.getNetworkHandler();
						if (nh != null) nh.getConnection().disconnect(net.minecraft.text.Text.literal("StasisBot: switching server"));
						autoReconnect.connectNow();
					}
				});

		new HomeRequestListener(config, homeService, surveillance).register();
		deathWatcher.register();
		chatTap.register();
		control.start();

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
			surveillance.tick();
			while (openMonitor.wasPressed()) {
				if (c.player != null) {
					c.setScreen(new StasisMonitorScreen(config, index, pearls, identity));
				}
			}
		});

		StasisBot.LOGGER.info("StasisBot ready — listening for [{}] in chat", config.triggerWordsDisplay());
	}
}
