package com.pinapp.notifications.security;

import com.pinapp.notifications.email.EmailNotification;
import com.pinapp.notifications.email.EmailValidator;
import com.pinapp.notifications.http.HttpRequest;
import com.pinapp.notifications.http.HttpResponse;
import com.pinapp.notifications.http.JdkHttpTransport;
import com.pinapp.notifications.http.RetryAfter;
import com.pinapp.notifications.push.PushNotification;
import com.pinapp.notifications.push.PushValidator;
import com.pinapp.notifications.slack.SlackNotification;
import com.pinapp.notifications.slack.SlackValidator;
import com.pinapp.notifications.NotificationService;
import com.pinapp.notifications.channel.Channels;
import com.pinapp.notifications.support.FakeProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityHardeningTest {

    private final EmailValidator email = new EmailValidator();

    // ------------------------------------------------------------ header injection / size limits

    @Test
    void subjectWithLineBreaksIsRejectedToPreventHeaderInjection() {
        var errors = email.validate(EmailNotification.of("ana@example.com", "Hi\r\nBcc: attacker@evil.com", "body"));

        assertEquals("subject", errors.get(0).field());
        assertTrue(errors.get(0).message().contains("line breaks"));
    }

    @Test
    void addressesWithLineBreaksAreRejected() {
        var injected = EmailNotification.builder().to("ana@example.com\r\nBcc: evil@x.com").subject("s").body("b").build();

        assertEquals("to", email.validate(injected).get(0).field());
    }

    @Test
    void emailSizeLimitsAreEnforced() {
        var tooLong = EmailNotification.of("a@b.co", "x".repeat(EmailValidator.MAX_SUBJECT_LENGTH + 1), "b");
        var builder = EmailNotification.builder().subject("s").body("b");
        IntStream.rangeClosed(1, EmailValidator.MAX_RECIPIENTS + 1).forEach(i -> builder.to("u" + i + "@example.com"));

        assertEquals("subject", email.validate(tooLong).get(0).field());
        assertTrue(email.validate(builder.build()).get(0).message().contains("too many recipients"));
    }

    @Test
    void pushPayloadLimitsAreEnforced() {
        var validator = new PushValidator();
        var bigData = PushNotification.builder().deviceToken("t").body("b")
                .data("k", "v".repeat(PushValidator.MAX_DATA_BYTES)).build();
        var badTtl = PushNotification.builder().deviceToken("t").body("b").ttl(Duration.ofDays(29)).build();
        var negativeTtl = PushNotification.builder().deviceToken("t").body("b").ttl(Duration.ofSeconds(-1)).build();
        var fine = PushNotification.builder().deviceToken("t").body("b").ttl(Duration.ofDays(28)).build();

        assertEquals("data", validator.validate(bigData).get(0).field());
        assertEquals("ttl", validator.validate(badTtl).get(0).field());
        assertEquals("ttl", validator.validate(negativeTtl).get(0).field());
        assertTrue(validator.validate(fine).isEmpty());
    }

    @Test
    void slackLimitsAndTargetInjectionAreEnforced() {
        var validator = new SlackValidator();

        assertEquals("body", validator.validate(new SlackNotification(null, "x".repeat(SlackValidator.MAX_BODY_LENGTH + 1))).get(0).field());
        assertFalse(validator.validate(new SlackNotification("#ops\nattack", "hi")).isEmpty());
    }

    @Test
    void batchesAboveTheLimitAreRejectedBeforeAnyWork() {
        var provider = FakeProvider.<com.pinapp.notifications.sms.SmsNotification>succeeding("p");
        try (var service = NotificationService.builder()
                .register(Channels.sms().provider(provider).build()).maxBatchSize(2).build()) {
            var sms = new com.pinapp.notifications.sms.SmsNotification("+14155552671", "hi");

            assertThrows(IllegalArgumentException.class, () -> service.sendAll(List.of(sms, sms, sms)));

            assertEquals(0, provider.calls());
            assertEquals(2, service.sendAll(List.of(sms, sms)).join().size());
        }
    }

    // ------------------------------------------------------------ transport hardening

    @Test
    void plainHttpToARemoteHostIsRefusedAndTheMessageDoesNotLeakThePath() {
        var transport = new JdkHttpTransport();

        var e = assertThrows(IllegalArgumentException.class, () -> transport.execute(
                new HttpRequest("POST", "http://hooks.example.com/services/T000/B000/SECRET-TOKEN", Map.of(), "x")));

        assertTrue(e.getMessage().contains("hooks.example.com"));
        assertFalse(e.getMessage().contains("SECRET-TOKEN"));
    }

    @Test
    void otherSchemesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new JdkHttpTransport()
                .execute(new HttpRequest("GET", "file:///etc/passwd", Map.of(), "")));
    }

    @Test
    void oversizedResponsesAreTruncated() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        byte[] huge = "A".repeat(200_000).getBytes();
        server.createContext("/big", exchange -> {
            exchange.sendResponseHeaders(200, huge.length);
            try {
                exchange.getResponseBody().write(huge);
            } catch (IOException ignored) {
                // client stopped reading after the limit
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var transport = new JdkHttpTransport(new JdkHttpTransport.Config(Duration.ofSeconds(1), Duration.ofSeconds(2), 1024));

            HttpResponse response = transport.execute(
                    new HttpRequest("GET", "http://127.0.0.1:" + server.getAddress().getPort() + "/big", Map.of(), ""));

            assertEquals(200, response.status());
            assertEquals(1024, response.body().length());
        } finally {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------ masking

    @Test
    void maskingHidesPersonalData() {
        assertEquals("a***@example.com", Masking.email("ana@example.com"));
        assertEquals("***", Masking.email("not-an-email"));
        assertEquals("***", Masking.email(null));
        assertEquals("+54*********00", Masking.phone("+5491155550000"));
        assertEquals("***", Masking.phone("123"));
        assertEquals("abcd****", Masking.token("abcdefghijklmnop"));
        assertEquals("****", Masking.token("short"));
    }

    // ------------------------------------------------------------ Retry-After (seconds and HTTP-date)

    @Test
    void retryAfterAcceptsSecondsAndHttpDates() {
        Instant now = Instant.parse("2026-11-08T12:00:00Z");
        String in30s = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(now.plusSeconds(30), ZoneOffset.UTC));
        String past = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(now.minusSeconds(30), ZoneOffset.UTC));

        assertEquals(Duration.ofSeconds(7), RetryAfter.parse("7", now));
        assertEquals(Duration.ofSeconds(7), RetryAfter.parse(" 7 ", now));
        assertEquals(Duration.ofSeconds(30), RetryAfter.parse(in30s, now));
        assertEquals(Duration.ZERO, RetryAfter.parse(past, now));
    }

    @Test
    void retryAfterIsCappedAndGarbageIsIgnored() {
        Instant now = Instant.parse("2026-11-08T12:00:00Z");

        assertEquals(RetryAfter.MAX, RetryAfter.parse("86400", now));
        assertEquals(RetryAfter.MAX, RetryAfter.parse("99999999999999", now));
        assertNull(RetryAfter.parse("-5", now));
        assertNull(RetryAfter.parse("soon", now));
        assertNull(RetryAfter.parse("", now));
        assertNull(RetryAfter.parse(null, now));
    }
}
