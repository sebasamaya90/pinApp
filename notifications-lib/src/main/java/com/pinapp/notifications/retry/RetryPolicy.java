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

    /**
     * Adds jitter so many clients do not retry in lockstep (thundering herd). The delay becomes a
     * random value in {@code [base * (1 - factor), base]}; factor 0.5 means "between 50% and 100%".
     */
    static RetryPolicy withJitter(RetryPolicy base, double factor) {
        return new Jittered(base, factor, () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble());
    }

    static RetryPolicy withJitter(RetryPolicy base, double factor, java.util.function.DoubleSupplier random) {
        return new Jittered(base, factor, random);
    }

    record Jittered(RetryPolicy base, double factor, java.util.function.DoubleSupplier random) implements RetryPolicy {
        public Jittered {
            Objects.requireNonNull(base);
            Objects.requireNonNull(random);
            if (factor < 0 || factor > 1) {
                throw new IllegalArgumentException("jitter factor must be between 0 and 1");
            }
        }

        @Override
        public int maxAttempts() {
            return base.maxAttempts();
        }

        @Override
        public Duration delayBeforeRetry(int failedAttempts) {
            long nanos = base.delayBeforeRetry(failedAttempts).toNanos();
            return Duration.ofNanos((long) (nanos * (1 - factor * random.getAsDouble())));
        }
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
