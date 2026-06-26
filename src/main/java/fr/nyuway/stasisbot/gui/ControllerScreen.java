package fr.nyuway.stasisbot.gui;

import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.service.ControllerService;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Remote-control panel (controller mode). Connects to the headless bot over the
 * encrypted whisper channel and lets the operator flip the bot's settings live.
 * The left column holds the connection (bot name + shared secret + Connect); the
 * right columns mirror the bot's toggles, each pushing a change the moment it's
 * clicked. Nothing here ever shows the bot's coordinates.
 */
public final class ControllerScreen extends Screen {

	/** key, label, default — the boolean settings the bot exposes in its STATE snapshot. */
	private static final String[][] TOGGLES = {
			{"drop", "Drop pearl"},
			{"reopen", "Reopen trap"},
			{"return", "Return home"},
			{"death", "Return on death"},
			{"online", "Require online"},
			{"walk", "Auto-walk"},
			{"members", "Members ctrl"},
			{"reqmember", "Req. base member"},
			{"skip", "Skip if present"},
			{"debug", "Debug"},
			{"baritone", "Baritone"},
			{"discord", "Discord"},
			{"embeds", "Discord embeds"},
			{"alert", "Outsider alert"},
			{"chatlog", "Chat relay"},
			{"appendchars", "Anti-spam chars"},
	};

	private final StasisBotConfig config;
	private final ControllerService controller;

	private TextFieldWidget botNameField;
	private TextFieldWidget secretField;
	private String lastSignature = "";

	public ControllerScreen(StasisBotConfig config, ControllerService controller) {
		super(Text.literal("StasisBot — remote control"));
		this.config = config;
		this.controller = controller;
	}

	@Override
	protected void init() {
		int x = 20;
		int y = 34;
		int fw = 200;

		botNameField = new TextFieldWidget(textRenderer, x, y, fw, 18, Text.literal("bot name"));
		botNameField.setMaxLength(16);
		botNameField.setText(config.controlBotName());
		botNameField.setPlaceholder(Text.literal("§7bot account in-game name"));
		addDrawableChild(botNameField);

		secretField = new TextFieldWidget(textRenderer, x, y + 24, fw, 18, Text.literal("secret"));
		secretField.setMaxLength(128);
		secretField.setText(""); // never echo the stored secret
		secretField.setPlaceholder(Text.literal(config.controlSecret().isBlank()
				? "§7shared secret (set it)" : "§7shared secret (saved — leave blank to keep)"));
		addDrawableChild(secretField);

		addDrawableChild(ButtonWidget.builder(Text.literal("§aSave & Connect"), b -> saveAndConnect())
				.dimensions(x, y + 48, 98, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), b -> controller.refresh())
				.dimensions(x + 102, y + 48, 98, 20).build());

		addDrawableChild(ButtonWidget.builder(Text.literal("§7Switch to BOT mode"), b -> {
			config.setControllerMode(false);
			controller.tick(); // harmless; real switch needs a relaunch (see status line)
		}).dimensions(x, y + 72, fw, 18).build());

		// Right-hand toggle grid (two columns), reflecting the last synced state.
		boolean synced = controller.status() == ControllerService.Status.SYNCED;
		int gx = width - 330;
		int colW = 150;
		int gy = 34;
		int step = 21;
		for (int i = 0; i < TOGGLES.length; i++) {
			String key = TOGGLES[i][0];
			String label = TOGGLES[i][1];
			int col = i % 2;
			int row = i / 2;
			int bx = gx + col * (colW + 6);
			int by = gy + row * step;
			boolean on = controller.flag(key, false);
			ButtonWidget btn = ButtonWidget.builder(toggleLabel(label, on, synced), b -> {
				boolean next = !controller.flag(key, false);
				controller.set(key, next ? "on" : "off");
				b.setMessage(toggleLabel(label, next, true));
			}).dimensions(bx, by, colW, 19).build();
			btn.active = synced; // can't toggle until we've synced the real values
			addDrawableChild(btn);
		}

		// Language flip (also gated on sync).
		int langY = gy + ((TOGGLES.length + 1) / 2) * step;
		boolean fr = "fr".equalsIgnoreCase(controller.text("lang", config.language()));
		ButtonWidget lang = ButtonWidget.builder(Text.literal("Language: " + (fr ? "FR" : "EN")), b -> {
			controller.set("lang", fr ? "en" : "fr");
		}).dimensions(gx, langY, colW, 19).build();
		lang.active = synced;
		addDrawableChild(lang);

		lastSignature = controller.signature();
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
		rebuildUi();
	}

	private void rebuildUi() {
		clearChildren();
		init();
	}

	@Override
	public void tick() {
		// Re-init when the synced state changes, so toggle labels reflect the bot's real values.
		String sig = controller.signature();
		if (!sig.equals(lastSignature)) {
			lastSignature = sig;
			rebuildUi();
		}
	}

	private static Text toggleLabel(String label, boolean on, boolean synced) {
		if (!synced) return Text.literal(label + ": §7?");
		return Text.literal(label + ": " + (on ? "§aON" : "§cOFF"));
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);

		ctx.drawTextWithShadow(textRenderer,
				Text.literal("Connection").formatted(Formatting.AQUA), 20, 18, 0xFFFFFF);
		ctx.drawTextWithShadow(textRenderer,
				Text.literal("Bot settings (live)").formatted(Formatting.AQUA), width - 330, 18, 0xFFFFFF);

		// Status line.
		String s = switch (controller.status()) {
			case SYNCED -> "§a● synced";
			case CONNECTING -> "§e● connecting…";
			case ERROR -> "§c● error";
			case IDLE -> "§7● idle";
		};
		ctx.drawTextWithShadow(textRenderer, Text.literal(s + " §7" + controller.info()), 20, height - 26, 0xFFFFFF);
		ctx.drawTextWithShadow(textRenderer,
				Text.literal("§8encrypted over /msg — both you and the bot must be online; mode change needs a relaunch"),
				20, height - 14, 0x888888);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
