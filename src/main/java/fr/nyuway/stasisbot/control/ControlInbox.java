package fr.nyuway.stasisbot.control;

import net.minecraft.client.MinecraftClient;

import java.util.function.Consumer;

/**
 * Static bridge between the low-level chat-packet {@code Mixin} and the active
 * control endpoint (bot or controller). The Fabric {@code ClientReceiveMessageEvents}
 * are not enough on a client whose cheat/utility mod intercepts and cancels incoming
 * whispers to render them in its own UI — a canceled message never reaches those
 * events. The mixin taps the packet at {@code HEAD}, before any cancellation, and
 * hands the raw text here; the endpoint registers a sink to receive it.
 *
 * <p>Delivery is marshaled onto the client main thread, because the packet hook may
 * run on the network thread and the control channel's state is single-threaded.
 */
public final class ControlInbox {

	private static volatile Consumer<String> sink;

	private ControlInbox() {
	}

	/** The active endpoint registers its raw-line handler here (bot or controller). */
	public static void setSink(Consumer<String> s) {
		sink = s;
	}

	/** Feed a raw incoming message text; scheduled on the client thread, never throws upward. */
	public static void feed(String raw) {
		Consumer<String> s = sink;
		if (s == null || raw == null) return;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) return;
		client.execute(() -> {
			try {
				s.accept(raw);
			} catch (Throwable t) {
				// Never let control handling break the client.
			}
		});
	}
}
