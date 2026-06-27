package fr.nyuway.stasisbot.gui;

import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.service.ControllerService;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * A scrolling view of the bot's own log lines (timestamped), fetched from the remote bot's
 * {@code LogTap} over the control API — "what happened, when". Read-only; auto-refreshes.
 */
public final class BotLogsScreen extends Screen {

	private final Screen parent;
	private final StasisBotConfig config;
	private final ControllerService controller;
	private long lastPoll = 0L;

	public BotLogsScreen(Screen parent, StasisBotConfig config, ControllerService controller) {
		super(Text.literal("StasisBot — bot logs"));
		this.parent = parent;
		this.config = config;
		this.controller = controller;
	}

	@Override
	protected void init() {
		addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), b -> controller.requestLogs())
				.dimensions(width - 184, height - 28, 84, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("§7« Back"), b -> { if (client != null) client.setScreen(parent); })
				.dimensions(width - 94, height - 28, 84, 20).build());
		controller.requestLogs();
		lastPoll = System.currentTimeMillis();
	}

	@Override
	public void tick() {
		long now = System.currentTimeMillis();
		if (now - lastPoll > 2000L) {
			lastPoll = now;
			controller.requestLogs();
		}
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		ctx.drawTextWithShadow(textRenderer, Text.literal("Bot logs").formatted(Formatting.AQUA), 12, 14, 0xFFFFFF);

		String log = controller.logs();
		int top = 30;
		int bottom = height - 36;
		int rows = Math.max(0, (bottom - top) / 10);
		if (log != null && !log.isBlank()) {
			String[] lines = log.split("\n");
			int start = Math.max(0, lines.length - rows);
			int yy = top;
			int maxW = width - 24;
			for (int i = start; i < lines.length; i++) {
				String line = lines[i];
				if (textRenderer.getWidth(line) > maxW) line = textRenderer.trimToWidth(line, maxW);
				ctx.drawTextWithShadow(textRenderer, Text.literal(line), 12, yy, 0xCCCCCC);
				yy += 10;
			}
		} else {
			ctx.drawTextWithShadow(textRenderer,
					Text.literal(controller.status() == ControllerService.Status.SYNCED ? "§7(no logs yet)" : "§7(connect on the main panel first)"),
					12, top, 0xAAAAAA);
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
