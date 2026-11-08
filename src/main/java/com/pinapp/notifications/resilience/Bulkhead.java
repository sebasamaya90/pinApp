package com.pinapp.notifications.resilience;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Semaphore bulkhead: caps concurrent calls to ONE provider so a slow provider cannot absorb every
 * available thread. Semaphore (not a thread pool) because callers run on virtual threads.
 */
public final class Bulkhead {

    /**
     * @param maxConcurrentCalls simultaneous in-flight calls allowed
     * @param maxWait            how long a caller may wait for a free slot (ZERO = fail fast)
     */
    public record Config(int maxConcurrentCalls, Duration maxWait) {
        public Config {
            if (maxConcurrentCalls < 1) {
                throw new IllegalArgumentException("maxConcurrentCalls must be >= 1");
            }
            Objects.requireNonNull(maxWait, "maxWait");
            if (maxWait.isNegative()) {
                throw new IllegalArgumentException("maxWait must not be negative");
            }
        }

        public static Config defaults() {
            return new Config(20, Duration.ZERO);
        }
    }

    private final Config config;
    private final Semaphore permits;

    public Bulkhead(Config config) {
        this.config = Objects.requireNonNull(config);
        this.permits = new Semaphore(config.maxConcurrentCalls());
    }

    /** @return true if a slot was obtained; the caller MUST then call {@link #release()}. */
    public boolean tryAcquire() {
        try {
            return permits.tryAcquire(config.maxWait().toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void release() {
        permits.release();
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    public int maxConcurrentCalls() {
        return config.maxConcurrentCalls();
    }
}
