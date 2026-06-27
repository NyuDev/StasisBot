package fr.nyuway.stasisbot.gui;

import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.service.ControllerService;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

/**
 * Live "bot control" panel (opened from the remote menu). Drives the running bot over the
 * control API: a real-time feed of its chat with the ability to talk/run commands as the bot,
 * movement (follow me, go to coords, stop), connection control (disconnect / reconnect / switch
 * server), and a reveal-on-demand view of the bot's coordinates plus the live distance to you.
 *
 * <p>The bot's absolute position is hidden by default (it stays secret); the distance is always
 * shown. Everything rides the same encrypted channel as the rest of the panel.
 */
public final class BotControlScreen extends Screen {

	private final Screen parent;
	private final StasisBotConfig config;
	private final ControllerService controller;

	private TextFieldWidget chatInput;
	private TextFieldWidget gotoField;
	private TextFieldWidget serverField;
	private ButtonWidget revealButton;
	private ButtonWidget followButton;

	private boolean revealCoords = false;
	private boolean followMe = false;
	private long lastPoll = 0L;

	public BotControlScreen(Screen parent, StasisBotConfig config, ControllerService controller) {
		super(Text.literal("StasisBot — bot control"));
		this.parent = parent;
		this.config = config;
		this.controller = controller;
	}

	private String myName() {
		return client != null && client.player != null ? client.player.getGameProfile().name() : "";
	}

	@Override
	protected void init() {
		int half = width / 2;

		// --- left: chat input (the feed itself is drawn in render) ---
		chatInput = new TextFieldWidget(textRenderer, 12, height - 30, half - 90, 18, Text.literal("say"));
		chatInput.setMaxLength(256);
		chatInput.setPlaceholder(Text.literal("§7say as bot — prefix / for a command"));
		addDrawableChild(chatInput);
		addDrawableChild(ButtonWidget.builder(Text.literal("§aSend"), b -> sendChat())
				.dimensions(half - 74, height - 31, 62, 20).build());

		// --- right: controls ---
		int x = half + 12;
		int w = width - x - 12;
		int y = 56;
		int step = 24;

		revealButton = ButtonWidget.builder(revealLabel(), b -> { revealCoords = !revealCoords; b.setMessage(revealLabel()); })
				.dimensions(x, y, w, 20).build();
		addDrawableChild(revealButton);
		y += step;

		followButton = ButtonWidget.builder(followLabel(), b -> {
			followMe = !followMe;
			b.setMessage(followLabel());
			if (followMe) controller.follow(myName()); // native Baritone follow — tracks you continuously
			else controller.stopNav();
		}).dimensions(x, y, w / 2 - 2, 20).build();
		addDrawableChild(followButton);
		addDrawableChild(ButtonWidget.builder(Text.literal("Stop"), b -> { followMe = false; if (followButton != null) followButton.setMessage(followLabel()); controller.stopNav(); })
				.dimensions(x + w / 2 + 2, y, w / 2 - 2, 20).build());
		y += step;

		gotoField = new TextFieldWidget(textRenderer, x, y, w - 70, 18, Text.literal("coords"));
		gotoField.setPlaceholder(Text.literal("§7x y z"));
		addDrawableChild(gotoField);
		addDrawableChild(ButtonWidget.builder(Text.literal("Go to"), b -> doGoto())
				.dimensions(x + w - 64, y - 1, 64, 20).build());
		y += step;

		addDrawableChild(ButtonWidget.builder(Text.literal("§eDisconnect"), b -> controller.serverDisconnect())
				.dimensions(x, y, w / 2 - 2, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("§aReconnect"), b -> controller.serverConnect(""))
				.dimensions(x + w / 2 + 2, y, w / 2 - 2, 20).build());
		y += step;

		serverField = new TextFieldWidget(textRenderer, x, y, w - 70, 18, Text.literal("server"));
		serverField.setMaxLength(120);
		serverField.setPlaceholder(Text.literal("§7host:port"));
		addDrawableChild(serverField);
		addDrawableChild(ButtonWidget.builder(Text.literal("Connect"), b -> {
			if (serverField != null && !serverField.getText().isBlank()) controller.serverConnect(serverField.getText().trim());
		}).dimensions(x + w - 64, y - 1, 64, 20).build());
		y += step + 6;

		addDrawableChild(ButtonWidget.builder(Text.literal("§7« Back"), b -> { if (client != null) client.setScreen(parent); })
				.dimensions(x, y, w, 20).build());

		// Pull the first chat + position immediately so the panel isn't blank for a second.
		controller.requestChatLog();
		controller.requestPos(myName());
		lastPoll = System.currentTimeMillis();
	}

	private void sendChat() {
		if (chatInput == null || chatInput.getText().isBlank()) return;
		controller.say(chatInput.getText());
		chatInput.setText("");
	}

	private void doGoto() {
		if (gotoField == null) return;
		String[] p = gotoField.getText().trim().split("\\s+");
		if (p.length != 3) return;
		try {
			controller.gotoCoords(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
		} catch (NumberFormatException ignored) { }
	}

	@Override
	public void tick() {
		// Poll regardless of the cached status so the feed recovers after any transient blip;
		// the requests no-op if the controller isn't configured.
		long now = System.currentTimeMillis();
		if (now - lastPoll > 1000L) {
			lastPoll = now;
			controller.requestChatLog();
			controller.requestPos(myName());
		}
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if ((input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER)
				&& chatInput != null && chatInput.isFocused()) {
			sendChat();
			return true;
		}
		return super.keyPressed(input);
	}

	private Text revealLabel() {
		return Text.literal("Reveal coords: " + (revealCoords ? "§aON" : "§cOFF"));
	}

	private Text followLabel() {
		return Text.literal("Follow me: " + (followMe ? "§aON" : "§cOFF"));
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		super.render(ctx, mouseX, mouseY, delta);
		int half = width / 2;

		ctx.drawTextWithShadow(textRenderer, Text.literal("Bot chat (live)").formatted(Formatting.AQUA), 12, 14, 0xFFFFFF);
		ctx.drawTextWithShadow(textRenderer, Text.literal("Bot control").formatted(Formatting.AQUA), half + 12, 14, 0xFFFFFF);

		// --- live chat feed (left) ---
		String log = controller.chatLog();
		int feedTop = 30;
		int feedBottom = height - 40;
		int rows = Math.max(0, (feedBottom - feedTop) / 10);
		if (log != null && !log.isBlank()) {
			String[] lines = log.split("\n");
			int start = Math.max(0, lines.length - rows);
			int yy = feedTop;
			int maxW = half - 24;
			for (int i = start; i < lines.length; i++) {
				String line = lines[i];
				if (textRenderer.getWidth(line) > maxW) line = textRenderer.trimToWidth(line, maxW);
				ctx.drawTextWithShadow(textRenderer, Text.literal(line), 12, yy, 0xDDDDDD);
				yy += 10;
			}
		} else {
			ctx.drawTextWithShadow(textRenderer, Text.literal("§7(no chat yet — connect to the bot)"), 12, feedTop, 0xAAAAAA);
		}

		// --- bot position / distance (right top) ---
		int dist = controller.distance();
		String distStr = dist < 0 ? "§7?" : "§f" + dist + "m";
		String posStr = revealCoords
				? (controller.botPos().isBlank() ? "§7?" : "§f" + controller.botPos())
				: "§8hidden";
		ctx.drawTextWithShadow(textRenderer, Text.literal("§7distance: " + distStr + "   §7pos: " + posStr), half + 12, 32, 0xFFFFFF);

		if (controller.status() != ControllerService.Status.SYNCED) {
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.literal("§e● not connected — open the main panel and Save & Connect"), width / 2, height - 14, 0xFFFFFF);
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
