package com.pinapp.notifications.email;

import com.pinapp.notifications.config.Secret;
import com.pinapp.notifications.http.HttpNotificationProvider;
import com.pinapp.notifications.http.HttpRequest;
import com.pinapp.notifications.http.HttpResponse;
import com.pinapp.notifications.http.HttpSupport;
import com.pinapp.notifications.http.HttpTransport;
import com.pinapp.notifications.http.Json;
import com.pinapp.notifications.http.SimulatedHttpTransport;
import com.pinapp.notifications.provider.DeliveryContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Mailgun Messages API: {@code POST /v3/{domain}/messages}, HTTP Basic ("api":key), form-encoded body
 * (from/to/cc/bcc/subject/text|html; several recipients are comma separated). Success is HTTP 200 with
 * JSON {@code {"id": "<...>", "message": "Queued..."}}. The notification id travels as the custom
 * variable {@code v:notification-id} (returned in Mailgun webhooks as {@code user-variables}).
 */
public final class MailgunEmailProvider extends HttpNotificationProvider<EmailNotification> {

    public record Config(Secret apiKey, String domain, String from) {
        public Config {
            Objects.requireNonNull(apiKey, "apiKey");
            if (domain == null || domain.isBlank() || from == null || from.isBlank()) {
                throw new IllegalArgumentException("domain and from must not be blank");
            }
        }
    }

    private final Config config;

    public MailgunEmailProvider(Config config) {
        this(config, simulatedApi());
    }

    public MailgunEmailProvider(Config config, HttpTransport transport) {
        super("mailgun", transport);
        this.config = Objects.requireNonNull(config);
    }

    @Override
    protected HttpRequest buildRequest(EmailNotification n) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("from", config.from());
        form.put("to", String.join(",", n.to()));
        if (!n.cc().isEmpty()) {
            form.put("cc", String.join(",", n.cc()));
        }
        if (!n.bcc().isEmpty()) {
            form.put("bcc", String.join(",", n.bcc()));
        }
        form.put("subject", n.subject());
        form.put(n.html() ? "html" : "text", n.body());
        DeliveryContext.current().ifPresent(context -> form.put("v:notification-id", context.notificationId()));

        return new HttpRequest("POST", "https://api.mailgun.net/v3/" + config.domain() + "/messages",
                Map.of("Authorization", HttpSupport.basicAuth("api", config.apiKey().reveal()),
                        "Content-Type", "application/x-www-form-urlencoded"),
                HttpSupport.formEncode(form));
    }

    @Override
    protected String extractMessageId(HttpResponse response) {
        return Json.read(response.body()).path("id").asText("unknown");
    }

    @Override
    protected String describeError(HttpResponse response) {
        String message = Json.read(response.body()).path("message").asText();
        return message.isEmpty() ? super.describeError(response) : message;
    }

    public static HttpTransport simulatedApi() {
        return new SimulatedHttpTransport(request -> new HttpResponse(200, Map.of(),
                Json.write(Map.of("id", "<" + UUID.randomUUID() + "@mailgun.sim>", "message", "Queued. Thank you."))));
    }
}
