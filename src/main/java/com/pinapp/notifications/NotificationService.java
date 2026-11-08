package com.pinapp.notifications;

import com.pinapp.notifications.channel.NotificationChannel;
import com.pinapp.notifications.dedup.DeduplicationStore;
import com.pinapp.notifications.event.NotificationEvent;
import com.pinapp.notifications.event.NotificationListener;
import com.pinapp.notifications.exception.ChannelNotConfiguredException;
import com.pinapp.notifications.exception.DeliveryException;
import com.pinapp.notifications.exception.NotificationException;
import com.pinapp.notifications.exception.ValidationException;
import com.pinapp.notifications.provider.DeliveryContext;
import com.pinapp.notifications.provider.ProviderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single entry point of the library (Facade). It routes each notification to the channel registered
 * for its {@link ChannelType} and publishes status events. It knows nothing about concrete channels
 * or vendors, which is what keeps it closed for modification and open for extension.
 *
 * <p>Error handling, pick the style you prefer:
 * <ul>
 *   <li>{@link #send}: returns the result or throws {@link ValidationException} /
 *       {@link DeliveryException} / {@link ChannelNotConfiguredException};</li>
 *   <li>{@link #trySend}: never throws, inspect {@link NotificationResult#status()};</li>
 *   <li>{@link #sendAsync}: future completes exceptionally with the same exceptions;</li>
 *   <li>{@link #sendAll}: batch, never fails as a whole, one result per notification.</li>
 * </ul>
 *
 * <p>Idempotency: the overloads taking an {@code idempotencyKey} make a send safe to repeat. With a
 * {@link DeduplicationStore} configured, a second send with the same key (within the TTL) is not sent
 * and returns {@link DeliveryStatus#DUPLICATE}. A failed send releases its key so it can be retried
 * (at-least-once; after an ambiguous timeout this can still duplicate a message).
 */
public final class NotificationService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final Map<ChannelType, NotificationChannel> channels;
    private final List<NotificationListener> listeners = new CopyOnWriteArrayList<>();
    private final Executor executor;
    private final boolean ownsExecutor;
    private final DeduplicationStore deduplicationStore;
    private final Duration deduplicationTtl;
    private final int maxBatchSize;

    private NotificationService(Builder b) {
        this.channels = Map.copyOf(b.channels);
        this.listeners.addAll(b.listeners);
        this.ownsExecutor = b.executor == null;
        this.executor = ownsExecutor ? Executors.newVirtualThreadPerTaskExecutor() : b.executor;
        this.deduplicationStore = b.deduplicationStore;
        this.deduplicationTtl = b.deduplicationTtl;
        this.maxBatchSize = b.maxBatchSize;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---------------------------------------------------------------- synchronous API

    public NotificationResult send(Notification notification) {
        return send(notification, null);
    }

    public NotificationResult send(Notification notification, String idempotencyKey) {
        Attempt attempt = process(notification, idempotencyKey);
        if (attempt.error() != null) {
            throw attempt.error();
        }
        return attempt.result();
    }

    public NotificationResult trySend(Notification notification) {
        return trySend(notification, null);
    }

    public NotificationResult trySend(Notification notification, String idempotencyKey) {
        return process(notification, idempotencyKey).result();
    }

    // ---------------------------------------------------------------- asynchronous API

    public CompletableFuture<NotificationResult> sendAsync(Notification notification) {
        return sendAsync(notification, null);
    }

    public CompletableFuture<NotificationResult> sendAsync(Notification notification, String idempotencyKey) {
        Objects.requireNonNull(notification, "notification");
        return CompletableFuture.supplyAsync(() -> send(notification, idempotencyKey), executor);
    }

    /** @throws IllegalArgumentException if the batch is larger than the configured {@code maxBatchSize} */
    public CompletableFuture<List<NotificationResult>> sendAll(Collection<? extends Notification> notifications) {
        if (notifications.size() > maxBatchSize) {
            throw new IllegalArgumentException("Batch of " + notifications.size()
                    + " notifications exceeds the maximum of " + maxBatchSize);
        }
        List<CompletableFuture<NotificationResult>> futures = notifications.stream()
                .map(n -> CompletableFuture.supplyAsync(() -> trySend(n), executor))
                .toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }

    // ---------------------------------------------------------------- events

    public void subscribe(NotificationListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    @Override
    public void close() {
        if (ownsExecutor && executor instanceof ExecutorService service) {
            service.close();
        }
    }

    // ---------------------------------------------------------------- internals

    private record Attempt(NotificationResult result, NotificationException error) {
    }

    private Attempt process(Notification notification, String idempotencyKey) {
        Objects.requireNonNull(notification, "notification");
        if (idempotencyKey != null && idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        String id = UUID.randomUUID().toString();
        ChannelType type = notification.channel();
        boolean claimed = false;
        try {
            if (idempotencyKey != null && deduplicationStore != null) {
                if (!deduplicationStore.tryClaim(idempotencyKey, deduplicationTtl)) {
                    publish(id, type, NotificationEvent.Type.DUPLICATE, "-", "idempotency key already processed");
                    return new Attempt(NotificationResult.duplicate(id, type), null);
                }
                claimed = true;
            }
            NotificationChannel channel = channels.get(type);
            if (channel == null) {
                throw new ChannelNotConfiguredException(type);
            }
            ProviderResponse response = new DeliveryContext(id, idempotencyKey)
                    .callWith(() -> channel.deliver(notification));
            publish(id, type, NotificationEvent.Type.SENT, response.provider(), "messageId=" + response.messageId());
            return new Attempt(NotificationResult.sent(id, type, response), null);
        } catch (NotificationException e) {
            release(claimed, idempotencyKey);
            return failed(id, type, e);
        } catch (RuntimeException e) {
            release(claimed, idempotencyKey);
            return failed(id, type, new DeliveryException(type, "-", 0, "unexpected error: " + e, e));
        }
    }

    private void release(boolean claimed, String idempotencyKey) {
        if (claimed) {
            deduplicationStore.release(idempotencyKey);
        }
    }

    private Attempt failed(String id, ChannelType type, NotificationException e) {
        NotificationResult result = NotificationResult.failure(id, type, e);
        NotificationEvent.Type eventType = result.status() == DeliveryStatus.REJECTED
                ? NotificationEvent.Type.REJECTED : NotificationEvent.Type.FAILED;
        publish(id, type, eventType, result.provider(), result.error());
        return new Attempt(result, e);
    }

    private void publish(String id, ChannelType type, NotificationEvent.Type eventType, String provider, String detail) {
        NotificationEvent event = new NotificationEvent(id, type, eventType, provider, detail, Instant.now());
        for (NotificationListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException e) {
                // A faulty subscriber must never break delivery or other subscribers.
                log.warn("Notification listener {} failed", listener.getClass().getName(), e);
            }
        }
    }

    public static final class Builder {
        private final Map<ChannelType, NotificationChannel> channels = new LinkedHashMap<>();
        private final List<NotificationListener> listeners = new ArrayList<>();
        private Executor executor;
        private DeduplicationStore deduplicationStore;
        private Duration deduplicationTtl = Duration.ofHours(24);
        private int maxBatchSize = 1000;

        private Builder() {
        }

        public Builder register(NotificationChannel channel) {
            Objects.requireNonNull(channel, "channel");
            if (channels.putIfAbsent(channel.type(), channel) != null) {
                throw new IllegalArgumentException("Channel already registered: " + channel.type());
            }
            return this;
        }

        public Builder subscribe(NotificationListener listener) {
            listeners.add(Objects.requireNonNull(listener));
            return this;
        }

        /** Executor for async operations. Default: one virtual thread per task (owned and closed by the service). */
        public Builder executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor);
            return this;
        }

        /** Enables deduplication of sends that carry an idempotency key, remembered for {@code ttl}. */
        public Builder deduplication(DeduplicationStore store, Duration ttl) {
            this.deduplicationStore = Objects.requireNonNull(store);
            this.deduplicationTtl = Objects.requireNonNull(ttl);
            return this;
        }

        /** Upper bound for {@code sendAll} (default 1000), to protect memory and vendor quotas. */
        public Builder maxBatchSize(int maxBatchSize) {
            if (maxBatchSize < 1) {
                throw new IllegalArgumentException("maxBatchSize must be >= 1");
            }
            this.maxBatchSize = maxBatchSize;
            return this;
        }

        public NotificationService build() {
            return new NotificationService(this);
        }
    }
}
