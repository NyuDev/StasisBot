package fr.nyuway.stasisbot.config;

/**
 * What a Discord ping actually mentions when an event decides to ping (the
 * per-event {@link PingMode} still decides <em>whether</em> to ping at all):
 *
 * <ul>
 *   <li>{@link #EVERYONE} — mention {@code @everyone};</li>
 *   <li>{@link #HERE} — mention {@code @here} (only members currently online);</li>
 *   <li>{@link #ROLE} — mention a custom role, given by its ID or name in
 *       {@link StasisBotConfig#pingRole()} (default {@code "2b2t"}).</li>
 * </ul>
 *
 * <p>Note: a webhook can only truly ping a role by its numeric <em>ID</em>
 * ({@code <@&id>}); a role <em>name</em> is shown as plain text since a webhook
 * has no way to resolve it to an ID.
 */
public enum PingTarget {
	EVERYONE,
	HERE,
	ROLE;

	/** Cycle to the next target (EVERYONE → HERE → ROLE → EVERYONE). */
	public PingTarget next() {
		return switch (this) {
			case EVERYONE -> HERE;
			case HERE -> ROLE;
			case ROLE -> EVERYONE;
		};
	}
}
