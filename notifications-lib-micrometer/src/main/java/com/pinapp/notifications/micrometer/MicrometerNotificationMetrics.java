package com.pinapp.notifications.micrometer;

import com.pinapp.notifications.event.NotificationListener;
import com.pinapp.notifications.resilience.Bulkhead;
import com.pinapp.notifications.resilience.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Optional bridge between notifications-lib and Micrometer. The core library only exposes plain
 * listener hooks; this module translates them into meters, so the core never depends on Micrometer.
 *
 * <pre>{@code
 * var metrics = new MicrometerNotificationMetrics(registry);
 *
 * Resilience resilience = Resilience.recommended()-like builder
 *     .circuitBreakerListener(metrics.circuitBreakerListener())
 *     .bulkheadObserver(metrics.bulkheadObserver())
 *     .build();
 *
 * NotificationService.builder()...subscribe(metrics.notificationListener()).build();
 * }</pre>
 *
 * <h2>Meters</h2>
 * <ul>
 *   <li>{@code notifications.events} (counter) tags: channel, type (sent|failed|rejected|duplicate), provider</li>
 *   <li>{@code notifications.circuit_breaker.transitions} (counter) tags: provider, from, to</li>
 *   <li>{@code notifications.circuit_breaker.state} (gauge: 0 closed, 1 open, 2 half-open) tag: provider</li>
 *   <li>{@code notifications.bulkhead.available_permits} and {@code notifications.bulkhead.max_permits}
 *       (gauges) tag: provider</li>
 * </ul>
 * Tags never contain recipients or message content, so cardinality stays bounded and no personal data leaks.
 */
public final class MicrometerNotificationMetrics {

    private final MeterRegistry registry;
    // Gauges hold weak references: keep the backing values strongly reachable here.
    private final Map<String, AtomicInteger> breakerStates = new ConcurrentHashMap<>();

    public MicrometerNotificationMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    public NotificationListener notificationListener() {
        return event -> registry.counter("notifications.events",
                "channel", event.channel().name(),
                "type", event.type().name().toLowerCase(Locale.ROOT),
                "provider", String.valueOf(event.provider())).increment();
    }

    public Consumer<CircuitBreaker.Transition> circuitBreakerListener() {
        return transition -> {
            registry.counter("notifications.circuit_breaker.transitions",
                    "provider", transition.name(),
                    "from", transition.from().name().toLowerCase(Locale.ROOT),
                    "to", transition.to().name().toLowerCase(Locale.ROOT)).increment();
            breakerState(transition.name()).set(transition.to().ordinal());
        };
    }

    public BiConsumer<String, Bulkhead> bulkheadObserver() {
        return (provider, bulkhead) -> {
            Gauge.builder("notifications.bulkhead.available_permits", bulkhead, Bulkhead::availablePermits)
                    .tags(Tags.of("provider", provider))
                    .register(registry);
            Gauge.builder("notifications.bulkhead.max_permits", bulkhead, Bulkhead::maxConcurrentCalls)
                    .tags(Tags.of("provider", provider))
                    .register(registry);
        };
    }

    private AtomicInteger breakerState(String provider) {
        return breakerStates.computeIfAbsent(provider, name -> registry.gauge(
                "notifications.circuit_breaker.state", Tags.of("provider", name), new AtomicInteger(0)));
    }
}
