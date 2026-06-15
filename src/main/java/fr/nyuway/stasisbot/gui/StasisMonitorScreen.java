package fr.nyuway.stasisbot.gui;

import fr.nyuway.stasisbot.activation.BaritoneSupport;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.entity.PearlDetector;
import fr.nyuway.stasisbot.model.StasisChamber;
import fr.nyuway.stasisbot.scan.ChamberIndex;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * Status + quick-config panel (opened with the keybind). Shows what the bot
 * currently detects and lets you flip the common toggles — drop pearl, reopen
 * trap, return home, language — without typing any command. Each toggle persists
 * to disk immediately via the config setters.
 */
public final class StasisMonitorScreen extends Screen {

	private final StasisBotConfig config;
	private final ChamberIndex index;
	private final PearlDetector pearls;

	public StasisMonitorScreen(StasisBotConfig config, ChamberIndex index, PearlDetector pearls) {
		super(Text.literal("StasisBot — monitor"));
		this.config = config;
		this.index = index;
		this.pearls = pearls;
	}

	@Override
	protected void init() {
		int bw = 150;
		int bh = 20;
		int x = width / 2 - bw / 2;
		int y = height - 180;

		addToggle(x, y, bw, bh, "Drop pearl", config::dropPearlForPlayer, config::setDropPearlForPlayer);
		addToggle(x, y + 24, bw, bh, "Reopen trap", config::reopenTrigger, config::setReopenTrigger);
		addToggle(x, y + 48, bw, bh, "Return home", config::returnHome, config::setReturnHome);
		addToggle(x, y + 72, bw, bh, "Require online", config::requireOnline, config::setRequireOnline);
		addToggle(x, y + 96, bw, bh, "Debug", config::debug, config::setDebug);
		addMovementToggle(x, y + 120, bw, bh);

		addDrawableChild(ButtonWidget.builder(langLabel(), b -> {
			config.setLanguage(config.language().equals("fr") ? "en" : "fr");
			b.setMessage(langLabel());
		}).dimensions(x, y + 144, bw, bh).build());

		addDrawableChild(ButtonWidget.builder(Text.literal("Rescan"), b -> index.invalidate())
				.dimensions(x, y + 168, bw, bh).build());
	}

	/**
	 * Toggle between Baritone pathfinding and the built-in primitive walker. When
	 * Baritone isn't installed there's nothing to choose, so the button is shown
	 * greyed-out and locked on "Simple".
	 */
	private void addMovementToggle(int x, int y, int w, int h) {
		boolean baritone = BaritoneSupport.isAvailable();
		ButtonWidget button = ButtonWidget.builder(movementLabel(baritone), b -> {
			if (!BaritoneSupport.isAvailable()) return; // locked when Baritone is absent
			config.setUseBaritone(!config.useBaritone());
			b.setMessage(movementLabel(true));
		}).dimensions(x, y, w, h).build();
		button.active = baritone; // greyed + unclickable without Baritone
		addDrawableChild(button);
	}

	private Text movementLabel(boolean baritoneAvailable) {
		if (!baritoneAvailable) {
			return Text.literal("Movement: §7Simple (no Baritone)");
		}
		boolean on = config.useBaritone();
		return Text.literal("Movement: " + (on ? "§aBaritone" : "§eSimple"));
	}

	private void addToggle(int x, int y, int w, int h, String label,
	                       java.util.function.BooleanSupplier getter,
	                       java.util.function.Consumer<Boolean> setter) {
		addDrawableChild(ButtonWidget.builder(toggleLabel(label, getter.getAsBoolean()), b -> {
			boolean next = !getter.getAsBoolean();
			setter.accept(next);
			b.setMessage(toggleLabel(label, next));
		}).dimensions(x, y, w, h).build());
	}

	private static Text toggleLabel(String label, boolean on) {
		return Text.literal(label + ": " + (on ? "§aON" : "§cOFF"));
	}

	private Text langLabel() {
		return Text.literal("Language: " + config.language().toUpperCase());
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);

		ctx.drawCenteredTextWithShadow(textRenderer,
				Text.literal("Detected stasis chambers").formatted(Formatting.AQUA), width / 2, 12, 0xFFFFFF);

		boolean inWorld = client != null && client.world != null && client.player != null;
		List<StasisChamber> chambers = inWorld
				? index.chambers(client.world, client.player.getBlockPos())
				: List.of();

		if (chambers.isEmpty()) {
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.literal("None nearby — park the bot next to your stasis signs."),
					width / 2, 36, 0xAAAAAA);
		} else {
			int x = width / 2 - 170;
			int y = 32;
			for (StasisChamber c : chambers) {
				boolean pearl = pearls.hasOwnPearl(client.world, c, chambers);
				String row = (pearl ? "§a● " : "§c○ ") + "§f" + c.label()
						+ "  §7" + c.trigger().toShortString()
						+ (pearl ? "  §a[pearl]" : "  §c[no pearl]");
				ctx.drawTextWithShadow(textRenderer, Text.literal(row), x, y, 0xFFFFFF);
				y += 12;
				if (y > height - 150) break;
			}
		}

		String master = config.master().isBlank() ? "(none)" : config.master();
		ctx.drawCenteredTextWithShadow(textRenderer,
				Text.literal("§7master: §f" + master + "  §7| DM in-game: §f" + config.commandPrefix() + " help"),
				width / 2, height - 14, 0xFFFFFF);
	}
}
