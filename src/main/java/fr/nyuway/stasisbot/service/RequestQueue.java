package fr.nyuway.stasisbot.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the pending home-requesters as a first-come, first-served queue with
 * O(1) de-duplication, plus a short debounce that collapses spammed requests
 * from the same player. Pure data structure — it makes no decisions about who
 * gets served, it only remembers who is waiting.
 */
final class RequestQueue {

	/** Collapse repeated requests from the same sender within this window. */
	private static final long DEBOUNCE_MILLIS = 1500L;

	private final Map<String, Long> lastRequestAt = new ConcurrentHashMap<>();
	private final Deque<String> queue = new ArrayDeque<>();
	private final Set<String> queuedKeys = new HashSet<>();

	/** True when the same sender asked again within {@value #DEBOUNCE_MILLIS} ms. */
	boolean shouldDebounce(String name) {
		long now = System.currentTimeMillis();
		Long previous = lastRequestAt.put(key(name), now);
		return previous != null && now - previous < DEBOUNCE_MILLIS;
	}

	/** Add a requester to the back of the queue; false if already queued. */
	boolean offer(String name) {
		if (!queuedKeys.add(key(name))) return false;
		queue.addLast(name);
		return true;
	}

	/** Take the next requester (still considered "in flight" until {@link #release}). */
	String poll() {
		return queue.pollFirst();
	}

	/** Forget a requester once their request is fully finished. */
	void release(String name) {
		if (name != null) queuedKeys.remove(key(name));
	}

	boolean isEmpty() {
		return queue.isEmpty();
	}

	int size() {
		return queue.size();
	}

	private static String key(String name) {
		return name.toLowerCase(Locale.ROOT);
	}
}
