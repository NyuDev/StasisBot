package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.StasisBot;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Captures the bot's own log output (everything sent through {@link StasisBot#LOGGER}) into a
 * timestamped ring buffer, so the remote panel can show a "what happened, when" view. Done by
 * attaching a tiny log4j appender to the {@code StasisBot} logger — nothing else is captured.
 */
public final class LogTap {

	private static final int MAX = 300;
	private static final Deque<String> lines = new ArrayDeque<>();
	private static final DateTimeFormatter FMT =
			DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
	private static boolean installed = false;

	private LogTap() {
	}

	/** Attach the capturing appender (idempotent). Failure is non-fatal — the bot runs regardless. */
	public static synchronized void install() {
		if (installed) return;
		try {
			Logger core = (Logger) LogManager.getLogger("StasisBot");
			RingAppender appender = new RingAppender();
			appender.start();
			core.addAppender(appender);
			installed = true;
			StasisBot.LOGGER.info("[logtap] capturing bot logs for the remote panel");
		} catch (Throwable t) {
			StasisBot.LOGGER.warn("[logtap] could not install: {}", t.toString());
		}
	}

	static synchronized void add(String line) {
		lines.addLast(line);
		while (lines.size() > MAX) lines.pollFirst();
	}

	/** All buffered log lines, oldest first, newline-separated. */
	public static synchronized String dump() {
		return String.join("\n", lines);
	}

	private static final class RingAppender extends AbstractAppender {
		RingAppender() {
			super("StasisBotRing", null, null, true, Property.EMPTY_ARRAY);
		}

		@Override
		public void append(LogEvent e) {
			try {
				String lvl = e.getLevel() != null ? String.valueOf(e.getLevel().name().charAt(0)) : "I";
				add(FMT.format(Instant.ofEpochMilli(e.getTimeMillis())) + " [" + lvl + "] "
						+ e.getMessage().getFormattedMessage());
			} catch (Throwable ignored) {
				// never let logging break logging
			}
		}
	}
}
