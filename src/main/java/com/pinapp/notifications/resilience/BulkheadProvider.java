package com.pinapp.notifications.resilience;

import com.pinapp.notifications.Notification;
import com.pinapp.notifications.provider.NotificationProvider;
import com.pinapp.notifications.provider.ProviderException;
import com.pinapp.notifications.provider.ProviderResponse;

import java.util.Objects;

/** Decorator enforcing a {@link Bulkhead}. A full bulkhead is NOT retryable: failover should take over. */
public final class BulkheadProvider<N extends Notification> implements NotificationProvider<N> {

    private final NotificationProvider<N> delegate;
    private final Bulkhead bulkhead;

    public BulkheadProvider(NotificationProvider<N> delegate, Bulkhead bulkhead) {
        this.delegate = Objects.requireNonNull(delegate);
        this.bulkhead = Objects.requireNonNull(bulkhead);
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public ProviderResponse send(N notification) throws ProviderException {
        if (!bulkhead.tryAcquire()) {
            throw ProviderException.of(ProviderException.Reason.BULKHEAD_FULL,
                    name() + ": bulkhead full (" + bulkhead.maxConcurrentCalls() + " concurrent calls), call rejected",
                    false, null, null, null);
        }
        try {
            return delegate.send(notification);
        } finally {
            bulkhead.release();
        }
    }
}
