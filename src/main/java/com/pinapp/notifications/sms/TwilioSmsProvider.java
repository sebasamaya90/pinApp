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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Twilio Programmable Messaging: {@code POST /2010-04-01/Accounts/{sid}/Messages.json}, HTTP Basic
 * (accountSid:authToken), form fields To/From/Body. Success is HTTP 201 with JSON
 * {@code {"sid": "SM...", "status": "queued"}}; errors carry {@code {"code": 21211, "message": "..."}}.
 */
public final class TwilioSmsProvider extends HttpNotificationProvider<SmsNotification> {

    public record Config(String accountSid, Secret authToken, String fromNumber) {
        public Config {
            Objects.requireNonNull(authToken, "authToken");
            if (accountSid == null || accountSid.isBlank() || fromNumber == null || fromNumber.isBlank()) {
                throw new IllegalArgumentException("accountSid and fromNumber must not be blank");
            }
        }
    }

    private final Config config;

    public TwilioSmsProvider(Config config) {
        this(config, simulatedApi());
    }

    public TwilioSmsProvider(Config config, HttpTransport transport) {
        super("twilio", transport);
        this.config = Objects.requireNonNull(config);
    }

    @Override
    protected HttpRequest buildRequest(SmsNotification n) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("To", n.to());
        form.put("From", config.fromNumber());
        form.put("Body", n.body());

        return new HttpRequest("POST",
                "https://api.twilio.com/2010-04-01/Accounts/" + config.accountSid() + "/Messages.json",
                Map.of("Authorization", HttpSupport.basicAuth(config.accountSid(), config.authToken().reveal()),
                        "Content-Type", "application/x-www-form-urlencoded"),
                HttpSupport.formEncode(form));
    }

    @Override
    protected String extractMessageId(HttpResponse response) {
        return Json.read(response.body()).path("sid").asText("unknown");
    }

    @Override
    protected String describeError(HttpResponse response) {
        JsonNode json = Json.read(response.body());
        return json.has("message") ? json.path("code").asText("?") + " " + json.path("message").asText()
                : super.describeError(response);
    }

    public static HttpTransport simulatedApi() {
        return new SimulatedHttpTransport(request -> new HttpResponse(201, Map.of(),
                Json.write(Map.of("sid", "SM" + UUID.randomUUID().toString().replace("-", ""), "status", "queued"))));
    }
}
