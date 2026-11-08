package com.pinapp.notifications.slack;

import com.pinapp.notifications.config.Secret;
import com.pinapp.notifications.http.HttpNotificationProvider;
import com.pinapp.notifications.http.HttpRequest;
import com.pinapp.notifications.http.HttpResponse;
import com.pinapp.notifications.http.HttpTransport;
import com.pinapp.notifications.http.Json;
import com.pinapp.notifications.http.SimulatedHttpTransport;

import java.util.Map;
import java.util.Objects;

/**
 * Slack Incoming Webhook: {@code POST <webhook url>} with {@code {"text": "..."}}. The channel,
 * username and icon cannot be overridden: they come from the webhook itself. Success is HTTP 200 with
 * the plain-text body {@code ok} (no message id is returned). Errors are 400/403/404 with a plain-text
 * reason ({@code invalid_payload}, {@code no_service}, {@code channel_not_found}...), and HTTP 429 with
 * {@code Retry-After} (seconds) when posting faster than about 1 message per second per channel
 * (short bursts are allowed). The webhook URL is itself a credential, hence {@link Secret}.
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
        return new HttpRequest("POST", config.webhookUrl().reveal(),
                Map.of("Content-Type", "application/json"), Json.write(Map.of("text", n.body())));
    }

    @Override
    protected String extractMessageId(HttpResponse response) {
        return "n/a";
    }

    public static HttpTransport simulatedApi() {
        return new SimulatedHttpTransport(request -> new HttpResponse(200, Map.of(), "ok"));
    }
}
