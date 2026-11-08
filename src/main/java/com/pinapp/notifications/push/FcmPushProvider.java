package com.pinapp.notifications.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.pinapp.notifications.config.Secret;
import com.pinapp.notifications.config.TokenProvider;
import com.pinapp.notifications.http.HttpNotificationProvider;
import com.pinapp.notifications.http.HttpRequest;
import com.pinapp.notifications.http.HttpResponse;
import com.pinapp.notifications.http.HttpTransport;
import com.pinapp.notifications.http.Json;
import com.pinapp.notifications.http.SimulatedHttpTransport;
import com.pinapp.notifications.provider.ProviderException;

import java.io.IOException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Firebase Cloud Messaging HTTP v1: {@code POST /v1/projects/{projectId}/messages:send}, Bearer OAuth2
 * access token, body {@code {"message": {"token", "notification": {"title","body"}, "data": {...},
 * "android": {...}, "apns": {...}}}}. Success is HTTP 200 with {@code {"name": "projects/{id}/messages/{id}"}}.
 *
 * <p>The access token is short-lived (about 1 hour). It comes from a {@link TokenProvider}: use
 * {@code RefreshingTokenProvider} (backed by e.g. Google's credentials library) for automatic refresh. An
 * HTTP 401 invalidates the cached token and is reported as retryable so the next attempt uses a new one.
 *
 * <p>Priority and TTL map to {@code android.priority}/{@code android.ttl} and to the APNs headers
 * {@code apns-priority}/{@code apns-expiration} (absolute epoch seconds, hence the clock).
 */
public final class FcmPushProvider extends HttpNotificationProvider<PushNotification> {

    public record Config(String projectId, TokenProvider tokens) {
        public Config {
            Objects.requireNonNull(tokens, "tokens");
            if (projectId == null || projectId.isBlank()) {
                throw new IllegalArgumentException("projectId must not be blank");
            }
        }

        /** Convenience for a fixed token (tests, short-lived scripts). */
        public Config(String projectId, Secret accessToken) {
            this(projectId, TokenProvider.fixed(accessToken));
        }
    }

    private static final Duration MIN_QUOTA_BACKOFF = Duration.ofMinutes(1);

    private final Config config;
    private final InstantSource clock;

    public FcmPushProvider(Config config) {
        this(config, simulatedApi(config.projectId()));
    }

    public FcmPushProvider(Config config, HttpTransport transport) {
        this(config, transport, InstantSource.system());
    }

    public FcmPushProvider(Config config, HttpTransport transport, InstantSource clock) {
        super("fcm", transport, clock);
        this.config = Objects.requireNonNull(config);
        this.clock = clock;
    }

    @Override
    protected HttpRequest buildRequest(PushNotification n) throws ProviderException {
        Secret token;
        try {
            token = config.tokens().currentToken();
        } catch (IOException e) {
            throw new ProviderException(name() + ": cannot obtain an access token (" + e.getMessage() + ")",
                    true, null, e);
        }

        Map<String, Object> notification = new LinkedHashMap<>();
        if (n.title() != null && !n.title().isBlank()) {
            notification.put("title", n.title());
        }
        notification.put("body", n.body());

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("token", n.deviceToken());
        message.put("notification", notification);
        if (!n.data().isEmpty()) {
            message.put("data", n.data());
        }
        addDeliveryOptions(message, n);

        return new HttpRequest("POST",
                "https://fcm.googleapis.com/v1/projects/" + config.projectId() + "/messages:send",
                Map.of("Authorization", "Bearer " + token.reveal(), "Content-Type", "application/json"),
                Json.write(Map.of("message", message)));
    }

    private void addDeliveryOptions(Map<String, Object> message, PushNotification n) {
        boolean high = n.priority() == PushNotification.Priority.HIGH;

        Map<String, Object> android = new LinkedHashMap<>();
        Map<String, Object> apnsHeaders = new LinkedHashMap<>();
        if (high) {
            android.put("priority", "high");
            apnsHeaders.put("apns-priority", "10");
        }
        if (n.ttl() != null) {
            android.put("ttl", n.ttl().toSeconds() + "s");
            apnsHeaders.put("apns-expiration",
                    String.valueOf(n.ttl().isZero() ? 0 : clock.instant().plus(n.ttl()).getEpochSecond()));
        }
        if (!android.isEmpty()) {
            message.put("android", android);
        }
        if (!apnsHeaders.isEmpty()) {
            message.put("apns", Map.of("headers", apnsHeaders));
        }
    }

    /**
     * HTTP 401 has two very different causes in FCM:
     * <ul>
     *   <li>{@code THIRD_PARTY_AUTH_ERROR}: the APNs certificate / web push key configured in Firebase is
     *       invalid. Refreshing our OAuth token cannot fix it, so it is NOT retryable;</li>
     *   <li>anything else (Google's standard {@code UNAUTHENTICATED} for an expired or invalid OAuth token):
     *       the cached token is discarded and the call is retryable with a fresh one.</li>
     * </ul>
     */
    @Override
    protected boolean isRetryable(HttpResponse response) {
        if (response.status() == 401) {
            if ("THIRD_PARTY_AUTH_ERROR".equals(errorStatus(response))) {
                return false;
            }
            config.tokens().invalidate();
            return true;
        }
        return super.isRetryable(response);
    }

    /**
     * FCM asks to back off exponentially starting at 1 minute when {@code QUOTA_EXCEEDED} (HTTP 429), and to
     * honour {@code Retry-After} on {@code UNAVAILABLE} (503). Retrying within milliseconds would risk
     * getting the sender denylisted, so a 429 never asks for less than a minute (an overall deadline in
     * {@code Resilience} will then stop the retries instead of hammering FCM).
     */
    @Override
    protected Duration retryAfter(HttpResponse response) {
        Duration requested = super.retryAfter(response);
        if (response.status() == 429 && (requested == null || requested.compareTo(MIN_QUOTA_BACKOFF) < 0)) {
            return MIN_QUOTA_BACKOFF;
        }
        return requested;
    }

    private static String errorStatus(HttpResponse response) {
        return Json.read(response.body()).path("error").path("status").asText("");
    }

    @Override
    protected String extractMessageId(HttpResponse response) {
        return Json.read(response.body()).path("name").asText("unknown");
    }

    @Override
    protected String describeError(HttpResponse response) {
        JsonNode error = Json.read(response.body()).path("error");
        return error.isMissingNode() ? super.describeError(response)
                : error.path("status").asText("?") + " " + error.path("message").asText();
    }

    public static HttpTransport simulatedApi(String projectId) {
        return new SimulatedHttpTransport(request -> new HttpResponse(200, Map.of(), Json.write(
                Map.of("name", "projects/" + projectId + "/messages/0:" + System.nanoTime()))));
    }
}
