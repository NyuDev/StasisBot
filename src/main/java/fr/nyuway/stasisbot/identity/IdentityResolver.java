package fr.nyuway.stasisbot.identity;

import fr.nyuway.stasisbot.config.StasisBotConfig;

import java.util.HashSet;
import java.util.Set;

/**
 * Turns a chat sender's name into the lower-cased tokens that may identify their
 * stasis sign: their own name, plus any configured aliases/keywords. Tokenises
 * the same way {@link fr.nyuway.stasisbot.model.StasisChamber} tokenises signs, so
 * matching is symmetric.
 */
public final class IdentityResolver {

	private final StasisBotConfig config;

	public IdentityResolver(StasisBotConfig config) {
		this.config = config;
	}

	public Set<String> tokensFor(String playerName) {
		Set<String> tokens = new HashSet<>();
		addWords(tokens, playerName);
		for (String alias : config.aliasesFor(playerName)) {
			addWords(tokens, alias);
		}
		return tokens;
	}

	private static void addWords(Set<String> tokens, String value) {
		if (value == null) return;
		for (String word : value.toLowerCase().split("[^a-z0-9_]+")) {
			if (!word.isBlank()) tokens.add(word);
		}
	}
}
