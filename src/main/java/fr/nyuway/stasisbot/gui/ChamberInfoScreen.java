package fr.nyuway.stasisbot.gui;

import fr.nyuway.stasisbot.service.ControllerService;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Detail view for one of the bot's detected stasis chambers (opened by clicking it in the
 * remote panel). Shows the chamber's sign label, its trigger coordinates and pearl state —
 * this is the bot's view, so the coords are revealed here — and a button to <em>trigger</em>
 * (fire) that trap remotely over the control API.
 */
public final class ChamberInfoScreen extends Screen {

	private final Screen parent;
	private final ControllerService controller;
	private final ControllerService.RemoteChamber chamber;
	private long firedAt = 0L;

	public ChamberInfoScreen(Screen parent, ControllerService controller, ControllerService.RemoteChamber chamber) {
		super(Text.literal("StasisBot — chamber"));
		this.parent = parent;
		this.controller = controller;
		this.chamber = chamber;
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int y = height / 2 - 10;
		addDrawableChild(ButtonWidget.builder(Text.literal("§eTrigger trap"), b -> {
			int[] p = parsePos();
			if (p != null) { controller.fireChamber(p[0], p[1], p[2]); firedAt = System.currentTimeMillis(); }
		}).dimensions(cx - 110, y, 220, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("§7« Back"),
				b -> { if (client != null) client.setScreen(parent); })
				.dimensions(cx - 110, y + 26, 220, 20).build());
	}

	private int[] parsePos() {
		String[] p = chamber.pos().trim().split("\\s+");
		if (p.length != 3) return null;
		try {
			return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		int cx = width / 2;
		String stateStr = switch (chamber.state()) {
			case '0' -> "§cempty";
			case 'w' -> "§6wrong pearl";
			default -> "§aloaded";
		};
		ctx.drawCenteredTextWithShadow(textRenderer,
				Text.literal("Chamber: §f" + chamber.label()).formatted(Formatting.AQUA), cx, height / 2 - 60, 0xFFFFFFFF);
		ctx.drawCenteredTextWithShadow(textRenderer,
				Text.literal("§7coords: §f" + chamber.pos()), cx, height / 2 - 44, 0xFFFFFFFF);
		ctx.drawCenteredTextWithShadow(textRenderer,
				Text.literal("§7pearl: " + stateStr), cx, height / 2 - 32, 0xFFFFFFFF);
		if (System.currentTimeMillis() - firedAt < 2500L) {
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.literal("§asent trigger to the bot"), cx, height / 2 + 40, 0xFFFFFFFF);
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
