package fr.nyuway.stasisbot.gui;

import fr.nyuway.stasisbot.config.StasisBotConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Arrays;
import java.util.List;

/**
 * Edits (or creates) a single player → keywords mapping. Two text fields: the
 * player name and the comma-separated sign keywords. Saving writes through to
 * {@link StasisBotConfig}; renaming the player drops the old entry first.
 */
public final class AliasEditScreen extends Screen {

	private final Screen parent;
	private final StasisBotConfig config;
	private final String originalPlayer;
	private final String initialKeywords;

	private TextFieldWidget playerField;
	private TextFieldWidget keywordsField;

	public AliasEditScreen(Screen parent, StasisBotConfig config, String player, List<String> keywords) {
		super(Text.literal("StasisBot — edit mapping"));
		this.parent = parent;
		this.config = config;
		this.originalPlayer = player == null ? "" : player;
		this.initialKeywords = keywords == null ? "" : String.join(", ", keywords);
	}

	@Override
	protected void init() {
		int fw = 260;
		int x = width / 2 - fw / 2;
		int y = height / 2 - 30;

		playerField = new TextFieldWidget(textRenderer, x, y, fw, 20, Text.literal("player"));
		playerField.setMaxLength(32);
		playerField.setText(originalPlayer);
		addDrawableChild(playerField);

		keywordsField = new TextFieldWidget(textRenderer, x, y + 44, fw, 20, Text.literal("keywords"));
		keywordsField.setMaxLength(160);
		keywordsField.setText(initialKeywords);
		addDrawableChild(keywordsField);

		addDrawableChild(ButtonWidget.builder(Text.literal("§aSave"), b -> save())
				.dimensions(x, y + 80, fw / 2 - 2, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> close())
				.dimensions(x + fw / 2 + 2, y + 80, fw / 2 - 2, 20).build());

		setInitialFocus(playerField);
	}

	private void save() {
		String player = playerField.getText().trim();
		if (player.isEmpty()) { // nothing to key on — treat as cancel
			close();
			return;
		}
		List<String> keywords = Arrays.stream(keywordsField.getText().split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.toList();
		// If the player name was changed, drop the stale entry under the old name.
		if (!originalPlayer.isBlank() && !originalPlayer.equalsIgnoreCase(player)) {
			config.removeAlias(originalPlayer);
		}
		config.putAlias(player, keywords);
		close();
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		int x = width / 2 - 130;
		int y = height / 2 - 30;
		ctx.drawCenteredTextWithShadow(textRenderer,
				Text.literal(originalPlayer.isBlank() ? "New mapping" : "Edit mapping").formatted(Formatting.AQUA),
				width / 2, y - 44, 0xFFFFFF);
		ctx.drawTextWithShadow(textRenderer, Text.literal("§7Player name"), x, y - 12, 0xFFFFFF);
		ctx.drawTextWithShadow(textRenderer,
				Text.literal("§7Sign keywords (comma-separated)"), x, y + 32, 0xFFFFFF);
	}

	@Override
	public void close() {
		if (client != null) client.setScreen(parent);
	}
}
