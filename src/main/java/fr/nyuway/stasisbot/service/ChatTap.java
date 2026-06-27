package fr.nyuway.stasisbot.service;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A small ring buffer of the bot's most recent chat lines, so the remote panel can show
 * a live feed of what the bot sees. Fed from the same Fabric message events the rest of
 * the mod uses; only the rendered text is kept (capped length + count).
 */
public final class ChatTap {

	private static final int MAX_LINES = 80;
	private static final int MAX_LEN = 240;

	private final Deque<String> lines = new ArrayDeque<>();

	public void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) add(message.getString());
		});
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) ->
				add((sender != null ? "<" + sender.name() + "> " : "") + message.getString()));
	}

	private synchronized void add(String s) {
		if (s == null || s.isBlank()) return;
		lines.addLast(s.length() > MAX_LEN ? s.substring(0, MAX_LEN) : s);
		while (lines.size() > MAX_LINES) lines.pollFirst();
	}

	/** All buffered lines, oldest first, newline-separated. */
	public synchronized String dump() {
		return String.join("\n", lines);
	}
}
