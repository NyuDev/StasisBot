package fr.nyuway.stasisbot.gui;

import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.service.ControllerService;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "Bot Control" sub-panel: the settings that are specific to running the bot — base-member
 * gating, presence skipping, Discord notifications and the surveillance relay — as opposed
 * to the general toggles on the main remote panel. Each toggle drives the remote bot live
 * over the control channel; the labels reflect the bot's last synced state.
 */
public final class BotControlScreen extends Screen {

	private static final String[][] CONTROLS = {
			{"reqmember", "Require base member"},
			{"skip", "Skip if present"},
			{"discord", "Discord notifications"},
			{"embeds", "Discord embeds"},
			{"alert", "Outsider siren alert"},
			{"chatlog", "Chat relay"},
			{"appendchars", "Anti-spam chars"},
	};

	private final Screen parent;
	private final StasisBotConfig config;
	private final ControllerService controller;
	private final Map<String, ButtonWidget> toggles = new LinkedHashMap<>();

	public BotControlScreen(Screen parent, StasisBotConfig config, ControllerService controller) {
		super(Text.literal("StasisBot — bot control"));
		this.parent = parent;
		this.config = config;
		this.controller = controller;
	}

	@Override
	protected void init() {
		toggles.clear();
		int bw = 220;
		int x = width / 2 - bw / 2;
		int top = 40;
		int step = 24;
		int y = top;

		for (String[] c : CONTROLS) {
			String key = c[0];
			String label = c[1];
			ButtonWidget b = ButtonWidget.builder(Text.literal(label + ": §7?"), btn -> {
				boolean next = !controller.flag(key, false);
				controller.set(key, next ? "on" : "off");
				btn.setMessage(toggleLabel(label, next));
			}).dimensions(x, y, bw, 20).build();
			addDrawableChild(b);
			toggles.put(key, b);
			y += step;
		}

		addDrawableChild(ButtonWidget.builder(Text.literal("Refresh from bot"), b -> controller.refresh())
				.dimensions(x, y + 6, bw, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("§7« Back"),
				b -> { if (client != null) client.setScreen(parent); })
				.dimensions(x, y + 30, bw, 20).build());

		refreshLabels();
	}

	@Override
	public void tick() {
		refreshLabels();
	}

	private void refreshLabels() {
		boolean synced = controller.status() == ControllerService.Status.SYNCED;
		for (String[] c : CONTROLS) {
			ButtonWidget b = toggles.get(c[0]);
			if (b == null) continue;
			b.setMessage(synced ? toggleLabel(c[1], controller.flag(c[0], false)) : Text.literal(c[1] + ": §7?"));
			b.active = synced;
		}
	}

	private static Text toggleLabel(String label, boolean on) {
		return Text.literal(label + ": " + (on ? "§aON" : "§cOFF"));
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		ctx.drawCenteredTextWithShadow(textRenderer,
				Text.literal("Bot control — surveillance & security").formatted(Formatting.AQUA),
				width / 2, 18, 0xFFFFFF);
		boolean synced = controller.status() == ControllerService.Status.SYNCED;
		ctx.drawCenteredTextWithShadow(textRenderer,
				Text.literal(synced ? "§a● synced — changes apply live" : "§e● connect on the main panel first"),
				width / 2, height - 16, 0xFFFFFF);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
