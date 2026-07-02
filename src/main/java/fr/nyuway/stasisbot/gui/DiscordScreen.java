package fr.nyuway.stasisbot.gui;

import fr.nyuway.stasisbot.config.DiscordEvent;
import fr.nyuway.stasisbot.config.PingMode;
import fr.nyuway.stasisbot.config.PingTarget;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.service.ControllerService;
import fr.nyuway.stasisbot.service.DiscordNotifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Configure the optional Discord webhook: enable it, paste a webhook URL, test
 * it, and tune every event independently. Each event row has its own Send /
 * {@code @everyone} ping mode (off / outsiders / all) plus optional Gear, coords
 * (XYZ, spoiler-tagged) and Distance extras where the event supports them.
 * Paginated since there are many events. Everything is off by default and
 * persists immediately via the config.
 */
public final class DiscordScreen extends Screen {

	private final Screen parent;
	private final StasisBotConfig config;
	/** Non-null in controller mode: mutations are sent to the bot via the HTTP API. */
	private final ControllerService controller;
	private final DiscordNotifier notifier;
	private final DiscordEvent[] events = DiscordEvent.values();

	private TextFieldWidget urlField;
	private String urlDraft;
	private TextFieldWidget roleField;
	private String roleDraft;
	private int page = 0;
	private int rowsPerPage = 1;
	private int listTop;
	private String status = "";

	/** Bot-mode constructor (no remote). */
	public DiscordScreen(Screen parent, StasisBotConfig config) {
		this(parent, config, null);
	}

	/** Controller-mode constructor: every mutation is forwarded to the bot over the control API. */
	public DiscordScreen(Screen parent, StasisBotConfig config, ControllerService controller) {
		super(Text.literal("StasisBot — Discord"));
		this.parent = parent;
		this.config = config;
		this.controller = controller;
		this.notifier = new DiscordNotifier(config);
		this.urlDraft = config.discordWebhookUrl();
		this.roleDraft = config.pingRole();
	}

	// --- mutation helpers: write local config AND forward to bot when in controller mode ---

	private void doSetEnabled(boolean v) {
		config.setDiscordEnabled(v);
		if (controller != null) controller.set("discord", v ? "on" : "off");
	}

	private void doSetWebhookUrl(String url) {
		config.setDiscordWebhookUrl(url);
		if (controller != null) controller.set("discordwebhook", url);
	}

	private void doSetUseEmbeds(boolean v) {
		config.setDiscordUseEmbeds(v);
		if (controller != null) controller.set("embeds", v ? "on" : "off");
	}

	private void doSetPingTarget(PingTarget pt) {
		config.setPingTarget(pt);
		if (controller != null) controller.set("pingtarget", pt.name().toLowerCase(java.util.Locale.ROOT));
	}

	private void doSetPingRole(String role) {
		config.setPingRole(role);
		if (controller != null) controller.set("pingrole", role);
	}

	private void doSetEventEnabled(DiscordEvent e, boolean v) {
		config.setDiscordEventEnabled(e, v);
		if (controller != null) controller.set("discordevent", e.key() + " send " + (v ? "on" : "off"));
	}

	private void doSetEventPing(DiscordEvent e, PingMode v) {
		config.setDiscordEventPing(e, v);
		if (controller != null) controller.set("discordevent", e.key() + " ping " + v.name().toLowerCase(java.util.Locale.ROOT));
	}

	private void doSetEventDetails(DiscordEvent e, boolean v) {
		config.setDiscordEventDetails(e, v);
		if (controller != null) controller.set("discordevent", e.key() + " details " + (v ? "on" : "off"));
	}

	private void doSetEventCoords(DiscordEvent e, boolean v) {
		config.setDiscordEventCoords(e, v);
		if (controller != null) controller.set("discordevent", e.key() + " coords " + (v ? "on" : "off"));
	}

	private void doSetEventDistance(DiscordEvent e, boolean v) {
		config.setDiscordEventDistance(e, v);
		if (controller != null) controller.set("discordevent", e.key() + " dist " + (v ? "on" : "off"));
	}

	@Override
	protected void init() {
		int fw = Math.min(470, width - 20);
		int x = width / 2 - fw / 2;
		int y = 30;

		// --- top band: enable, URL, test ------------------------------------
		addDrawableChild(ButtonWidget.builder(enabledLabel(), b -> {
			doSetEnabled(!config.discordEnabled());
			b.setMessage(enabledLabel());
		}).dimensions(x, y, fw, 20).build());
		y += 30;

		urlField = new TextFieldWidget(textRenderer, x, y, fw, 20, Text.literal("webhook url"));
		urlField.setMaxLength(300);
		urlField.setText(urlDraft);
		urlField.setChangedListener(s -> urlDraft = s);
		addDrawableChild(urlField);
		y += 26;

		addDrawableChild(ButtonWidget.builder(Text.literal("§aSave URL"), b -> {
			doSetWebhookUrl(urlDraft.trim());
			status = controller != null ? "§aURL sent to bot." : "§aURL saved.";
		}).dimensions(x, y, 90, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("§bTest"), b -> testWebhook())
				.dimensions(x + 94, y, 70, 20).build());
		addDrawableChild(ButtonWidget.builder(testPingLabel(), b -> {
			config.setDiscordTestPing(!config.discordTestPing());
			b.setMessage(testPingLabel());
		}).dimensions(x + 168, y, fw - 168, 20).build());
		y += 30;

		// --- message style --------------------------------------------------
		addDrawableChild(ButtonWidget.builder(embedsLabel(), b -> {
			doSetUseEmbeds(!config.discordUseEmbeds());
			b.setMessage(embedsLabel());
		}).dimensions(x, y, fw, 20).build());
		y += 30;

		// --- ping target (what a ping mentions) -----------------------------
		if (config.pingTarget() == PingTarget.ROLE) {
			addDrawableChild(ButtonWidget.builder(pingTargetLabel(), b -> {
				persistRoleDraft();
				doSetPingTarget(config.pingTarget().next());
				rebuild();
			}).dimensions(x, y, 220, 20).build());
			roleField = new TextFieldWidget(textRenderer, x + 226, y, fw - 226, 20, Text.literal("role id or name"));
			roleField.setMaxLength(100);
			roleField.setText(roleDraft);
			roleField.setChangedListener(s -> roleDraft = s);
			addDrawableChild(roleField);
		} else {
			roleField = null;
			addDrawableChild(ButtonWidget.builder(pingTargetLabel(), b -> {
				doSetPingTarget(config.pingTarget().next());
				rebuild();
			}).dimensions(x, y, fw, 20).build());
		}
		y += 30;

		// --- per-event list (paginated) -------------------------------------
		listTop = y;
		int bottomReserve = 64; // room for nav + status
		rowsPerPage = Math.max(1, (height - listTop - bottomReserve) / 22);
		int pages = (int) Math.ceil(events.length / (double) rowsPerPage);
		if (page >= pages) page = pages - 1;
		if (page < 0) page = 0;

		int start = page * rowsPerPage;
		int end = Math.min(events.length, start + rowsPerPage);
		int ry = listTop;
		for (int i = start; i < end; i++) {
			buildEventRow(x, ry, fw, events[i]);
			ry += 22;
		}

		// --- bottom nav -----------------------------------------------------
		int navY = height - 50;
		ButtonWidget prev = ButtonWidget.builder(Text.literal("◀ Prev"), b -> { page--; rebuild(); })
				.dimensions(x, navY, 70, 20).build();
		prev.active = page > 0;
		addDrawableChild(prev);
		ButtonWidget next = ButtonWidget.builder(Text.literal("Next ▶"), b -> { page++; rebuild(); })
				.dimensions(x + fw - 70, navY, 70, 20).build();
		next.active = page < pages - 1;
		addDrawableChild(next);

		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
				.dimensions(width / 2 - 50, height - 26, 100, 20).build());
	}

	/** A single event row: label + Send + @everyone + (Gear / XYZ / Dist where supported). */
	private void buildEventRow(int x, int y, int fw, DiscordEvent e) {
		int labelW = 140;
		int gap = 4;
		int avail = fw - labelW - gap;
		int colW = (avail - 4 * gap) / 5;

		ButtonWidget label = ButtonWidget.builder(Text.literal("§f" + e.label()), b -> {})
				.dimensions(x, y, labelW, 20).build();
		label.active = false;
		addDrawableChild(label);

		int bx = x + labelW + gap;

		// Send on/off
		addDrawableChild(ButtonWidget.builder(sendLabel(e), b -> {
			doSetEventEnabled(e, !config.discordEventEnabled(e));
			b.setMessage(sendLabel(e));
		}).dimensions(bx, y, colW, 20).build());
		bx += colW + gap;

		// @everyone ping mode (cycles off → outsiders → all; non-scoped skips outsiders)
		addDrawableChild(ButtonWidget.builder(pingLabel(e), b -> {
			doSetEventPing(e, config.discordEventPing(e).next(e.scoped()));
			b.setMessage(pingLabel(e));
		}).dimensions(bx, y, colW, 20).build());
		bx += colW + gap;

		// Gear (only for detailable events)
		if (e.detailable()) {
			addDrawableChild(ButtonWidget.builder(detailsLabel(e), b -> {
				doSetEventDetails(e, !config.discordEventDetails(e));
				b.setMessage(detailsLabel(e));
			}).dimensions(bx, y, colW, 20).build());
		} else {
			addNa(bx, y, colW);
		}
		bx += colW + gap;

		// Coords + Distance (only for locatable events)
		if (e.locatable()) {
			addDrawableChild(ButtonWidget.builder(coordsLabel(e), b -> {
				doSetEventCoords(e, !config.discordEventCoords(e));
				b.setMessage(coordsLabel(e));
			}).dimensions(bx, y, colW, 20).build());
			bx += colW + gap;
			addDrawableChild(ButtonWidget.builder(distanceLabel(e), b -> {
				doSetEventDistance(e, !config.discordEventDistance(e));
				b.setMessage(distanceLabel(e));
			}).dimensions(bx, y, colW, 20).build());
		} else {
			addNa(bx, y, colW);
			bx += colW + gap;
			addNa(bx, y, colW);
		}
	}

	/** A greyed-out placeholder so columns stay aligned when an extra doesn't apply. */
	private void addNa(int x, int y, int w) {
		ButtonWidget na = ButtonWidget.builder(Text.literal("§8—"), b -> {})
				.dimensions(x, y, w, 20).build();
		na.active = false;
		addDrawableChild(na);
	}

	private void testWebhook() {
		String url = urlDraft.trim();
		doSetWebhookUrl(url);
		if (!DiscordNotifier.isValidWebhook(url)) {
			status = "§cInvalid URL — must be a Discord webhook link.";
			return;
		}
		status = "§7Sending test…";
		// The HTTP result arrives off-thread; hop back onto the client thread to update UI.
		notifier.test(url, config.discordTestPing(), ok -> setStatus(
				ok ? "§aTest sent! Check your Discord channel." : "§cTest failed — check the URL/permissions."));
	}

	private void setStatus(String s) {
		MinecraftClient.getInstance().execute(() -> this.status = s);
	}

	private void rebuild() {
		persistRoleDraft();
		clearChildren();
		init();
	}

	/** Save the custom-role text (id or name) into the config if the field is showing. */
	private void persistRoleDraft() {
		if (roleField != null && roleDraft != null) doSetPingRole(roleDraft.trim());
	}

	private Text sendLabel(DiscordEvent e) {
		return Text.literal(config.discordEventEnabled(e) ? "§aSend" : "§cSend");
	}

	private Text pingLabel(DiscordEvent e) {
		return Text.literal(switch (config.discordEventPing(e)) {
			case OFF -> "§7@off";
			case OUTSIDERS -> "§e@out";
			case ALL -> "§a@all";
		});
	}

	private Text detailsLabel(DiscordEvent e) {
		return Text.literal(config.discordEventDetails(e) ? "§aGear" : "§8Gear");
	}

	private Text coordsLabel(DiscordEvent e) {
		return Text.literal(config.discordEventCoords(e) ? "§aXYZ" : "§8XYZ");
	}

	private Text distanceLabel(DiscordEvent e) {
		return Text.literal(config.discordEventDistance(e) ? "§aDist" : "§8Dist");
	}

	private Text enabledLabel() {
		return Text.literal("Discord webhook: " + (config.discordEnabled() ? "§aENABLED" : "§cDISABLED"));
	}

	private Text testPingLabel() {
		return Text.literal("Test ping: " + (config.discordTestPing() ? "§a@all" : "§7no"));
	}

	private Text embedsLabel() {
		return Text.literal("Message style: " + (config.discordUseEmbeds() ? "§bEmbed (named card)" : "§7Plain text"));
	}

	private Text pingTargetLabel() {
		return Text.literal("Ping target: " + switch (config.pingTarget()) {
			case EVERYONE -> "§c@everyone";
			case HERE -> "§e@here";
			case ROLE -> "§b@role";
		});
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		ctx.drawCenteredTextWithShadow(textRenderer,
				Text.literal("Discord webhook").formatted(Formatting.AQUA), width / 2, 14, 0xFFFFFF);
		ctx.drawTextWithShadow(textRenderer,
				Text.literal("§7Event                Send / @everyone / Gear / XYZ / Dist"),
				width / 2 - 230, listTop - 11, 0xAAAAAA);
		int pages = Math.max(1, (int) Math.ceil(events.length / (double) rowsPerPage));
		ctx.drawCenteredTextWithShadow(textRenderer,
				Text.literal("§7Page " + (page + 1) + "/" + pages), width / 2, height - 45, 0xAAAAAA);
		if (!status.isEmpty()) {
			ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2, height - 64, 0xFFFFFF);
		}
	}

	@Override
	public void close() {
		doSetWebhookUrl(urlDraft.trim());
		persistRoleDraft();
		if (client != null) client.setScreen(parent);
	}
}
