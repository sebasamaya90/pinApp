package com.pinapp.notifications.retry;

import java.time.Duration;
import java.util.Objects;

/** Strategy deciding how many times and how long to wait between attempts. */
public interface RetryPolicy {

    /** Total attempts including the first one (>= 1). */
    int maxAttempts();

    /** Delay to wait after the given (1-based) failed attempt. */
    Duration delayBeforeRetry(int failedAttempts);

    static RetryPolicy none() {
        return new Fixed(1, Duration.ZERO);
    }

    static RetryPolicy fixedDelay(int maxAttempts, Duration delay) {
        return new Fixed(maxAttempts, delay);
    }

    static RetryPolicy exponentialBackoff(int maxAttempts, Duration initialDelay, Duration maxDelay) {
        return new Exponential(maxAttempts, initialDelay, maxDelay);
    }

    record Fixed(int maxAttempts, Duration delay) implements RetryPolicy {
        public Fixed {
            requireAttempts(maxAttempts);
            Objects.requireNonNull(delay);
        }

        @Override
        public Duration delayBeforeRetry(int failedAttempts) {
            return delay;
        }
    }

    record Exponential(int maxAttempts, Duration initialDelay, Duration maxDelay) implements RetryPolicy {
        public Exponential {
            requireAttempts(maxAttempts);
            Objects.requireNonNull(initialDelay);
            Objects.requireNonNull(maxDelay);
        }

        @Override
        public Duration delayBeforeRetry(int failedAttempts) {
            int shift = Math.min(Math.max(failedAttempts - 1, 0), 20);
            Duration candidate = initialDelay.multipliedBy(1L << shift);
            return candidate.compareTo(maxDelay) > 0 ? maxDelay : candidate;
        }
    }

    private static void requireAttempts(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
    }
}
