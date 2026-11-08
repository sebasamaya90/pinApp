package com.pinapp.notifications.micrometer;

import com.pinapp.notifications.NotificationService;
import com.pinapp.notifications.channel.Channels;
import com.pinapp.notifications.provider.NotificationProvider;
import com.pinapp.notifications.provider.ProviderException;
import com.pinapp.notifications.provider.ProviderResponse;
import com.pinapp.notifications.resilience.Bulkhead;
import com.pinapp.notifications.resilience.CircuitBreaker;
import com.pinapp.notifications.resilience.Resilience;
import com.pinapp.notifications.sms.SmsNotification;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MicrometerNotificationMetricsTest {

    private static final SmsNotification SMS = new SmsNotification("+14155552671", "hi");

    private SimpleMeterRegistry registry;
    private MicrometerNotificationMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MicrometerNotificationMetrics(registry);
    }

    private static NotificationProvider<SmsNotification> provider(boolean ok) {
        return new NotificationProvider<>() {
            public String name() {
                return "fake";
            }

            public ProviderResponse send(SmsNotification n) throws ProviderException {
                if (!ok) {
                    throw new ProviderException("down", true, 503, null);
                }
                return ProviderResponse.of("fake", "id");
            }
        };
    }

    @Test
    void countsNotificationEventsByChannelTypeAndProvider() {
        try (var service = NotificationService.builder()
                .register(Channels.sms().provider(provider(true)).build())
                .subscribe(metrics.notificationListener())
                .build()) {
            service.send(SMS);
            service.send(SMS);
            service.trySend(new SmsNotification("bad", "x"));
        }

        assertEquals(2.0, registry.get("notifications.events")
                .tags("channel", "sms", "type", "sent", "provider", "fake").counter().count());
        assertEquals(1.0, registry.get("notifications.events")
                .tags("channel", "sms", "type", "rejected", "provider", "-").counter().count());
    }

    @Test
    void exposesCircuitBreakerTransitionsAndState() {
        var resilience = Resilience.builder()
                .circuitBreaker(new CircuitBreaker.Config(2, 2, 0.5, Duration.ofSeconds(30), 1))
                .circuitBreakerListener(metrics.circuitBreakerListener())
                .build();
        var decorated = resilience.decorate(provider(false));

        for (int i = 0; i < 2; i++) {
            try {
                decorated.send(SMS);
            } catch (ProviderException expected) {
                // keep failing until the breaker opens
            }
        }

        assertEquals(1.0, registry.get("notifications.circuit_breaker.transitions")
                .tags("provider", "fake", "from", "closed", "to", "open").counter().count());
        assertEquals(1.0, registry.get("notifications.circuit_breaker.state").tag("provider", "fake").gauge().value(),
                "1 means OPEN");
    }

    @Test
    void exposesBulkheadCapacityGauges() throws Exception {
        var resilience = Resilience.builder()
                .bulkhead(new Bulkhead.Config(5, Duration.ZERO))
                .bulkheadObserver(metrics.bulkheadObserver())
                .build();
        var decorated = resilience.decorate(provider(true));
        decorated.send(SMS);

        assertNotNull(registry.find("notifications.bulkhead.available_permits").tag("provider", "fake").gauge());
        assertEquals(5.0, registry.get("notifications.bulkhead.available_permits").gauge().value());
        assertEquals(5.0, registry.get("notifications.bulkhead.max_permits").gauge().value());
    }
}
