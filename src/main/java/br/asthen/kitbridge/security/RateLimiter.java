package br.asthen.kitbridge.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Sliding-window limiter: at most {@code maxPerMinute} acquisitions per key in any 60s window. */
public final class RateLimiter {

    private static final long WINDOW_MS = 60_000L;

    private final int maxPerMinute;
    private final LongSupplier clock;
    private final Map<String, Deque<Long>> hits = new HashMap<>();
    private long lastCleanup;

    public RateLimiter(int maxPerMinute) {
        this(maxPerMinute, System::currentTimeMillis);
    }

    public RateLimiter(int maxPerMinute, LongSupplier clock) {
        this.maxPerMinute = maxPerMinute;
        this.clock = clock;
        this.lastCleanup = clock.getAsLong();
    }

    /** @return false when the key is over its limit (the attempt is not counted). */
    public synchronized boolean tryAcquire(String key) {
        if (maxPerMinute <= 0) return true; // disabled
        long now = clock.getAsLong();
        cleanup(now);
        Deque<Long> d = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!d.isEmpty() && now - d.peekFirst() >= WINDOW_MS) d.pollFirst();
        if (d.size() >= maxPerMinute) return false;
        d.addLast(now);
        return true;
    }

    private void cleanup(long now) {
        if (now - lastCleanup < WINDOW_MS) return;
        lastCleanup = now;
        hits.values().removeIf(d -> d.isEmpty() || now - d.peekLast() >= WINDOW_MS);
    }
}
