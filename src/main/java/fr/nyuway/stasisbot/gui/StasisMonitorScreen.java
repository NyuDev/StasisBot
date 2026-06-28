package fr.nyuway.stasisbot.gui;

import fr.nyuway.stasisbot.activation.BaritoneSupport;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.entity.PearlDetector;
import fr.nyuway.stasisbot.identity.IdentityResolver;
import fr.nyuway.stasisbot.model.StasisChamber;
import fr.nyuway.stasisbot.scan.ChamberIndex;
import fr.nyuway.stasisbot.service.ControllerService;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The single hard-coded control panel (key H). It has two faces driven by one layout:
 *
 * <ul>
 *   <li><b>Bot mode</b> (local): the toggles edit this client's own config and the left
 *       column lists the stasis chambers it detects — the original monitor behaviour.</li>
 *   <li><b>Controller mode</b> (remote): the very same toggles drive a <em>remote</em>
 *       headless bot over the HTTP control API, and the left column becomes the connection
 *       panel (endpoint + shared secret). Buttons read/write the bot's live state.</li>
 * </ul>
 *
 * Which face is shown depends only on whether a {@link ControllerService} was supplied.
 */
public final class StasisMonitorScreen extends Screen {

	/** The boolean settings, in display order: key (control-API name) and label. */
	private static final String[][] TOGGLES = {
			{"drop", "Drop pearl"},
			{"reopen", "Reopen trap"},
			{"return", "Return home"},
			{"death", "Return on death"},
			{"online", "Require online"},
			{"skip", "Skip if present"},
			{"members", "Members ctrl"},
			{"debug", "Debug"},
	};

	private final StasisBotConfig config;
	private final ChamberIndex index;     // null in controller mode
	private final PearlDetector pearls;   // null in controller mode
	private final IdentityResolver identity; // null in controller mode
	private final ControllerService remote;  // null in bot mode

	/** Where the remote "Detected chambers" header sits; the chamber buttons start just under it. */
	private static final int CHAMBER_HEADER_Y = 156;

	private String lastSignature = "";
	private String remoteChamberSig = "";
	private long lastRefresh = 0L;
	private long lastChamberFetch = 0L;
	private long homeFeedbackAt = 0L;
	private ButtonWidget homeButton;

	// Controller-mode widgets, kept for live updates without a rebuild (so fields keep focus).
	private final Map<String, ButtonWidget> toggleButtons = new LinkedHashMap<>();
	private final List<ButtonWidget> remoteGated = new ArrayList<>();
	private ButtonWidget langButton;
	private ButtonWidget movementButton;
	private TextFieldWidget endpointField;
	private TextFieldWidget secretField;

	/** Bot mode: drives the local client's config and shows detected chambers. */
	public StasisMonitorScreen(StasisBotConfig config, ChamberIndex index, PearlDetector pearls,
	                           IdentityResolver identity) {
		this(config, index, pearls, identity, null);
	}

	/** Controller mode: the same panel, driving a remote bot over the control API. */
	public StasisMonitorScreen(StasisBotConfig config, ControllerService remote) {
		this(config, null, null, null, remote);
	}

	private StasisMonitorScreen(StasisBotConfig config, ChamberIndex index, PearlDetector pearls,
	                            IdentityResolver identity, ControllerService remote) {
		super(Text.literal(remote != null ? "StasisBot — remote control" : "StasisBot — monitor"));
		this.config = config;
		this.index = index;
		this.pearls = pearls;
		this.identity = identity;
		this.remote = remote;
	}

	private boolean remote() { return remote != null; }
	private boolean synced() { return remote != null && remote.status() == ControllerService.Status.SYNCED; }

	private String myName() {
		return client != null && client.player != null ? client.player.getGameProfile().name() : "";
	}

	/** Throttled fetch of the remote chamber list + position. Driven from render() (always
	 *  called) because Screen.tick() is not reliably invoked on this version. */
	private void pumpRemote() {
		long now = System.currentTimeMillis();
		if (now - lastChamberFetch > 2000L) {
			lastChamberFetch = now;
			remote.requestChambers();
			remote.requestPos(myName());
		}
	}

	@Override
	protected void init() {
		toggleButtons.clear();
		remoteGated.clear();
		buildToggles();
		if (remote()) {
			buildConnectionPanel();
			buildRemoteChamberButtons();
			remoteChamberSig = remoteChamberSignature();
			refreshRemoteLabels();
		} else {
			buildChamberButtons();
			lastSignature = chamberSignature();
		}
	}

	@Override
	public void tick() {
		if (remote()) {
			refreshRemoteLabels();
			long now = System.currentTimeMillis();
			if (synced() && now - lastChamberFetch > 3000L) {
				lastChamberFetch = now;
				remote.requestChambers(); // keep the chamber list live
			}
			return;
		}
		if (homeButton != null) homeButton.setMessage(setHomeLabel());
		long now = System.currentTimeMillis();
		if (now - lastRefresh < 400L) return;
		lastRefresh = now;
		String sig = chamberSignature();
		if (!sig.equals(lastSignature)) {
			lastSignature = sig;
			clearChildren();
			init();
		}
	}

	// --- routing: a setting reads/writes the local config OR the remote bot -----

	private boolean getFlag(String key) {
		if (remote()) return remote.flag(key, false);
		return switch (key) {
			case "drop" -> config.dropPearlForPlayer();
			case "reopen" -> config.reopenTrigger();
			case "return" -> config.returnHome();
			case "death" -> config.returnHomeOnDeath();
			case "online" -> config.requireOnline();
			case "skip" -> config.skipIfPresent();
			case "members" -> config.baseMembersControl();
			case "debug" -> config.debug();
			case "baritone" -> config.useBaritone();
			default -> false;
		};
	}

	private void setFlag(String key, boolean v) {
		if (remote()) { remote.set(key, v ? "on" : "off"); return; }
		switch (key) {
			case "drop" -> config.setDropPearlForPlayer(v);
			case "reopen" -> config.setReopenTrigger(v);
			case "return" -> config.setReturnHome(v);
			case "death" -> config.setReturnHomeOnDeath(v);
			case "online" -> config.setRequireOnline(v);
			case "skip" -> config.setSkipIfPresent(v);
			case "members" -> config.setBaseMembersControl(v);
			case "debug" -> config.setDebug(v);
			case "baritone" -> config.setUseBaritone(v);
			default -> { }
		}
	}

	private String getLang() {
		return remote() ? remote.text("lang", config.language()) : config.language();
	}

	private void toggleLang() {
		String next = "fr".equalsIgnoreCase(getLang()) ? "en" : "fr";
		if (remote()) remote.set("lang", next);
		else config.setLanguage(next);
	}

	// --- right column: toggles & actions (shared layout) ------------------------

	private void buildToggles() {
		int bw = 150;
		int x = width - bw - 20;
		int top = 28;
		int count = 16;
		int avail = height - top - 34;
		int step = Math.max(15, Math.min(22, avail / count));
		int bh = Math.min(20, step - 2);
		int y = top;

		for (String[] t : TOGGLES) {
			addToggle(x, y, bw, bh, t[0], t[1]);
			y += step;
		}
		addMovementToggle(x, y, bw, bh);
		y += step;

		langButton = ButtonWidget.builder(langLabel(), b -> { toggleLang(); b.setMessage(langLabel()); })
				.dimensions(x, y, bw, bh).build();
		addDrawableChild(langButton);
		y += step;

		if (!remote()) {
			addDrawableChild(ButtonWidget.builder(controllerModeLabel(),
					b -> { config.setControllerMode(!config.controllerMode()); b.setMessage(controllerModeLabel()); })
					.dimensions(x, y, bw, bh).build());
			y += step;
		}

		addDrawableChild(ButtonWidget.builder(Text.literal("§bManage mappings…"),
				b -> { if (client != null) client.setScreen(new AliasListScreen(this, config)); })
				.dimensions(x, y, bw, bh).build());
		y += step;

		addDrawableChild(ButtonWidget.builder(Text.literal("§bTrigger words…"),
				b -> { if (client != null) client.setScreen(new TriggerWordsScreen(this, config)); })
				.dimensions(x, y, bw, bh).build());
		y += step;

		addDrawableChild(ButtonWidget.builder(Text.literal("§dDiscord…"),
				b -> { if (client != null) client.setScreen(new DiscordScreen(this, config)); })
				.dimensions(x, y, bw, bh).build());
		y += step;

		// Set home here: bot mode pins the bot's own block; controller mode sends the
		// OPERATOR's position AND facing, so the bot adopts where YOU stand and look.
		homeButton = ButtonWidget.builder(setHomeLabel(), b -> {
			if (remote()) {
				if (client != null && client.player != null) {
					var p = client.player.getBlockPos();
					remote.setHome(p.getX(), p.getY(), p.getZ(), client.player.getYaw(), client.player.getPitch());
					homeFeedbackAt = System.currentTimeMillis();
				}
			} else if (client != null && client.player != null) {
				var p = client.player.getBlockPos();
				config.setReturnPos(p.getX(), p.getY(), p.getZ());
			}
			b.setMessage(setHomeLabel());
		}).dimensions(x, y, bw, bh).build();
		if (remote()) remoteGated.add(homeButton);
		addDrawableChild(homeButton);
		y += step;

		// Rescan: bot mode re-indexes locally; controller mode asks the bot to re-scan.
		ButtonWidget rescan = ButtonWidget.builder(Text.literal("Rescan"), b -> {
			if (remote()) {
				remote.rescan();
			} else {
				if (index != null) index.invalidate();
				clearChildren();
				init();
			}
		}).dimensions(x, y, bw, bh).build();
		if (remote()) remoteGated.add(rescan);
		addDrawableChild(rescan);
		y += step;

		if (remote()) {
			ButtonWidget refresh = ButtonWidget.builder(Text.literal("Refresh from bot"), b -> remote.refresh())
					.dimensions(x, y, bw, bh).build();
			remoteGated.add(refresh);
			addDrawableChild(refresh);
		}
	}

	private void addToggle(int x, int y, int w, int h, String key, String label) {
		ButtonWidget b = ButtonWidget.builder(toggleLabel(label, getFlag(key)), btn -> {
			boolean next = !getFlag(key);
			setFlag(key, next);
			btn.setMessage(toggleLabel(label, next));
		}).dimensions(x, y, w, h).build();
		if (remote()) b.active = synced();
		addDrawableChild(b);
		toggleButtons.put(key, b);
	}

	private void addMovementToggle(int x, int y, int w, int h) {
		boolean baritone = remote() || BaritoneSupport.isAvailable();
		movementButton = ButtonWidget.builder(movementLabel(baritone), b -> {
			if (!remote() && !BaritoneSupport.isAvailable()) return;
			setFlag("baritone", !getFlag("baritone"));
			b.setMessage(movementLabel(true));
		}).dimensions(x, y, w, h).build();
		movementButton.active = remote() ? synced() : baritone;
		addDrawableChild(movementButton);
	}

	private Text movementLabel(boolean baritoneAvailable) {
		if (!remote() && !baritoneAvailable) return Text.literal("Movement: §7Simple (no Baritone)");
		boolean on = getFlag("baritone");
		return Text.literal("Movement: " + (on ? "§aBaritone" : "§eSimple"));
	}

	// --- left column (controller mode): connection panel ------------------------

	private void buildConnectionPanel() {
		int x = 20;
		int fw = 220;
		int y = 34;

		endpointField = new TextFieldWidget(textRenderer, x, y, fw, 18, Text.literal("endpoint"));
		endpointField.setMaxLength(120);
		endpointField.setText(config.controlEndpoint());
		endpointField.setPlaceholder(Text.literal("§7http://host:6969"));
		addDrawableChild(endpointField);

		secretField = new TextFieldWidget(textRenderer, x, y + 24, fw, 18, Text.literal("secret"));
		secretField.setMaxLength(128);
		secretField.setText("");
		secretField.setPlaceholder(Text.literal(config.controlSecret().isBlank()
				? "§7shared secret (set it)" : "§7shared secret (saved — blank to keep)"));
		addDrawableChild(secretField);

		addDrawableChild(ButtonWidget.builder(Text.literal("§aSave & Connect"), b -> saveAndConnect())
				.dimensions(x, y + 48, 140, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Disconnect"), b -> remote.disconnect())
				.dimensions(x + 144, y + 48, 76, 20).build());

		addDrawableChild(ButtonWidget.builder(Text.literal("§dBot Control…"),
				b -> { if (client != null) client.setScreen(new BotControlScreen(this, config, remote)); })
				.dimensions(x, y + 73, fw / 2 - 2, 18).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("§bBot logs…"),
				b -> { if (client != null) client.setScreen(new BotLogsScreen(this, config, remote)); })
				.dimensions(x + fw / 2 + 2, y + 73, fw / 2 - 2, 18).build());

		addDrawableChild(ButtonWidget.builder(Text.literal("§7Switch to BOT mode (relaunch)"),
				b -> config.setControllerMode(false))
				.dimensions(x, y + 93, fw, 16).build());
	}

	private void saveAndConnect() {
		if (endpointField != null && !endpointField.getText().isBlank()) {
			config.setControlEndpoint(endpointField.getText().trim());
		}
		if (secretField != null && !secretField.getText().isBlank()) {
			config.setControlSecret(secretField.getText().trim());
			secretField.setText("");
		}
		remote.connect();
	}

	/** Controller mode: refresh toggle labels/enabled from the synced state, no rebuild. */
	private void refreshRemoteLabels() {
		boolean synced = synced();
		for (String[] t : TOGGLES) {
			ButtonWidget b = toggleButtons.get(t[0]);
			if (b == null) continue;
			b.setMessage(synced ? toggleLabel(t[1], getFlag(t[0])) : Text.literal(t[1] + ": §7?"));
			b.active = synced;
		}
		if (movementButton != null) {
			movementButton.setMessage(synced ? movementLabel(true) : Text.literal("Movement: §7?"));
			movementButton.active = synced;
		}
		if (langButton != null) {
			langButton.setMessage(synced ? langLabel() : Text.literal("Language: §7?"));
			langButton.active = synced;
		}
		for (ButtonWidget b : remoteGated) b.active = synced;
		if (homeButton != null) homeButton.setMessage(setHomeLabel()); // shows the "Home set ✔" flash
	}

	private Text setHomeLabel() {
		if (remote()) {
			if (System.currentTimeMillis() - homeFeedbackAt < 2000L) return Text.literal("§aHome set ✔");
			// Relative to YOU: ✔ when the operator is standing on the bot's home block (clicking
			// would re-pin the same spot), not when the bot is there.
			return Text.literal(remote.watcherAtHome() ? "§aSet home here §a✔" : "§bSet home here");
		}
		return Text.literal(standingOnHome() ? "§aSet home here §a✔" : "§bSet home here");
	}

	private boolean standingOnHome() {
		if (!config.hasReturnPos() || client == null || client.player == null) return false;
		var p = client.player.getBlockPos();
		return p.getX() == config.returnX() && p.getY() == config.returnY() && p.getZ() == config.returnZ();
	}

	// --- left column (bot mode): detected chambers ------------------------------

	private void buildChamberButtons() {
		List<StasisChamber> chambers = currentChambers();
		if (chambers.isEmpty()) return;

		int bw = 220;
		int bh = 18;
		int x = 20;
		int y = 30;
		int max = Math.min(chambers.size(), (height - 60) / (bh + 2));

		for (int i = 0; i < max; i++) {
			StasisChamber c = chambers.get(i);
			boolean pearl = pearls.hasOwnPearl(client.world, c, chambers);
			boolean wrong = pearl && isWrongPearl(c, chambers);
			String dot = !pearl ? "§c○ " : (wrong ? "§6● " : "§a● ");
			String label = dot + "§f" + c.label() + " §7" + c.trigger().toShortString();
			List<String> keywords = new ArrayList<>(c.signTokens());
			addDrawableChild(ButtonWidget.builder(Text.literal(label),
					b -> { if (client != null) client.setScreen(new AliasEditScreen(this, config, "", keywords)); })
					.dimensions(x, y, bw, bh).build());
			y += bh + 2;
		}
	}

	private List<StasisChamber> currentChambers() {
		boolean inWorld = index != null && client != null && client.world != null && client.player != null;
		return inWorld ? index.chambers(client.world, client.player.getBlockPos()) : List.of();
	}

	// --- left column (controller mode): remote chambers as clickable buttons ----

	/** Build a clickable button per remote chamber; clicking opens its detail (reveal + trigger). */
	private void buildRemoteChamberButtons() {
		List<ControllerService.RemoteChamber> chs = remote.chambers();
		if (chs.isEmpty()) return;
		int x = 20;
		int bw = 220;
		int bh = 13;
		int cy = CHAMBER_HEADER_Y + 13;
		int maxRows = Math.max(0, (height - cy - 40) / (bh + 1));
		for (int i = 0; i < Math.min(chs.size(), maxRows); i++) {
			final ControllerService.RemoteChamber c = chs.get(i);
			String dot = c.state() == '0' ? "§c○" : (c.state() == 'w' ? "§6●" : "§a●");
			// Coords are intentionally hidden here — they are revealed on the detail screen.
			ButtonWidget b = ButtonWidget.builder(Text.literal(dot + " §f" + c.label()),
					btn -> { if (client != null) client.setScreen(new ChamberInfoScreen(this, remote, c)); })
					.dimensions(x, cy, bw, bh).build();
			addDrawableChild(b);
			cy += bh + 1;
		}
	}

	/** A fingerprint of the remote chamber list (labels + pearl states) to detect changes. */
	private String remoteChamberSignature() {
		StringBuilder sb = new StringBuilder();
		for (ControllerService.RemoteChamber c : remote.chambers()) {
			sb.append(c.label()).append(c.state()).append(';');
		}
		return sb.toString();
	}

	/** Rebuild the chamber buttons when the bot's list changes — but not mid-typing in a field. */
	private void maybeRebuildRemoteChambers() {
		String sig = remoteChamberSignature();
		if (sig.equals(remoteChamberSig)) return;
		if ((endpointField != null && endpointField.isFocused())
				|| (secretField != null && secretField.isFocused())) return;
		remoteChamberSig = sig;
		clearChildren();
		init();
	}

	private boolean isWrongPearl(StasisChamber c, List<StasisChamber> chambers) {
		if (client == null || client.world == null) return false;
		String owner = pearls.ownPearlThrower(client.world, c, chambers);
		return owner != null && !c.matchesAny(identity.tokensFor(owner));
	}

	private String chamberSignature() {
		List<StasisChamber> chambers = currentChambers();
		if (chambers.isEmpty() || client == null || client.world == null) return "none";
		StringBuilder sb = new StringBuilder(chambers.size() * 20);
		for (StasisChamber c : chambers) {
			boolean pearl = pearls.hasOwnPearl(client.world, c, chambers);
			char state = pearl ? (isWrongPearl(c, chambers) ? 'w' : '1') : '0';
			sb.append(c.trigger().asLong()).append(state).append(';');
		}
		return sb.toString();
	}

	// --- shared helpers ---------------------------------------------------------

	private static Text toggleLabel(String label, boolean on) {
		return Text.literal(label + ": " + (on ? "§aON" : "§cOFF"));
	}

	private Text langLabel() {
		return Text.literal("Language: " + getLang().toUpperCase(Locale.ROOT));
	}

	private Text controllerModeLabel() {
		return Text.literal("Controller mode: " + (config.controllerMode() ? "§aON §7(relaunch)" : "§cOFF"));
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);

		String leftHeader = remote() ? "Connection" : "Detected chambers";
		ctx.drawTextWithShadow(textRenderer,
				Text.literal(leftHeader).formatted(Formatting.AQUA), 20, 16, 0xFFFFFFFF);
		ctx.drawTextWithShadow(textRenderer,
				Text.literal(remote() ? "Settings (remote)" : "Settings").formatted(Formatting.AQUA),
				width - 170, 16, 0xFFFFFFFF);

		if (remote()) {
			pumpRemote(); // keep the chamber list + position live (render is always called)
			maybeRebuildRemoteChambers(); // re-make the chamber buttons when the bot's list changes
			// The bot's detected chambers (clickable buttons, built as children below the panel).
			int cy = CHAMBER_HEADER_Y;
			ctx.drawTextWithShadow(textRenderer,
					Text.literal("Detected chambers (remote)").formatted(Formatting.AQUA), 20, cy, 0xFFFFFFFF);
			if (remote.chambers().isEmpty()) {
				ctx.drawTextWithShadow(textRenderer,
						Text.literal(synced() ? "§7(none — bot not parked at the base?)" : "§7(connect to see them)"),
						20, cy + 13, 0xFFAAAAAA);
			} else {
				ctx.drawTextWithShadow(textRenderer,
						Text.literal("§8click a chamber: reveal coords + trigger trap"), 20, height - 38, 0xFF888888);
			}

			String dot = switch (remote.status()) {
				case SYNCED -> "§a● synced";
				case CONNECTING -> "§e● connecting…";
				case ERROR -> "§c● error";
				case IDLE -> "§7● idle";
			};
			ctx.drawTextWithShadow(textRenderer, Text.literal(dot + "  §7" + remote.info()), 20, height - 26, 0xFFFFFFFF);
			ctx.drawTextWithShadow(textRenderer,
					Text.literal("§8HTTP control API — no 2b2t chat involved"), 20, height - 14, 0xFF888888);
			return;
		}

		if (currentChambers().isEmpty()) {
			ctx.drawTextWithShadow(textRenderer,
					Text.literal("§7None nearby — park the bot next to your stasis signs."), 20, 34, 0xFFAAAAAA);
		} else {
			ctx.drawTextWithShadow(textRenderer,
					Text.literal("§8click a chamber to map it to a player"), 20, height - 14, 0xFF888888);
		}

		String master = config.master().isBlank() ? "(none)" : config.master();
		ctx.drawTextWithShadow(textRenderer,
				Text.literal("§7master: §f" + master + "  §7| DM: §f" + config.commandPrefix() + " help"),
				width - 320, height - 14, 0xFFFFFFFF);
	}
}
