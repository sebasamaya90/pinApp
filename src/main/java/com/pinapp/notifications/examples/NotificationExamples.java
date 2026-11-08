package com.pinapp.notifications.examples;

import com.pinapp.notifications.NotificationResult;
import com.pinapp.notifications.NotificationService;
import com.pinapp.notifications.channel.Channels;
import com.pinapp.notifications.config.Secret;
import com.pinapp.notifications.email.EmailNotification;
import com.pinapp.notifications.email.MailgunEmailProvider;
import com.pinapp.notifications.email.SendGridEmailProvider;
import com.pinapp.notifications.exception.NotificationException;
import com.pinapp.notifications.exception.ValidationException;
import com.pinapp.notifications.http.HttpResponse;
import com.pinapp.notifications.http.SimulatedHttpTransport;
import com.pinapp.notifications.push.FcmPushProvider;
import com.pinapp.notifications.push.PushNotification;
import com.pinapp.notifications.retry.RetryPolicy;
import com.pinapp.notifications.slack.SlackChannel;
import com.pinapp.notifications.slack.SlackNotification;
import com.pinapp.notifications.slack.SlackWebhookProvider;
import com.pinapp.notifications.sms.SmsNotification;
import com.pinapp.notifications.sms.TwilioSmsProvider;
import com.pinapp.notifications.template.MessageTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Runnable demo. Everything is simulated: no network access and no real credentials are needed. */
public final class NotificationExamples {

    private NotificationExamples() {
    }

    public static void main(String[] args) {
        // In production use Secret.fromEnv("SENDGRID_API_KEY") etc. These are demo placeholders.
        var sendGrid = new SendGridEmailProvider(
                new SendGridEmailProvider.Config(Secret.of("SG.demo"), "no-reply@pinapp.dev", "PinApp"));
        var mailgun = new MailgunEmailProvider(
                new MailgunEmailProvider.Config(Secret.of("key-demo"), "mg.pinapp.dev", "PinApp <no-reply@mg.pinapp.dev>"));
        var twilio = new TwilioSmsProvider(
                new TwilioSmsProvider.Config("AC123", Secret.of("token-demo"), "+15005550006"));
        var fcm = new FcmPushProvider(new FcmPushProvider.Config("pinapp-demo", Secret.of("ya29.demo")));
        var slack = new SlackWebhookProvider(
                new SlackWebhookProvider.Config(Secret.of("https://hooks.slack.com/services/T000/B000/XXXX")));

        try (NotificationService service = NotificationService.builder()
                .register(Channels.email().provider(sendGrid).fallbackTo(mailgun)
                        .retryPolicy(RetryPolicy.exponentialBackoff(3, Duration.ofMillis(100), Duration.ofSeconds(1)))
                        .build())
                .register(Channels.sms().provider(twilio).build())
                .register(Channels.push().provider(fcm).build())
                .register(SlackChannel.builder().provider(slack).build())
                .subscribe(event -> System.out.printf("   [event] %s %s via %s (%s)%n",
                        event.channel(), event.type(), event.provider(), event.detail()))
                .build()) {

            section("1. Same API for every channel");
            show(service.send(EmailNotification.of("ana@example.com", "Welcome", "Hi Ana!")));
            show(service.send(new SmsNotification("+5491155550000", "Your code is 123456")));
            show(service.send(PushNotification.builder().deviceToken("fcm-token-abc").title("Order shipped")
                    .body("Your order #42 is on its way").data("orderId", "42").build()));
            show(service.send(new SlackNotification("#alerts", "Deploy finished")));

            section("2. Templates");
            String body = MessageTemplate.of("Hi {{name}}, your order {{order}} is ready")
                    .render(Map.of("name", "Luis", "order", "#77"));
            show(service.send(new SmsNotification("+5491155550001", body)));

            section("3. Validation errors vs delivery errors (try/catch)");
            try {
                service.send(EmailNotification.of("not-an-email", " ", "body"));
            } catch (ValidationException e) {
                System.out.println("   validation failed: " + e.errors());
            } catch (NotificationException e) {
                System.out.println("   delivery failed: " + e.getMessage());
            }

            section("4. Async batch (CompletableFuture)");
            List<NotificationResult> results = service.sendAll(List.of(
                    EmailNotification.of("a@example.com", "Hi", "A"),
                    new SmsNotification("12345", "invalid phone"),
                    PushNotification.builder().deviceToken("t1").body("B").build())).join();
            results.forEach(NotificationExamples::show);
        }

        section("5. Retries + failover (SendGrid is down, Mailgun takes over)");
        var down = new SimulatedHttpTransport(req -> new HttpResponse(503, Map.of(),
                "{\"errors\":[{\"message\":\"service unavailable\"}]}"));
        AtomicInteger calls = new AtomicInteger();
        var countingDown = new SimulatedHttpTransport(req -> {
            calls.incrementAndGet();
            return down.execute(req);
        });
        try (NotificationService resilient = NotificationService.builder()
                .register(Channels.email()
                        .provider(new SendGridEmailProvider(
                                new SendGridEmailProvider.Config(Secret.of("SG.demo"), "no-reply@pinapp.dev", null), countingDown))
                        .fallbackTo(mailgun)
                        .retryPolicy(RetryPolicy.fixedDelay(3, Duration.ofMillis(50)))
                        .build())
                .build()) {
            NotificationResult result = resilient.send(EmailNotification.of("ana@example.com", "Hi", "Failover!"));
            System.out.println("   SendGrid was called " + calls.get() + " times, delivered by: " + result.provider());
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title);
    }

    private static void show(NotificationResult r) {
        System.out.printf("   -> %s %s provider=%s id=%s attempts=%d%s%n", r.channel(), r.status(), r.provider(),
                r.providerMessageId(), r.attempts(), r.error() == null ? "" : " error=" + r.error());
    }
}
