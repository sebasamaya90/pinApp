package com.pinapp.notifications.provider;

import com.pinapp.notifications.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/** Composite: tries providers in order until one accepts the notification (failover). */
public final class FallbackProvider<N extends Notification> implements NotificationProvider<N> {

    private static final Logger log = LoggerFactory.getLogger(FallbackProvider.class);

    private final List<NotificationProvider<N>> providers;

    public FallbackProvider(List<NotificationProvider<N>> providers) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("At least one provider is required");
        }
        this.providers = List.copyOf(providers);
    }

    @Override
    public String name() {
        return providers.stream().map(NotificationProvider::name).collect(Collectors.joining(">", "failover[", "]"));
    }

    @Override
    public ProviderResponse send(N notification) throws ProviderException {
        ProviderException last = null;
        for (NotificationProvider<N> provider : providers) {
            try {
                return provider.send(notification);
            } catch (ProviderException e) {
                log.warn("Provider {} failed ({}); trying next one", provider.name(), e.getMessage());
                if (last != null) {
                    e.addSuppressed(last);
                }
                last = e;
            }
        }
        throw last;
    }
}
