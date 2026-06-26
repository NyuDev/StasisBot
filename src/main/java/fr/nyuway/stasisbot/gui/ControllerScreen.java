package fr.nyuway.stasisbot.gui;

import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.service.ControllerService;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remote-control panel. Deliberately mirrors {@link StasisMonitorScreen} — same right-hand
 * settings column, same toggle styling, same bottom status line — so it feels like the
 * familiar bot menu, just driving a <em>remote</em> headless bot over the encrypted whisper
 * channel. The left column (where the monitor lists chambers) becomes the connection panel:
 * the bot's in-game name + the shared secret + connect/refresh. The "Bot Control…" button
 * opens the surveillance/security toggles that are specific to running the bot.
 */
public final class ControllerScreen extends Screen {

	/** The general settings, in the same order the bot monitor shows them. key → label. */
	private static final String[][] SETTINGS = {
			{"drop", "Drop pearl"},
			{"reopen", "Reopen trap"},
			{"return", "Return home"},
			{"death", "Return on death"},
			{"online", "Require online"},
			{"members", "Members ctrl"},
			{"debug", "Debug"},
			{"baritone", "Baritone"},
	};

	private final StasisBotConfig config;
	private final ControllerService controller;

	private TextFieldWidget botNameField;
	private TextFieldWidget secretField;
	private final Map<String, ButtonWidget> toggles = new LinkedHashMap<>();
	private ButtonWidget langButton;

	public ControllerScreen(StasisBotConfig config, ControllerService controller) {
		super(Text.literal("StasisBot — remote control"));
		this.config = config;
		this.controller = controller;
	}

	@Override
	protected void init() {
		toggles.clear();
		buildSettings();    // right column — identical layout to the bot monitor
		buildConnection();  // left column — connect to the remote bot
		refreshToggleLabels();
	}

	// --- right column: the same settings the bot monitor shows -----------------

	private void buildSettings() {
		int bw = 150;
		int x = width - bw - 20;
		int top = 28;
		int count = SETTINGS.length + 1; // + language
		int avail = height - top - 34;
		int step = Math.max(15, Math.min(22, avail / count));
		int bh = Math.min(20, step - 2);
		int y = top;

		for (String[] s : SETTINGS) {
			String key = s[0];
			String label = s[1];
			ButtonWidget b = ButtonWidget.builder(Text.literal(label + ": §7?"), btn -> {
				boolean next = !controller.flag(key, false);
				controller.set(key, next ? "on" : "off");
				btn.setMessage(toggleLabel(label, next));
			}).dimensions(x, y, bw, bh).build();
			addDrawableChild(b);
			toggles.put(key, b);
			y += step;
		}

		langButton = ButtonWidget.builder(Text.literal("Language: §7?"), btn -> {
			boolean fr = "fr".equalsIgnoreCase(controller.text("lang", config.language()));
			controller.set("lang", fr ? "en" : "fr");
		}).dimensions(x, y, bw, bh).build();
		addDrawableChild(langButton);
	}

	// --- left column: connection to the remote bot -----------------------------

	private void buildConnection() {
		int x = 20;
		int fw = 200;
		int y = 34;

		botNameField = new TextFieldWidget(textRenderer, x, y, fw, 18, Text.literal("bot name"));
		botNameField.setMaxLength(16);
		botNameField.setText(config.controlBotName());
		botNameField.setPlaceholder(Text.literal("§7bot account in-game name"));
		addDrawableChild(botNameField);

		secretField = new TextFieldWidget(textRenderer, x, y + 24, fw, 18, Text.literal("secret"));
		secretField.setMaxLength(128);
		secretField.setText("");
		secretField.setPlaceholder(Text.literal(config.controlSecret().isBlank()
				? "§7shared secret (set it)" : "§7shared secret (saved — blank to keep)"));
		addDrawableChild(secretField);

		addDrawableChild(ButtonWidget.builder(Text.literal("§aSave & Connect"), b -> saveAndConnect())
				.dimensions(x, y + 48, 120, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), b -> controller.refresh())
				.dimensions(x + 124, y + 48, 76, 20).build());

		addDrawableChild(ButtonWidget.builder(Text.literal("§dBot Control…"),
				b -> { if (client != null) client.setScreen(new BotControlScreen(this, config, controller)); })
				.dimensions(x, y + 74, fw, 18).build());

		addDrawableChild(ButtonWidget.builder(Text.literal("§7Switch to BOT mode (relaunch)"), b -> {
			config.setControllerMode(false);
		}).dimensions(x, y + 96, fw, 18).build());
	}

	private void saveAndConnect() {
		if (botNameField != null && !botNameField.getText().isBlank()) {
			config.setControlBotName(botNameField.getText().trim());
		}
		if (secretField != null && !secretField.getText().isBlank()) {
			config.setControlSecret(secretField.getText().trim());
			secretField.setText("");
		}
		controller.connect();
	}

	// --- live label refresh (no rebuild, so the text fields keep focus) --------

	@Override
	public void tick() {
		refreshToggleLabels();
	}

	private void refreshToggleLabels() {
		boolean synced = controller.status() == ControllerService.Status.SYNCED;
		for (String[] s : SETTINGS) {
			ButtonWidget b = toggles.get(s[0]);
			if (b == null) continue;
			b.setMessage(synced ? toggleLabel(s[1], controller.flag(s[0], false)) : Text.literal(s[1] + ": §7?"));
			b.active = synced;
		}
		if (langButton != null) {
			String lang = controller.text("lang", config.language());
			langButton.setMessage(Text.literal("Language: " + (synced ? lang.toUpperCase() : "§7?")));
			langButton.active = synced;
		}
	}

	private static Text toggleLabel(String label, boolean on) {
		return Text.literal(label + ": " + (on ? "§aON" : "§cOFF"));
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);

		ctx.drawTextWithShadow(textRenderer,
				Text.literal("Connection").formatted(Formatting.AQUA), 20, 16, 0xFFFFFF);
		ctx.drawTextWithShadow(textRenderer,
				Text.literal("Settings (remote)").formatted(Formatting.AQUA), width - 170, 16, 0xFFFFFF);

		String dot = switch (controller.status()) {
			case SYNCED -> "§a● synced";
			case CONNECTING -> "§e● connecting…";
			case ERROR -> "§c● error";
			case IDLE -> "§7● idle";
		};
		ctx.drawTextWithShadow(textRenderer, Text.literal(dot + "  §7" + controller.info()), 20, height - 26, 0xFFFFFF);
		ctx.drawTextWithShadow(textRenderer,
				Text.literal("§8bot: §7" + config.controlBotName() + " §8| encrypted over /msg — no port; both must be online"),
				20, height - 14, 0x888888);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
