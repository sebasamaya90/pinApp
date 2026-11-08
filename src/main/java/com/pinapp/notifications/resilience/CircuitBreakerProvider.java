package com.pinapp.notifications.resilience;

import com.pinapp.notifications.Notification;
import com.pinapp.notifications.provider.NotificationProvider;
import com.pinapp.notifications.provider.ProviderException;
import com.pinapp.notifications.provider.ProviderResponse;

import java.util.Objects;

/**
 * Decorator that fails fast while the provider is known to be unhealthy. Only transient failures
 * (retryable: timeouts, network, 429/5xx) count against the provider; a 4xx means the provider is
 * alive and the request was bad, so it counts as a healthy call.
 * An open breaker throws a NON-retryable exception so retries are skipped and failover starts at once.
 */
public final class CircuitBreakerProvider<N extends Notification> implements NotificationProvider<N> {

    private final NotificationProvider<N> delegate;
    private final CircuitBreaker breaker;

    public CircuitBreakerProvider(NotificationProvider<N> delegate, CircuitBreaker breaker) {
        this.delegate = Objects.requireNonNull(delegate);
        this.breaker = Objects.requireNonNull(breaker);
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public ProviderResponse send(N notification) throws ProviderException {
        if (!breaker.tryAcquirePermission()) {
            throw ProviderException.of(ProviderException.Reason.CIRCUIT_OPEN,
                    name() + ": circuit breaker is open, call rejected", false, null, null, null);
        }
        try {
            ProviderResponse response = delegate.send(notification);
            breaker.onSuccess();
            return response;
        } catch (ProviderException e) {
            if (e.retryable()) {
                breaker.onFailure();
            } else {
                breaker.onSuccess();
            }
            throw e;
        } catch (RuntimeException e) {
            breaker.onFailure();
            throw e;
        }
    }
}
