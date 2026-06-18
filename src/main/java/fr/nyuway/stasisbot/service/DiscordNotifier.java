package fr.nyuway.stasisbot.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.nyuway.stasisbot.StasisBot;
import fr.nyuway.stasisbot.config.DiscordEvent;
import fr.nyuway.stasisbot.config.PingMode;
import fr.nyuway.stasisbot.config.PingTarget;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import net.minecraft.util.math.BlockPos;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Discord webhook sender. Posts short JSON messages to a user-configured webhook
 * from the game tick without ever blocking it: each message is handed to a single
 * background thread that sends them <em>one at a time, in submission order</em>.
 *
 * <p>Ordering matters — a home request fires several events back-to-back
 * (requested → fired → pulled in → reopened → dropped). Posting them with
 * independent async requests let them race over the network and land out of order
 * in the channel; a single serial sender keeps the timeline correct.
 *
 * <p>For safety, only genuine Discord webhook hosts are accepted — this stops the
 * bot from being pointed at an arbitrary (possibly internal) address, i.e. a
 * basic SSRF guard. The feature is opt-in and does nothing until enabled with a
 * valid URL in the config/GUI.
 */
public final class DiscordNotifier {

	private static final Gson GSON = new Gson();

	private final StasisBotConfig config;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	/** Single worker so webhook POSTs go out strictly in the order they're queued. */
	private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "StasisBot-Discord");
		t.setDaemon(true);
		return t;
	});

	/** Optional: when set, gates @everyone pings on an outsider being in render. */
	private RenderPresence presence;

	public DiscordNotifier(StasisBotConfig config) {
		this.config = config;
	}

	/** Wire the shared render-distance snapshot used to gate pings. */
	public void setPresence(RenderPresence presence) {
		this.presence = presence;
	}

	/** True when the feature is on and a plausible Discord webhook URL is set. */
	public boolean isReady() {
		return config.discordEnabled() && isValidWebhook(config.discordWebhookUrl());
	}

	/** Only accept real Discord webhook hosts, so we never POST somewhere internal. */
	public static boolean isValidWebhook(String url) {
		if (url == null) return false;
		String u = url.trim().toLowerCase(Locale.ROOT);
		return u.startsWith("https://discord.com/api/webhooks/")
				|| u.startsWith("https://discordapp.com/api/webhooks/")
				|| u.startsWith("https://canary.discord.com/api/webhooks/")
				|| u.startsWith("https://ptb.discord.com/api/webhooks/");
	}

	/** Post a message (honouring the @everyone ping toggle). No-op when not ready. */
	public void send(String content) {
		if (!isReady()) return;
		postInternal(config.discordWebhookUrl(), content, config.discordTestPing(), BLURPLE)
				.thenAccept(ok -> { if (!ok) StasisBot.LOGGER.warn("[discord] webhook POST failed"); });
	}

	/**
	 * Announce one event, honouring its per-event config: send on/off, the
	 * {@code @everyone} {@link PingMode}, and the optional gear / coordinates /
	 * distance extras. {@code outsider} is whether the player this event is about is
	 * NOT a base member — it only matters for {@link PingMode#OUTSIDERS}.
	 */
	public void notify(DiscordEvent event, boolean outsider, String content) {
		notify(event, outsider, content, null, -1);
	}

	/** Announce a non-scoped event (bot died, restocked, …). */
	public void notify(DiscordEvent event, String content) {
		notify(event, false, content, null, -1);
	}

	/** Announce a non-scoped but locatable event (e.g. a stasis was recharged). */
	public void notify(DiscordEvent event, String content, BlockPos pos, int distance) {
		notify(event, false, content, pos, distance);
	}

	/**
	 * Full announce: per-event send gate, ping mode, plus the optional distance
	 * (when {@code distance >= 0}) and spoiler-tagged coordinates (when {@code pos}
	 * is non-null) appended only if that event has them enabled.
	 */
	public void notify(DiscordEvent event, boolean outsider, String content, BlockPos pos, int distance) {
		notify(event, outsider, content, null, pos, distance);
	}

	/**
	 * Full announce with an optional {@code from}→{@code to} coordinate pair (e.g. a
	 * pearl teleport's departure and arrival). When only one of the two is given it
	 * behaves like a single location; both are shown only if the event has coords on.
	 */
	public void notify(DiscordEvent event, boolean outsider, String content, BlockPos from, BlockPos to, int distance) {
		if (!isReady()) return;
		if (!config.discordEventEnabled(event)) return;
		boolean ping = wantsPing(event, outsider);
		String body = decorate(event, content, from, to, distance);
		postInternal(config.discordWebhookUrl(), body, ping, colorFor(event))
				.thenAccept(ok -> { if (!ok) StasisBot.LOGGER.warn("[discord] '{}' POST failed", event.key()); });
	}

	/** Resolve the configured {@link PingMode} into an actual ping decision. */
	private boolean wantsPing(DiscordEvent event, boolean outsider) {
		return switch (config.discordEventPing(event)) {
			case OFF -> false;
			case ALL -> true;
			case OUTSIDERS -> outsider;
		};
	}

	/** Append the optional distance and (spoiler) coordinates to a message. */
	private String decorate(DiscordEvent event, String content, BlockPos from, BlockPos to, int distance) {
		boolean fr = "fr".equalsIgnoreCase(config.language());
		StringBuilder sb = new StringBuilder(content);
		if (distance >= 0 && config.discordEventDistance(event)) {
			sb.append(fr ? "\n\uD83D\uDCCF \u00e0 ~" : "\n\uD83D\uDCCF ~").append(distance).append(fr ? " blocs" : " blocks");
		}
		if (config.discordEventCoords(event)) {
			if (from != null && to != null) {
				sb.append(fr ? "\n\uD83D\uDCCD d\u00e9part " : "\n\uD83D\uDCCD from ").append(spoiler(from))
					.append(fr ? " \u2192 arriv\u00e9e " : " \u2192 to ").append(spoiler(to));
			} else {
				BlockPos one = to != null ? to : from;
				if (one != null) sb.append("\n\uD83D\uDCCD ").append(spoiler(one));
			}
		}
		return sb.toString();
	}

	/** A block position wrapped in a Discord spoiler tag, so coords stay hidden until clicked. */
	private static String spoiler(BlockPos p) {
		return "||" + p.getX() + ' ' + p.getY() + ' ' + p.getZ() + "||";
	}

	/**
	 * Send a one-off test message to the given URL (used by the GUI "Test" button).
	 * The result callback fires off the client thread — the caller is responsible
	 * for hopping back onto it before touching any game state.
	 */
	public void test(String url, boolean ping, Consumer<Boolean> result) {
		if (!isValidWebhook(url)) {
			if (result != null) result.accept(false);
			return;
		}
		postInternal(url, "\u2705 StasisBot webhook test \u2014 it works!", ping, BLURPLE)
				.thenAccept(ok -> { if (result != null) result.accept(ok); });
	}

	private CompletableFuture<Boolean> postInternal(String url, String content, boolean ping, int color) {
		final HttpRequest req;
		try {
			req = HttpRequest.newBuilder(URI.create(url.trim()))
					.timeout(Duration.ofSeconds(10))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(buildBody(content, ping, color), StandardCharsets.UTF_8))
					.build();
		} catch (RuntimeException e) {
			StasisBot.LOGGER.warn("[discord] bad webhook URL: {}", e.toString());
			return CompletableFuture.completedFuture(false);
		}
		// Queue on the single sender thread: posts leave in order, one fully delivered
		// before the next starts, so Discord timestamps them in the right sequence.
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		try {
			sender.execute(() -> result.complete(sendBlocking(req)));
		} catch (RuntimeException e) { // executor rejected (shutting down)
			result.complete(false);
		}
		return result;
	}

	/** Send one webhook request and wait for it, retrying once if Discord rate-limits us. */
	private boolean sendBlocking(HttpRequest req) {
		try {
			HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
			if (resp.statusCode() == 429) {
				long waitMs = resp.headers().firstValue("Retry-After")
						.map(v -> { try { return (long) (Double.parseDouble(v.trim()) * 1000); }
								catch (NumberFormatException nfe) { return 1000L; } })
						.orElse(1000L);
				Thread.sleep(Math.min(Math.max(waitMs, 0L) + 100L, 10000L));
				resp = http.send(req, HttpResponse.BodyHandlers.discarding());
			}
			return resp.statusCode() >= 200 && resp.statusCode() < 300;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} catch (Exception e) {
			StasisBot.LOGGER.warn("[discord] webhook error: {}", e.toString());
			return false;
		}
	}

	// Discord brand colours used for the embed accent bar.
	private static final int BLURPLE = 0x5865F2;
	private static final int RED     = 0xED4245;
	private static final int ORANGE  = 0xFAA61A;
	private static final int GREEN   = 0x57F287;
	private static final int GREY    = 0x99AAB5;

	/** A sensible accent colour for each event, so embeds read at a glance. */
	private static int colorFor(DiscordEvent event) {
		return switch (event) {
			case BOT_DIED, PLAYER_DIED, CRYSTAL_BROKEN, TNT_PRIMED -> RED;
			case OUT_OF_PEARLS, PATH_FAILED, TP_FAILED, STASIS_WRONG_PEARL, RETURN_TOO_FAR -> ORANGE;
			case PLAYER_ENTER, PLAYER_CONNECT, BOT_RESPAWNED, TP_CONFIRMED -> GREEN;
			case PLAYER_LEAVE, PLAYER_DISCONNECT -> GREY;
			default -> BLURPLE;
		};
	}

	/**
	 * Build the JSON payload. Plain text by default; a coloured embed when
	 * {@code discordUseEmbeds} is on. Either way the mention only goes in (and is
	 * only allowed) when we're actually pinging, and it follows the configured
	 * {@link PingTarget}: {@code @everyone}, {@code @here}, or a custom role.
	 */
	private String buildBody(String content, boolean ping, int color) {
		JsonObject root = new JsonObject();
		String mention = ping ? mentionText() : "";
		if (config.discordUseEmbeds()) {
			if (ping) root.addProperty("content", mention);
			JsonArray embeds = new JsonArray();
			JsonObject embed = new JsonObject();
			embed.addProperty("description", content);
			embed.addProperty("color", color);
			embeds.add(embed);
			root.add("embeds", embeds);
		} else {
			root.addProperty("content", (ping ? mention + " " : "") + content);
		}
		root.add("allowed_mentions", allowedMentions(ping));
		return GSON.toJson(root);
	}

	/** The literal mention to inject for the configured target (e.g. {@code @everyone}, {@code <@&id>}). */
	private String mentionText() {
		return switch (config.pingTarget()) {
			case EVERYONE -> "@everyone";
			case HERE -> "@here";
			case ROLE -> roleMention();
		};
	}

	/** A role mention: {@code <@&id>} for a numeric ID (real ping), else {@code @name} (text only). */
	private String roleMention() {
		String r = config.pingRole().trim();
		if (r.isEmpty()) return "@everyone";
		if (isRoleId(r)) return "<@&" + r + ">";
		return r.startsWith("@") ? r : "@" + r;
	}

	/** True when the configured role is a bare numeric snowflake ID (so it can really ping). */
	private static boolean isRoleId(String r) {
		if (r.isEmpty()) return false;
		for (int i = 0; i < r.length(); i++) {
			if (!Character.isDigit(r.charAt(i))) return false;
		}
		return true;
	}

	/** allowed_mentions matching the target, so only the intended ping is ever permitted. */
	private JsonObject allowedMentions(boolean ping) {
		JsonObject mentions = new JsonObject();
		JsonArray parse = new JsonArray();
		JsonArray roles = new JsonArray();
		if (ping) {
			PingTarget target = config.pingTarget();
			if (target == PingTarget.EVERYONE || target == PingTarget.HERE) {
				parse.add("everyone"); // Discord treats @here under the "everyone" parse type
			} else { // ROLE
				String r = config.pingRole().trim();
				if (isRoleId(r)) roles.add(r); // a role *name* can't be resolved by a webhook
			}
		}
		mentions.add("parse", parse); // empty array = suppress all pings unless we opted in
		if (roles.size() > 0) mentions.add("roles", roles);
		return mentions;
	}
}
