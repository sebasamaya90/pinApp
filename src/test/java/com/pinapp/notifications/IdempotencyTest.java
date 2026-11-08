package com.pinapp.notifications;

import com.pinapp.notifications.channel.Channels;
import com.pinapp.notifications.config.Secret;
import com.pinapp.notifications.dedup.InMemoryDeduplicationStore;
import com.pinapp.notifications.email.EmailNotification;
import com.pinapp.notifications.email.MailgunEmailProvider;
import com.pinapp.notifications.email.SendGridEmailProvider;
import com.pinapp.notifications.event.NotificationEvent;
import com.pinapp.notifications.http.Json;
import com.pinapp.notifications.provider.DeliveryContext;
import com.pinapp.notifications.provider.NotificationProvider;
import com.pinapp.notifications.provider.ProviderResponse;
import com.pinapp.notifications.support.FakeProvider;
import com.pinapp.notifications.support.MutableClock;
import com.pinapp.notifications.support.ScriptedTransport;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdempotencyTest {

    private static final EmailNotification EMAIL = EmailNotification.of("ana@example.com", "Hi", "Body");

    // ------------------------------------------------------------ store

    @Test
    void storeAllowsOnlyTheFirstClaimUntilItExpires() {
        var clock = new MutableClock();
        var store = new InMemoryDeduplicationStore(clock);

        assertTrue(store.tryClaim("k", Duration.ofMinutes(10)));
        assertFalse(store.tryClaim("k", Duration.ofMinutes(10)));
        assertTrue(store.tryClaim("other", Duration.ofMinutes(10)));

        clock.advance(Duration.ofMinutes(10));
        assertTrue(store.tryClaim("k", Duration.ofMinutes(10)), "expired keys can be claimed again");
    }

    @Test
    void releasedKeysCanBeClaimedAgain() {
        var store = new InMemoryDeduplicationStore();
        store.tryClaim("k", Duration.ofHours(1));

        store.release("k");

        assertTrue(store.tryClaim("k", Duration.ofHours(1)));
    }

    // ------------------------------------------------------------ service

    private NotificationService service(FakeProvider<EmailNotification> provider, List<NotificationEvent> events) {
        return NotificationService.builder()
                .register(Channels.email().provider(provider).build())
                .deduplication(new InMemoryDeduplicationStore(), Duration.ofHours(1))
                .subscribe(events::add)
                .build();
    }

    @Test
    void secondSendWithTheSameKeyIsNotSentAndIsReportedAsDuplicate() {
        var provider = FakeProvider.<EmailNotification>succeeding("p");
        List<NotificationEvent> events = new CopyOnWriteArrayList<>();
        try (var service = service(provider, events)) {
            NotificationResult first = service.send(EMAIL, "order-42-confirmation");
            NotificationResult second = service.send(EMAIL, "order-42-confirmation");

            assertTrue(first.isSuccess());
            assertTrue(second.isDuplicate());
            assertFalse(second.isSuccess());
            assertEquals(1, provider.calls());
            assertEquals(NotificationEvent.Type.DUPLICATE, events.get(1).type());
        }
    }

    @Test
    void differentKeysAndSendsWithoutKeyAreNeverDeduplicated() {
        var provider = FakeProvider.<EmailNotification>succeeding("p");
        try (var service = service(provider, new ArrayList<>())) {
            service.send(EMAIL, "a");
            service.send(EMAIL, "b");
            service.send(EMAIL);
            service.send(EMAIL);

            assertEquals(4, provider.calls());
        }
    }

    @Test
    void failedSendsReleaseTheKeySoTheyCanBeRetried() {
        var flaky = FakeProvider.<EmailNotification>failingTimes("flaky", 1);
        try (var service = service(flaky, new ArrayList<>())) {
            assertEquals(DeliveryStatus.FAILED, service.trySend(EMAIL, "k").status());

            NotificationResult retry = service.send(EMAIL, "k");

            assertTrue(retry.isSuccess(), "the key was released after the failure");
        }
    }

    @Test
    void rejectedSendsAlsoReleaseTheKey() {
        var provider = FakeProvider.<EmailNotification>succeeding("p");
        try (var service = service(provider, new ArrayList<>())) {
            assertEquals(DeliveryStatus.REJECTED, service.trySend(EmailNotification.of("bad", "s", "b"), "k").status());

            assertTrue(service.send(EMAIL, "k").isSuccess());
        }
    }

    @Test
    void withoutAStoreTheKeyIsIgnoredForDeduplication() {
        var provider = FakeProvider.<EmailNotification>succeeding("p");
        try (var service = NotificationService.builder()
                .register(Channels.email().provider(provider).build()).build()) {
            service.send(EMAIL, "k");
            service.send(EMAIL, "k");

            assertEquals(2, provider.calls());
        }
    }

    @Test
    void blankKeysAreRejected() {
        var provider = FakeProvider.<EmailNotification>succeeding("p");
        try (var service = service(provider, new ArrayList<>())) {
            assertThrows(IllegalArgumentException.class, () -> service.send(EMAIL, " "));
        }
    }

    @Test
    void asyncSendHonoursTheKeyToo() {
        var provider = FakeProvider.<EmailNotification>succeeding("p");
        try (var service = service(provider, new ArrayList<>())) {
            service.sendAsync(EMAIL, "k").join();

            assertTrue(service.sendAsync(EMAIL, "k").join().isDuplicate());
        }
    }

    // ------------------------------------------------------------ delivery context

    @Test
    void providersSeeTheSameNotificationIdOnEveryRetryAndTheKey() {
        List<DeliveryContext> seen = new CopyOnWriteArrayList<>();
        NotificationProvider<EmailNotification> spy = new NotificationProvider<>() {
            public String name() {
                return "spy";
            }

            public ProviderResponse send(EmailNotification n) throws com.pinapp.notifications.provider.ProviderException {
                seen.add(DeliveryContext.current().orElseThrow());
                if (seen.size() < 3) {
                    throw new com.pinapp.notifications.provider.ProviderException("transient", true, 503, null);
                }
                return ProviderResponse.of("spy", "id");
            }
        };
        try (var service = NotificationService.builder()
                .register(Channels.email().provider(spy)
                        .retryPolicy(com.pinapp.notifications.retry.RetryPolicy.fixedDelay(3, Duration.ZERO))
                        .sleeper(d -> { }).build())
                .build()) {

            NotificationResult result = service.send(EMAIL, "key-1");

            assertEquals(3, seen.size());
            assertEquals(1, seen.stream().map(DeliveryContext::notificationId).distinct().count());
            assertEquals(result.id(), seen.get(0).notificationId());
            assertEquals("key-1", seen.get(0).idempotencyKey());
        }
    }

    @Test
    void contextIsNotLeakedOutsideTheDelivery() {
        assertTrue(DeliveryContext.current().isEmpty());
    }

    @Test
    void sendGridAndMailgunTagTheRequestWithTheNotificationId() throws Exception {
        var sendGridTransport = ScriptedTransport.responding(202, "");
        var mailgunTransport = ScriptedTransport.responding(200, "{\"id\":\"<x>\"}");
        var context = new DeliveryContext("notif-123", null);

        context.callWith(() -> {
            new SendGridEmailProvider(new SendGridEmailProvider.Config(Secret.of("k"), "a@b.co", null), sendGridTransport).send(EMAIL);
            new MailgunEmailProvider(new MailgunEmailProvider.Config(Secret.of("k"), "d.com", "a@d.com"), mailgunTransport).send(EMAIL);
            return null;
        });

        assertEquals("notif-123", Json.read(sendGridTransport.lastRequest().body()).at("/personalizations/0/custom_args/notification_id").asText());
        assertTrue(URLDecoder.decode(mailgunTransport.lastRequest().body(), StandardCharsets.UTF_8)
                .contains("v:notification-id=notif-123"));
    }
}
