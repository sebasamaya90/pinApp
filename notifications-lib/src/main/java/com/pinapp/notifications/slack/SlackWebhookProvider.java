package com.pinapp.notifications.slack;

import com.pinapp.notifications.config.Secret;
import com.pinapp.notifications.http.HttpNotificationProvider;
import com.pinapp.notifications.http.HttpRequest;
import com.pinapp.notifications.http.HttpResponse;
import com.pinapp.notifications.http.HttpTransport;
import com.pinapp.notifications.http.Json;
import com.pinapp.notifications.http.SimulatedHttpTransport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Slack Incoming Webhook: {@code POST <webhook url>} with {@code {"text": "...", "channel": "#x"}}.
 * Success is HTTP 200 with the plain-text body {@code ok} (no message id is returned). The webhook URL
 * is itself a credential, hence {@link Secret}.
 */
public final class SlackWebhookProvider extends HttpNotificationProvider<SlackNotification> {

    public record Config(Secret webhookUrl) {
        public Config {
            Objects.requireNonNull(webhookUrl, "webhookUrl");
        }
    }

    private final Config config;

    public SlackWebhookProvider(Config config) {
        this(config, simulatedApi());
    }

    public SlackWebhookProvider(Config config, HttpTransport transport) {
        super("slack-webhook", transport);
        this.config = Objects.requireNonNull(config);
    }

    @Override
    protected HttpRequest buildRequest(SlackNotification n) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", n.body());
        if (n.target() != null) {
            payload.put("channel", n.target());
        }
        return new HttpRequest("POST", config.webhookUrl().reveal(),
                Map.of("Content-Type", "application/json"), Json.write(payload));
    }

    @Override
    protected String extractMessageId(HttpResponse response) {
        return "n/a";
    }

    public static HttpTransport simulatedApi() {
        return new SimulatedHttpTransport(request -> new HttpResponse(200, Map.of(), "ok"));
    }
}
