package fr.nyuway.stasisbot.gui;

import fr.nyuway.stasisbot.config.StasisBotConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lists every player → keywords mapping and lets you add, edit or remove them
 * in-game. Each row opens an {@link AliasEditScreen}; the ✖ button deletes.
 * Long lists scroll with the mouse wheel.
 */
public final class AliasListScreen extends Screen {

	private static final int VISIBLE_ROWS = 8;

	private final Screen parent;
	private final StasisBotConfig config;
	private int scroll = 0;

	public AliasListScreen(Screen parent, StasisBotConfig config) {
		super(Text.literal("StasisBot — mappings"));
		this.parent = parent;
		this.config = config;
	}

	@Override
	protected void init() {
		rebuild();
	}

	/** Rebuilds the row widgets for the current scroll offset. */
	private void rebuild() {
		clearChildren();

		List<Map.Entry<String, List<String>>> entries = new ArrayList<>(config.aliases().entrySet());
		int total = entries.size();
		int maxScroll = Math.max(0, total - VISIBLE_ROWS);
		if (scroll > maxScroll) scroll = maxScroll;
		if (scroll < 0) scroll = 0;

		int rowW = 280;
		int x = width / 2 - rowW / 2;
		int y = 44;
		int rowH = 20;

		for (int i = scroll; i < Math.min(total, scroll + VISIBLE_ROWS); i++) {
			Map.Entry<String, List<String>> e = entries.get(i);
			String player = e.getKey();
			List<String> keywords = e.getValue();
			String kws = String.join(", ", keywords);

			addDrawableChild(ButtonWidget.builder(
					Text.literal("§f" + player + " §7→ §a" + kws),
					b -> openEdit(player, keywords)
			).dimensions(x, y, rowW - 24, rowH).build());

			addDrawableChild(ButtonWidget.builder(Text.literal("§c\u2716"),
					b -> { config.removeAlias(player); rebuild(); }
			).dimensions(x + rowW - 22, y, 22, rowH).build());

			y += rowH + 2;
		}

		int by = height - 30;
		addDrawableChild(ButtonWidget.builder(Text.literal("§a+ Add mapping"), b -> openEdit("", List.of()))
				.dimensions(width / 2 - 154, by, 150, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
				.dimensions(width / 2 + 4, by, 150, 20).build());
	}

	private void openEdit(String player, List<String> keywords) {
		if (client != null) client.setScreen(new AliasEditScreen(this, config, player, keywords));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (verticalAmount != 0 && config.aliases().size() > VISIBLE_ROWS) {
			scroll -= (int) Math.signum(verticalAmount);
			rebuild();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		ctx.drawCenteredTextWithShadow(textRenderer,
				Text.literal("Player → sign keywords").formatted(Formatting.AQUA), width / 2, 20, 0xFFFFFF);
		if (config.aliases().isEmpty()) {
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.literal("§7No mappings yet — players always match their own name."),
					width / 2, height / 2, 0xAAAAAA);
		}
	}

	@Override
	public void close() {
		if (client != null) client.setScreen(parent);
	}
}
