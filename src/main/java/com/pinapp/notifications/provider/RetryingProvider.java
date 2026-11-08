package com.pinapp.notifications.provider;

import com.pinapp.notifications.Notification;
import com.pinapp.notifications.retry.RetryPolicy;
import com.pinapp.notifications.retry.Sleeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;

/** Decorator adding retries to any provider. Only retryable failures are retried. */
public final class RetryingProvider<N extends Notification> implements NotificationProvider<N> {

    private static final Logger log = LoggerFactory.getLogger(RetryingProvider.class);

    private final NotificationProvider<N> delegate;
    private final RetryPolicy policy;
    private final Sleeper sleeper;

    public RetryingProvider(NotificationProvider<N> delegate, RetryPolicy policy, Sleeper sleeper) {
        this.delegate = Objects.requireNonNull(delegate);
        this.policy = Objects.requireNonNull(policy);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public ProviderResponse send(N notification) throws ProviderException {
        int attempt = 1;
        while (true) {
            try {
                return delegate.send(notification).withAttempts(attempt);
            } catch (ProviderException e) {
                if (!e.retryable() || attempt >= policy.maxAttempts()) {
                    throw e.withAttempts(attempt);
                }
                Duration delay = policy.delayBeforeRetry(attempt);
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
