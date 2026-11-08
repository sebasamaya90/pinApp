package com.pinapp.notifications.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.pinapp.notifications.config.Secret;
import com.pinapp.notifications.http.HttpNotificationProvider;
import com.pinapp.notifications.http.HttpRequest;
import com.pinapp.notifications.http.HttpResponse;
import com.pinapp.notifications.http.HttpSupport;
import com.pinapp.notifications.http.HttpTransport;
import com.pinapp.notifications.http.Json;
import com.pinapp.notifications.http.SimulatedHttpTransport;
import com.pinapp.notifications.provider.ProviderException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Vonage SMS API: {@code POST /sms/json}, form fields api_key/api_secret/to/from/text (number without '+').
 * Quirk worth modelling: it answers HTTP 200 even on failure; the real result is
 * {@code messages[0].status} ("0" = ok, "1" = throttled, "5" = internal error...).
 */
public final class VonageSmsProvider extends HttpNotificationProvider<SmsNotification> {

    private static final List<String> TRANSIENT_STATUSES = List.of("1", "5");

    public record Config(String apiKey, Secret apiSecret, String from) {
        public Config {
            Objects.requireNonNull(apiSecret, "apiSecret");
            if (apiKey == null || apiKey.isBlank() || from == null || from.isBlank()) {
                throw new IllegalArgumentException("apiKey and from must not be blank");
            }
        }
    }

    private final Config config;

    public VonageSmsProvider(Config config) {
        this(config, simulatedApi());
    }

    public VonageSmsProvider(Config config, HttpTransport transport) {
        super("vonage", transport);
        this.config = Objects.requireNonNull(config);
    }

    @Override
    protected HttpRequest buildRequest(SmsNotification n) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("api_key", config.apiKey());
        form.put("api_secret", config.apiSecret().reveal());
        form.put("to", n.to().startsWith("+") ? n.to().substring(1) : n.to());
        form.put("from", config.from());
        form.put("text", n.body());

        return new HttpRequest("POST", "https://rest.nexmo.com/sms/json",
                Map.of("Content-Type", "application/x-www-form-urlencoded"), HttpSupport.formEncode(form));
    }

    @Override
    protected void verifyResponse(HttpResponse response) throws ProviderException {
        JsonNode first = Json.read(response.body()).path("messages").path(0);
        String status = first.path("status").asText("");
        if (!"0".equals(status)) {
            throw new ProviderException("vonage reported status " + status + ": " + first.path("error-text").asText("unknown error"),
                    TRANSIENT_STATUSES.contains(status), response.status(), null);
        }
    }

    @Override
    protected String extractMessageId(HttpResponse response) {
        return Json.read(response.body()).path("messages").path(0).path("message-id").asText("unknown");
    }

    public static HttpTransport simulatedApi() {
        return new SimulatedHttpTransport(request -> new HttpResponse(200, Map.of(), Json.write(Map.of(
                "message-count", "1",
                "messages", List.of(Map.of("status", "0", "message-id", UUID.randomUUID().toString()))))));
    }
}
