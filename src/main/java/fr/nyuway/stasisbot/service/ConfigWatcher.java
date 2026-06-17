package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.StasisBot;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Hot-reloads {@code config/stasisbot.json} while the bot runs.
 *
 * <p>Polls the file's modified-time about once a second on the client thread; when
 * it changes, the live {@link StasisBotConfig} — the very instance every service
 * already holds — is refreshed in place. So editing the file (locally or over SSH
 * on the headless box) applies immediately, without restarting the client and
 * dropping the 2b2t queue. A bad/half-written file is logged and ignored; the
 * running config is kept until the next valid save.
 */
public final class ConfigWatcher {

	/** Throttle: don't stat the file more than once per this interval. */
	private static final long CHECK_INTERVAL_MILLIS = 1000L;

	private final StasisBotConfig config;
	private final Path file;
	private long lastModified;
	private long nextCheckAt;

	public ConfigWatcher(StasisBotConfig config) {
		this.config = config;
		this.file = FabricLoader.getInstance().getConfigDir().resolve(StasisBot.MOD_ID + ".json");
		this.lastModified = modifiedTime();
	}

	/** Called every client tick; cheap — only stats the file once a second. */
	public void tick() {
		long now = System.currentTimeMillis();
		if (now < nextCheckAt) return;
		nextCheckAt = now + CHECK_INTERVAL_MILLIS;

		long mtime = modifiedTime();
		if (mtime == 0L || mtime == lastModified) return;
		// Record the new stamp first, so a persistently broken file is retried only
		// on the next edit rather than spamming a reload error every second.
		lastModified = mtime;
		if (config.reloadFromDisk()) {
			StasisBot.LOGGER.info("[config] hot-reloaded — triggers now: [{}]", config.triggerWordsDisplay());
		}
	}

	private long modifiedTime() {
		try {
			return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
		} catch (IOException e) {
			return 0L;
		}
	}
}
