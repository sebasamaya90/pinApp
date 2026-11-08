package com.pinapp.notifications.resilience;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;

/** Token-bucket rate limiter: sustained {@code permitsPerSecond}, with bursts up to {@code burst}. */
public final class RateLimiter {

    public record Config(double permitsPerSecond, int burst) {
        public Config {
            if (!(permitsPerSecond > 0)) {
                throw new IllegalArgumentException("permitsPerSecond must be > 0");
            }
            if (burst < 1) {
                throw new IllegalArgumentException("burst must be >= 1");
            }
        }
    }

    private final Config config;
    private final InstantSource clock;
    private double tokens;
    private Instant lastRefill;

    public RateLimiter(Config config, InstantSource clock) {
        this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock);
        this.tokens = config.burst();
        this.lastRefill = clock.instant();
    }

    /** @return {@link Duration#ZERO} if a permit was consumed; otherwise how long to wait for the next one. */
    public synchronized Duration tryAcquire() {
        Instant now = clock.instant();
        double elapsedSeconds = Math.max(0, Duration.between(lastRefill, now).toNanos() / 1e9);
        tokens = Math.min(config.burst(), tokens + elapsedSeconds * config.permitsPerSecond());
        lastRefill = now;
        if (tokens >= 1) {
            tokens -= 1;
            return Duration.ZERO;
        }
        return Duration.ofNanos((long) Math.ceil((1 - tokens) / config.permitsPerSecond() * 1e9));
    }
}
