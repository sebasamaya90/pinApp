package com.pinapp.notifications;

import com.pinapp.notifications.channel.Channels;
import com.pinapp.notifications.event.NotificationEvent;
import com.pinapp.notifications.exception.ChannelNotConfiguredException;
import com.pinapp.notifications.exception.DeliveryException;
import com.pinapp.notifications.exception.NotificationException;
import com.pinapp.notifications.exception.ValidationException;
import com.pinapp.notifications.email.EmailNotification;
import com.pinapp.notifications.retry.RetryPolicy;
import com.pinapp.notifications.sms.SmsNotification;
import com.pinapp.notifications.support.FakeProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationServiceTest {

    private static final EmailNotification EMAIL = EmailNotification.of("ana@example.com", "Hi", "Body");

    private FakeProvider<EmailNotification> emailProvider;
    private FakeProvider<SmsNotification> smsProvider;
    private final List<NotificationEvent> events = new CopyOnWriteArrayList<>();
    private NotificationService service;

    @BeforeEach
    void setUp() {
        emailProvider = FakeProvider.succeeding("email-fake");
        smsProvider = FakeProvider.succeeding("sms-fake");
        service = NotificationService.builder()
                .register(Channels.email().provider(emailProvider).build())
                .register(Channels.sms().provider(smsProvider).build())
                .subscribe(events::add)
                .build();
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    void routesEachNotificationToItsChannel() {
        NotificationResult email = service.send(EMAIL);
        NotificationResult sms = service.send(new SmsNotification("+14155552671", "hi"));

        assertTrue(email.isSuccess());
        assertEquals(ChannelType.EMAIL, email.channel());
        assertEquals("email-fake", email.provider());
        assertEquals(ChannelType.SMS, sms.channel());
        assertEquals(1, emailProvider.calls());
        assertEquals(1, smsProvider.calls());
    }

    @Test
    void invalidNotificationThrowsValidationExceptionAndNeverReachesTheProvider() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> service.send(EmailNotification.of("bad", "", "")));

        assertEquals(3, e.errors().size());
        assertEquals(0, emailProvider.calls());
        assertEquals(NotificationEvent.Type.REJECTED, events.get(0).type());
    }

    @Test
    void providerFailureThrowsDeliveryExceptionWithDetails() {
        var failing = FakeProvider.<EmailNotification>alwaysFailing("down", true);
        try (var failingService = NotificationService.builder()
                .register(Channels.email().provider(failing)
                        .retryPolicy(RetryPolicy.fixedDelay(3, Duration.ZERO)).sleeper(d -> { }).build())
                .build()) {

            DeliveryException e = assertThrows(DeliveryException.class, () -> failingService.send(EMAIL));

            assertEquals("down", e.provider());
            assertEquals(3, e.attempts());
            assertEquals(ChannelType.EMAIL, e.channel());
        }
    }

    @Test
    void unregisteredChannelIsReportedExplicitly() {
        var push = com.pinapp.notifications.push.PushNotification.builder().deviceToken("t").body("b").build();

        ChannelNotConfiguredException e = assertThrows(ChannelNotConfiguredException.class, () -> service.send(push));

        assertEquals(ChannelType.PUSH, e.channel());
    }

    @Test
    void trySendNeverThrowsAndDescribesTheFailure() {
        NotificationResult rejected = service.trySend(EmailNotification.of("bad", "s", "b"));
        NotificationResult unrouted = service.trySend(new com.pinapp.notifications.slack.SlackNotification("x"));

        assertEquals(DeliveryStatus.REJECTED, rejected.status());
        assertTrue(rejected.error().contains("to: must be a valid email address"));
        assertEquals(DeliveryStatus.FAILED, unrouted.status());
    }

    @Test
    void failoverReportsTheProviderThatFinallyDelivered() {
        var primary = FakeProvider.<EmailNotification>alwaysFailing("primary", false);
        var backup = FakeProvider.<EmailNotification>succeeding("backup");
        try (var s = NotificationService.builder()
                .register(Channels.email().provider(primary).fallbackTo(backup).build()).build()) {

            assertEquals("backup", s.send(EMAIL).provider());
        }
    }

    @Test
    void publishesSentEventsToSubscribers() {
        NotificationResult result = service.send(EMAIL);

        assertEquals(1, events.size());
        assertEquals(NotificationEvent.Type.SENT, events.get(0).type());
        assertEquals(result.id(), events.get(0).notificationId());
    }

    @Test
    void faultySubscriberDoesNotBreakDeliveryOrOtherSubscribers() {
        List<NotificationEvent> healthy = new ArrayList<>();
        try (var s = NotificationService.builder()
                .register(Channels.email().provider(emailProvider).build())
                .subscribe(e -> { throw new IllegalStateException("boom"); })
                .subscribe(healthy::add)
                .build()) {

            assertTrue(s.send(EMAIL).isSuccess());
            assertEquals(1, healthy.size());
        }
    }

    @Test
    void sendAsyncCompletesWithResult() {
        assertTrue(service.sendAsync(EMAIL).join().isSuccess());
    }

    @Test
    void sendAsyncCompletesExceptionallyWithTheSameExceptionTypes() {
        var future = service.sendAsync(EmailNotification.of("bad", "s", "b"));

        CompletionException e = assertThrows(CompletionException.class, future::join);

        assertInstanceOf(ValidationException.class, e.getCause());
    }

    @Test
    void sendAllReturnsOneResultPerNotificationInOrderAndNeverFails() {
        var batch = List.of(EMAIL, new SmsNotification("bad", "x"), new SmsNotification("+14155552671", "ok"));

        List<NotificationResult> results = service.sendAll(batch).join();

        assertEquals(3, results.size());
        assertEquals(List.of(DeliveryStatus.SENT, DeliveryStatus.REJECTED, DeliveryStatus.SENT),
                results.stream().map(NotificationResult::status).toList());
    }

    @Test
    void duplicateChannelRegistrationIsRejected() {
        var builder = NotificationService.builder().register(Channels.email().provider(emailProvider).build());

        assertThrows(IllegalArgumentException.class,
                () -> builder.register(Channels.email().provider(emailProvider).build()));
    }

    @Test
    void channelRequiresAtLeastOneProvider() {
        assertThrows(IllegalStateException.class, () -> Channels.email().build());
    }

    @Test
    void exceptionHierarchyAllowsCatchingEverythingAtOnce() {
        assertThrows(NotificationException.class, () -> service.send(EmailNotification.of("bad", "", "")));
        assertFalse(events.isEmpty());
    }
}
