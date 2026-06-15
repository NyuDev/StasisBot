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

import java.util.ArrayList;
import java.util.List;

/**
 * Status + quick-config panel (opened with the keybind). The left column lists
 * the stasis chambers the bot currently detects — click one to map it to a
 * player. The right column flips the common toggles and opens the mapping
 * editor. Every change persists to disk immediately via the config setters.
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
		buildToggles();
		buildChamberButtons();
	}

	// --- right column: toggles & actions ------------------------------------

	private void buildToggles() {
		int bw = 150;
		int bh = 20;
		int x = width - bw - 20;
		int y = 30;
		int step = 22;

		addToggle(x, y, bw, bh, "Drop pearl", config::dropPearlForPlayer, config::setDropPearlForPlayer);
		addToggle(x, y += step, bw, bh, "Reopen trap", config::reopenTrigger, config::setReopenTrigger);
		addToggle(x, y += step, bw, bh, "Return home", config::returnHome, config::setReturnHome);
		addToggle(x, y += step, bw, bh, "Require online", config::requireOnline, config::setRequireOnline);
		addToggle(x, y += step, bw, bh, "Debug", config::debug, config::setDebug);
		addMovementToggle(x, y += step, bw, bh);

		addDrawableChild(ButtonWidget.builder(langLabel(), b -> {
			config.setLanguage(config.language().equals("fr") ? "en" : "fr");
			b.setMessage(langLabel());
		}).dimensions(x, y += step, bw, bh).build());

		addDrawableChild(ButtonWidget.builder(Text.literal("§bManage mappings…"),
				b -> { if (client != null) client.setScreen(new AliasListScreen(this, config)); })
				.dimensions(x, y += step, bw, bh).build());

		addDrawableChild(ButtonWidget.builder(Text.literal("Rescan"), b -> {
			index.invalidate();
			clearChildren();
			init();
		}).dimensions(x, y + step, bw, bh).build());
	}

	// --- left column: detected chambers (clickable) -------------------------

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
			String label = (pearl ? "§a\u25CF " : "§c\u25CB ") + "§f" + c.label()
					+ " §7" + c.trigger().toShortString();
			List<String> keywords = new ArrayList<>(c.signTokens());
			addDrawableChild(ButtonWidget.builder(Text.literal(label),
					b -> { if (client != null) client.setScreen(new AliasEditScreen(this, config, "", keywords)); })
					.dimensions(x, y, bw, bh).build());
			y += bh + 2;
		}
	}

	private List<StasisChamber> currentChambers() {
		boolean inWorld = client != null && client.world != null && client.player != null;
		return inWorld ? index.chambers(client.world, client.player.getBlockPos()) : List.of();
	}

	// --- toggle helpers -----------------------------------------------------

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

		ctx.drawTextWithShadow(textRenderer,
				Text.literal("Detected chambers").formatted(Formatting.AQUA), 20, 16, 0xFFFFFF);
		ctx.drawTextWithShadow(textRenderer,
				Text.literal("Settings").formatted(Formatting.AQUA), width - 170, 16, 0xFFFFFF);

		if (currentChambers().isEmpty()) {
			ctx.drawTextWithShadow(textRenderer,
					Text.literal("§7None nearby — park the bot next to your stasis signs."),
					20, 34, 0xAAAAAA);
		} else {
			ctx.drawTextWithShadow(textRenderer,
					Text.literal("§8click a chamber to map it to a player"),
					20, height - 14, 0x888888);
		}

		String master = config.master().isBlank() ? "(none)" : config.master();
		ctx.drawTextWithShadow(textRenderer,
				Text.literal("§7master: §f" + master + "  §7| DM: §f" + config.commandPrefix() + " help"),
				width - 320, height - 14, 0xFFFFFF);
	}
}
