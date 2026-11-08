package com.pinapp.notifications.resilience;

import com.pinapp.notifications.Notification;
import com.pinapp.notifications.provider.NotificationProvider;
import com.pinapp.notifications.provider.ProviderException;
import com.pinapp.notifications.provider.ProviderResponse;

import java.time.Duration;
import java.util.Objects;

/**
 * Decorator that stops calls locally when the provider quota would be exceeded (better than
 * collecting HTTP 429s). Rejections are retryable and carry the wait time as {@code retryAfter},
 * which {@code RetryingProvider} honours.
 */
public final class RateLimiterProvider<N extends Notification> implements NotificationProvider<N> {

    private final NotificationProvider<N> delegate;
    private final RateLimiter limiter;

    public RateLimiterProvider(NotificationProvider<N> delegate, RateLimiter limiter) {
        this.delegate = Objects.requireNonNull(delegate);
        this.limiter = Objects.requireNonNull(limiter);
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public ProviderResponse send(N notification) throws ProviderException {
        Duration wait = limiter.tryAcquire();
        if (!wait.isZero()) {
            throw ProviderException.of(ProviderException.Reason.RATE_LIMITED,
                    name() + ": local rate limit reached, next permit in " + wait.toMillis() + " ms",
                    true, null, wait, null);
        }
        return delegate.send(notification);
    }
}
