package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.StasisBot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

/**
 * Headless auto-connect / auto-reconnect.
 *
 * <p>The headless Docker launch relies on vanilla quickPlay ({@code
 * --quickPlayMultiplayer}) to auto-join the server. That is a one-shot fired
 * once after the initial resource reload, and under software rendering it races
 * with mod init and silently fails to connect, leaving the client parked on the
 * title screen forever. A 2b2t bot also needs to come back after the inevitable
 * disconnects (queue drops, kicks, server restarts), which quickPlay never does.
 *
 * <p>So when {@code STASIS_SERVER} is set (the same env var build.gradle reads for
 * quickPlay), this service deterministically connects on its own and keeps
 * retrying whenever it finds itself disconnected. It does nothing when the env
 * var is absent, so a normal desktop run (manual login) is never disturbed.
 */
public final class AutoReconnect {

	/** Ticks between connection attempts while disconnected (20 ticks = 1s). */
	private static final int RETRY_TICKS = 20 * 30;

	/**
	 * If the ConnectScreen stays visible this long without the network handler
	 * becoming non-null, the connection attempt has stalled (e.g. a NPE in
	 * ConnectScreen's connector thread silently killed the error-display path).
	 * Force back to title screen so the normal retry cycle can fire.
	 */
	private static final int MAX_CONNECT_SCREEN_TICKS = 20 * 45; // 45 s

	private final MinecraftClient client;
	private volatile String server;
	private volatile boolean enabled = true;
	private int cooldown = 0;
	private int connectScreenTicks = 0;

	public AutoReconnect(MinecraftClient client) {
		this.client = client;
		String env = System.getenv("STASIS_SERVER");
		this.server = (env != null && !env.isBlank()) ? env.trim() : null;
		if (this.server != null) {
			StasisBot.LOGGER.info("[auto-connect] enabled — will keep '{}' joined", this.server);
		}
	}

	/** The server the bot auto-joins (host or host:port), or null on a desktop run. */
	public String target() { return server; }

	/** Point the bot at a different server (used by the remote "connect to server" action). */
	public void setServer(String s) {
		if (s != null && !s.isBlank()) {
			this.server = s.trim();
			StasisBot.LOGGER.info("[auto-connect] target set to {}", this.server);
		}
	}

	/** Turn auto-reconnect on/off — off keeps the bot disconnected after a remote disconnect. */
	public void setEnabled(boolean e) { this.enabled = e; }

	/** Connect on the very next tick (used by remote connect/reconnect). */
	public void connectNow() { this.enabled = true; this.cooldown = 0; }

	public void tick() {
		if (server == null || !enabled) {
			return; // not a headless run, or auto-reconnect disabled; leave it alone
		}
		if (client.world != null || client.getNetworkHandler() != null) {
			connectScreenTicks = 0;
			return; // already in-game / queued / connecting at the play level
		}
		// Still loading (the splash/loading overlay is up, no screen yet): wait.
		if (client.getOverlay() != null) {
			return;
		}
		Screen screen = client.currentScreen;
		if (screen == null || screen instanceof ConnectScreen) {
			if (screen instanceof ConnectScreen) {
				// Guard against the ConnectScreen getting stuck (e.g. a NPE in the
				// connector thread that kills the error-display path before the screen
				// can transition to the disconnect/title screen).
				if (++connectScreenTicks > MAX_CONNECT_SCREEN_TICKS) {
					StasisBot.LOGGER.warn("[auto-connect] ConnectScreen stuck for {}s without connecting — forcing title screen",
							MAX_CONNECT_SCREEN_TICKS / 20);
					client.setScreen(new TitleScreen());
					connectScreenTicks = 0;
				}
			}
			return; // a connection attempt is already in progress
		}
		connectScreenTicks = 0;
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		cooldown = RETRY_TICKS;
		connect();
	}

	private void connect() {
		try {
			StasisBot.LOGGER.info("[auto-connect] connecting to {} ...", server);
			ServerAddress address = ServerAddress.parse(server);
			ServerInfo info = new ServerInfo("StasisBot", server, ServerInfo.ServerType.OTHER);
			ConnectScreen.connect(client.currentScreen, client, address, info, false, null);
		} catch (Exception e) {
			StasisBot.LOGGER.warn("[auto-connect] attempt failed, will retry", e);
		}
	}
}
