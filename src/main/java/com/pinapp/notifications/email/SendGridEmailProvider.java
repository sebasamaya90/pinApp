package com.pinapp.notifications.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.pinapp.notifications.config.Secret;
import com.pinapp.notifications.http.HttpNotificationProvider;
import com.pinapp.notifications.http.HttpRequest;
import com.pinapp.notifications.http.HttpResponse;
import com.pinapp.notifications.http.HttpTransport;
import com.pinapp.notifications.http.Json;
import com.pinapp.notifications.http.SimulatedHttpTransport;
import com.pinapp.notifications.provider.DeliveryContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * SendGrid v3 Mail Send: {@code POST /v3/mail/send}, Bearer API key, JSON body with
 * personalizations (to/cc/bcc), from, subject and content. Success is HTTP 202 with the id in
 * {@code X-Message-Id}. The notification id is attached as {@code personalizations[0].custom_args.notification_id}
 * so it comes back in SendGrid's event webhooks.
 */
public final class SendGridEmailProvider extends HttpNotificationProvider<EmailNotification> {

    private static final String ENDPOINT = "https://api.sendgrid.com/v3/mail/send";

    public record Config(Secret apiKey, String fromEmail, String fromName) {
        public Config {
            Objects.requireNonNull(apiKey, "apiKey");
            if (fromEmail == null || fromEmail.isBlank()) {
                throw new IllegalArgumentException("fromEmail must not be blank");
            }
        }
    }

    private final Config config;

    public SendGridEmailProvider(Config config) {
        this(config, simulatedApi());
    }

    public SendGridEmailProvider(Config config, HttpTransport transport) {
        super("sendgrid", transport);
        this.config = Objects.requireNonNull(config);
    }

    @Override
    protected HttpRequest buildRequest(EmailNotification n) {
        Map<String, Object> from = new LinkedHashMap<>();
        from.put("email", config.fromEmail());
        if (config.fromName() != null) {
            from.put("name", config.fromName());
        }
        Map<String, Object> personalization = new LinkedHashMap<>();
        personalization.put("to", addresses(n.to()));
        if (!n.cc().isEmpty()) {
            personalization.put("cc", addresses(n.cc()));
        }
        if (!n.bcc().isEmpty()) {
            personalization.put("bcc", addresses(n.bcc()));
        }
        // custom_args goes inside the personalization: the docs type it as an object there (key/value strings).
        DeliveryContext.current().ifPresent(context ->
                personalization.put("custom_args", Map.of("notification_id", context.notificationId())));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("personalizations", List.of(personalization));
        payload.put("from", from);
        payload.put("subject", n.subject());
        payload.put("content", List.of(Map.of("type", n.html() ? "text/html" : "text/plain", "value", n.body())));

        return new HttpRequest("POST", ENDPOINT,
                Map.of("Authorization", "Bearer " + config.apiKey().reveal(), "Content-Type", "application/json"),
                Json.write(payload));
    }

    private static List<Map<String, String>> addresses(List<String> addresses) {
        return addresses.stream().map(address -> Map.of("email", address)).toList();
    }

    @Override
    protected String extractMessageId(HttpResponse response) {
        return response.header("X-Message-Id").orElse("unknown");
    }

    @Override
    protected String describeError(HttpResponse response) {
        List<String> messages = new ArrayList<>();
        for (JsonNode error : Json.read(response.body()).path("errors")) {
            messages.add(error.path("message").asText());
        }
        return messages.isEmpty() ? super.describeError(response) : String.join("; ", messages);
    }

    public static HttpTransport simulatedApi() {
        return new SimulatedHttpTransport(request ->
                new HttpResponse(202, Map.of("X-Message-Id", "sg-" + UUID.randomUUID()), ""));
    }
}
