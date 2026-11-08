package com.pinapp.notifications.provider;

import com.pinapp.notifications.Notification;
import com.pinapp.notifications.retry.RetryPolicy;
import com.pinapp.notifications.retry.Sleeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;

/**
 * Decorator adding retries to any provider. Only retryable failures are retried. It honours the
 * vendor's {@code Retry-After} (never waits less than requested) and an optional overall deadline:
 * if waiting for the next attempt would exceed {@code maxElapsed}, it gives up instead.
 */
public final class RetryingProvider<N extends Notification> implements NotificationProvider<N> {

    private static final Logger log = LoggerFactory.getLogger(RetryingProvider.class);

    private final NotificationProvider<N> delegate;
    private final RetryPolicy policy;
    private final Sleeper sleeper;
    private final Duration maxElapsed;
    private final InstantSource clock;

    public RetryingProvider(NotificationProvider<N> delegate, RetryPolicy policy, Sleeper sleeper) {
        this(delegate, policy, sleeper, null, InstantSource.system());
    }

    /** @param maxElapsed overall time budget across all attempts; {@code null} = unlimited */
    public RetryingProvider(NotificationProvider<N> delegate, RetryPolicy policy, Sleeper sleeper,
                            Duration maxElapsed, InstantSource clock) {
        this.delegate = Objects.requireNonNull(delegate);
        this.policy = Objects.requireNonNull(policy);
        this.sleeper = Objects.requireNonNull(sleeper);
        this.maxElapsed = maxElapsed;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public ProviderResponse send(N notification) throws ProviderException {
        Instant start = clock.instant();
        int attempt = 1;
        while (true) {
            try {
                return delegate.send(notification).withAttempts(attempt);
            } catch (ProviderException e) {
                if (!e.retryable() || attempt >= policy.maxAttempts()) {
                    throw e.withAttempts(attempt);
                }
                Duration delay = policy.delayBeforeRetry(attempt);
                if (e.retryAfter() != null && e.retryAfter().compareTo(delay) > 0) {
                    delay = e.retryAfter();
                }
                if (maxElapsed != null
                        && Duration.between(start, clock.instant()).plus(delay).compareTo(maxElapsed) > 0) {
                    log.warn("{} failed on attempt {}; retrying would exceed the {} ms deadline, giving up",
                            name(), attempt, maxElapsed.toMillis());
                    throw e.withAttempts(attempt);
                }
                log.warn("{} failed on attempt {}/{} ({}); retrying in {} ms", name(), attempt,
                        policy.maxAttempts(), e.getMessage(), delay.toMillis());
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e.withAttempts(attempt);
                }
                attempt++;
            }
        }
    }
}
