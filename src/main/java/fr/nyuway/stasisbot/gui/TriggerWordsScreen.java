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
 * Edits the list of chat trigger words. They're shown (and entered) as a single
 * comma-separated field — the bot fires a home request when a chat line contains
 * any of them. Keeping several keywords lets the master vary the wording so a
 * server's anti-spam filter doesn't swallow repeated identical messages.
 */
public final class TriggerWordsScreen extends Screen {

	private final Screen parent;
	private final StasisBotConfig config;
	private TextFieldWidget field;

	public TriggerWordsScreen(Screen parent, StasisBotConfig config) {
		super(Text.literal("StasisBot — trigger words"));
		this.parent = parent;
		this.config = config;
	}

	@Override
	protected void init() {
		int fw = 300;
		int x = width / 2 - fw / 2;
		int y = height / 2 - 10;

		field = new TextFieldWidget(textRenderer, x, y, fw, 20, Text.literal("trigger words"));
		field.setMaxLength(256);
		field.setText(String.join(", ", config.triggerWords()));
		addDrawableChild(field);

		addDrawableChild(ButtonWidget.builder(Text.literal("§aSave"), b -> save())
				.dimensions(x, y + 40, fw / 2 - 2, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> close())
				.dimensions(x + fw / 2 + 2, y + 40, fw / 2 - 2, 20).build());

		setInitialFocus(field);
	}

	private void save() {
		List<String> words = Arrays.stream(field.getText().split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.toList();
		config.setTriggerWords(words);
		close();
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		int y = height / 2 - 10;
		ctx.drawCenteredTextWithShadow(textRenderer,
				Text.literal("Trigger words").formatted(Formatting.AQUA), width / 2, y - 50, 0xFFFFFF);
		ctx.drawCenteredTextWithShadow(textRenderer,
				Text.literal("§7Comma-separated. The bot reacts to any chat line containing one of them."),
				width / 2, y - 28, 0xAAAAAA);
	}

	@Override
	public void close() {
		if (client != null) client.setScreen(parent);
	}
}
