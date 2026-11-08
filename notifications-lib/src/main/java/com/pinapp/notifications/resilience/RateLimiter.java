package com.pinapp.notifications.resilience;

import java.time.Duration;

/**
 * Port for rate limiting one provider. {@link TokenBucketRateLimiter} is the built-in, per-JVM
 * implementation. To enforce a quota shared by several instances, implement this interface on top of a
 * shared store (e.g. Redis with a Lua token bucket) and plug it in with
 * {@code Resilience.builder().rateLimiterFactory(providerName -> new RedisRateLimiter(...))}.
 */
@FunctionalInterface
public interface RateLimiter {

    /** @return {@link Duration#ZERO} if a permit was consumed; otherwise how long to wait for the next one. */
    Duration tryAcquire();

    /** Configuration of the built-in token bucket. */
    record Config(double permitsPerSecond, int burst) {
        public Config {
            if (!(permitsPerSecond > 0)) {
                throw new IllegalArgumentException("permitsPerSecond must be > 0");
            }
            if (burst < 1) {
                throw new IllegalArgumentException("burst must be >= 1");
            }
        }
    }
}
