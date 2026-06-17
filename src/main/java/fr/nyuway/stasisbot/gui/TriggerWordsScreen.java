package fr.nyuway.stasisbot.gui;

import fr.nyuway.stasisbot.config.StasisBotConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * Edits the chat trigger words. A keyword fires a home request only when it's the
 * FIRST token of the message (extra gibberish after a space is allowed so the
 * master can dodge a server's anti-spam filter). Words are managed one by one:
 * type a keyword and press Add, or click a word's ✕ to drop it.
 */
public final class TriggerWordsScreen extends Screen {

	private final Screen parent;
	private final StasisBotConfig config;
	private TextFieldWidget field;
	private String draft = "";

	public TriggerWordsScreen(Screen parent, StasisBotConfig config) {
		super(Text.literal("StasisBot — trigger words"));
		this.parent = parent;
		this.config = config;
	}

	@Override
	protected void init() {
		int fw = 260;
		int addW = 60;
		int gap = 4;
		int rowW = fw + gap + addW;
		int x = width / 2 - rowW / 2;
		int top = 56;

		field = new TextFieldWidget(textRenderer, x, top, fw, 20, Text.literal("new keyword"));
		field.setMaxLength(48);
		field.setText(draft);
		field.setChangedListener(s -> draft = s);
		addDrawableChild(field);

		addDrawableChild(ButtonWidget.builder(Text.literal("§aAdd"), b -> addWord())
				.dimensions(x + fw + gap, top, addW, 20).build());

		// One row per current keyword: the word + a ✕ remove button.
		List<String> words = config.triggerWords();
		int ly = top + 30;
		int rmW = 22;
		int wordW = rowW - rmW - gap;
		int bottomLimit = height - 40;
		for (String w : words) {
			if (ly > bottomLimit) break;
			final String word = w;
			ButtonWidget label = ButtonWidget.builder(Text.literal("§f" + word), b -> {})
					.dimensions(x, ly, wordW, 20).build();
			label.active = false;
			addDrawableChild(label);
			addDrawableChild(ButtonWidget.builder(Text.literal("§c\u2715"), b -> removeWord(word))
					.dimensions(x + wordW + gap, ly, rmW, 20).build());
			ly += 22;
		}

		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
				.dimensions(width / 2 - 50, height - 28, 100, 20).build());

		setInitialFocus(field);
	}

	private void addWord() {
		String w = field.getText().trim();
		if (!w.isEmpty()) {
			config.addTriggerWord(w);
		}
		draft = "";
		rebuild();
	}

	private void removeWord(String word) {
		config.removeTriggerWord(word);
		rebuild();
	}

	private void rebuild() {
		clearChildren();
		init();
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		ctx.drawCenteredTextWithShadow(textRenderer,
				Text.literal("Trigger words").formatted(Formatting.AQUA), width / 2, 20, 0xFFFFFF);
		ctx.drawCenteredTextWithShadow(textRenderer,
				Text.literal("§7Keyword must be the FIRST word of the message. Click ✕ to remove."),
				width / 2, 36, 0xAAAAAA);
	}

	@Override
	public void close() {
		if (client != null) client.setScreen(parent);
	}
}
