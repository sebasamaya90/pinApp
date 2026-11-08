package com.pinapp.notifications.push;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Firebase Cloud Messaging HTTP v1: {@code POST /v1/projects/{projectId}/messages:send}, Bearer OAuth2
 * access token, body {@code {"message": {"token", "notification": {"title","body"}, "data": {...}}}}.
 * Success is HTTP 200 with {@code {"name": "projects/{id}/messages/{messageId}"}}. The access token is
 * short-lived (obtained from a service account); refreshing it is the caller's/transport's concern.
 */
public final class FcmPushProvider extends HttpNotificationProvider<PushNotification> {

    public record Config(String projectId, Secret accessToken) {
        public Config {
            Objects.requireNonNull(accessToken, "accessToken");
            if (projectId == null || projectId.isBlank()) {
                throw new IllegalArgumentException("projectId must not be blank");
            }
        }
    }

    private final Config config;

    public FcmPushProvider(Config config) {
        this(config, simulatedApi(config.projectId()));
    }

    public FcmPushProvider(Config config, HttpTransport transport) {
        super("fcm", transport);
        this.config = Objects.requireNonNull(config);
    }

    @Override
    protected HttpRequest buildRequest(PushNotification n) {
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

        return new HttpRequest("POST",
                "https://fcm.googleapis.com/v1/projects/" + config.projectId() + "/messages:send",
                Map.of("Authorization", "Bearer " + config.accessToken().reveal(),
                        "Content-Type", "application/json"),
                Json.write(Map.of("message", message)));
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
