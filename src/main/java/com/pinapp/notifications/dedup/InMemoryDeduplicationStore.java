package com.pinapp.notifications.dedup;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Single-JVM store with per-key expiry. Expired keys are purged lazily to keep memory bounded. */
public final class InMemoryDeduplicationStore implements DeduplicationStore {

    private static final int PURGE_THRESHOLD = 10_000;

    private final ConcurrentHashMap<String, Instant> expirations = new ConcurrentHashMap<>();
    private final InstantSource clock;

    public InMemoryDeduplicationStore() {
        this(InstantSource.system());
    }

    public InMemoryDeduplicationStore(InstantSource clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public boolean tryClaim(String key, Duration ttl) {
        Instant now = clock.instant();
        boolean[] claimed = {false};
        expirations.compute(key, (k, expiresAt) -> {
            if (expiresAt == null || !expiresAt.isAfter(now)) {
                claimed[0] = true;
                return now.plus(ttl);
            }
            return expiresAt;
        });
        if (expirations.size() > PURGE_THRESHOLD) {
            expirations.values().removeIf(expiresAt -> !expiresAt.isAfter(now));
        }
        return claimed[0];
    }

    @Override
    public void release(String key) {
        expirations.remove(key);
    }
}
