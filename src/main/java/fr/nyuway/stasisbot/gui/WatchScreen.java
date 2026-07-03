package fr.nyuway.stasisbot.gui;

import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.service.ControllerService;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.Locale;

/**
 * GUI for managing the global watch list. Watched players' chat lines,
 * server joins and leaves are forwarded to Discord by {@link
 * fr.nyuway.stasisbot.service.SurveillanceService}.
 *
 * <p>Works in both modes:
 * <ul>
 *   <li>Bot mode: reads and writes {@link StasisBotConfig} directly.</li>
 *   <li>Controller mode: reads from the local config (synced from the bot's
 *       snapshot on connect) and forwards every change to the bot via the
 *       HTTP control API ({@code watch add} / {@code unwatch}).</li>
 * </ul>
 */
public final class WatchScreen extends Screen {

	private final Screen parent;
	private final StasisBotConfig config;
	/** Non-null in controller mode: mutations are sent to the bot via the control API. */
	private final ControllerService controller;

	private TextFieldWidget field;
	private String draft = "";
	private String status = "";

	/** Bot-mode constructor (no remote). */
	public WatchScreen(Screen parent, StasisBotConfig config) {
		this(parent, config, null);
	}

	/** Controller-mode constructor: every mutation is forwarded to the bot. */
	public WatchScreen(Screen parent, StasisBotConfig config, ControllerService controller) {
		super(Text.literal("StasisBot — watch list"));
		this.parent = parent;
		this.config = config;
		this.controller = controller;
	}

	@Override
	protected void init() {
		int addW = 60;
		int gap = 4;
		int fw = Math.min(300, width - addW - gap - 40);
		int rowW = fw + gap + addW;
		int x = width / 2 - rowW / 2;
		int top = 56;

		// --- name entry + Add ---------------------------------------------------
		field = new TextFieldWidget(textRenderer, x, top, fw, 20, Text.literal("player name"));
		field.setMaxLength(16);
		field.setText(draft);
		field.setChangedListener(s -> draft = s);
		addDrawableChild(field);

		addDrawableChild(ButtonWidget.builder(Text.literal("§aAdd"), b -> doAdd(draft.trim()))
				.dimensions(x + fw + gap, top, addW, 20).build());

		// --- watched list -------------------------------------------------------
		List<String> watched = config.watchedPlayers();
		int rmW = 66;
		int labelW = rowW - rmW - gap;
		int ly = top + 30;
		int bottomLimit = height - 50;
		for (String name : watched) {
			if (ly > bottomLimit) break;
			final String n = name;
			ButtonWidget label = ButtonWidget.builder(Text.literal("§f" + name), b -> {})
					.dimensions(x, ly, labelW, 20).build();
			label.active = false;
			addDrawableChild(label);
			addDrawableChild(ButtonWidget.builder(Text.literal("§cRemove"), b -> doRemove(n))
					.dimensions(x + labelW + gap, ly, rmW, 20).build());
			ly += 22;
		}

		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
				.dimensions(width / 2 - 50, height - 28, 100, 20).build());

		setInitialFocus(field);
	}

	private void doAdd(String name) {
		if (name.isEmpty()) { status = "§cEnter a player name."; return; }
		if (!StasisBotConfig.isValidMinecraftName(name)) { status = "§cInvalid name (2–16 alphanumeric / _)."; return; }
		boolean added = config.addWatchedPlayer(name);
		if (!added) { status = "§e" + name + " is already on the list."; return; }
		if (controller != null) controller.set("watch", "add " + name.toLowerCase(Locale.ROOT));
		status = "§aWatching " + name + ".";
		draft = "";
		rebuild();
	}

	private void doRemove(String name) {
		config.removeWatchedPlayer(name);
		if (controller != null) controller.set("unwatch", name.toLowerCase(Locale.ROOT));
		status = "§7Removed " + name + ".";
		rebuild();
	}

	private void rebuild() {
		clearChildren();
		init();
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		ctx.drawCenteredTextWithShadow(textRenderer,
				Text.literal("Watch list").formatted(Formatting.AQUA), width / 2, 18, 0xFFFFFF);
		ctx.drawCenteredTextWithShadow(textRenderer,
				Text.literal("§7Chat, joins and leaves from these players go to Discord."),
				width / 2, 30, 0xAAAAAA);
		if (config.watchedPlayers().isEmpty()) {
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.literal("§7(nobody watched)"), width / 2, 96, 0xAAAAAA);
		}
		if (!status.isEmpty()) {
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.literal(status), width / 2, height - 42, 0xFFFFFF);
		}
		if (controller != null) {
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.literal("§8Changes are sent to the bot."), width / 2, height - 14, 0x888888);
		}
	}

	@Override
	public void close() {
		if (client != null) client.setScreen(parent);
	}
}
