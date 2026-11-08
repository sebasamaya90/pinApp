package com.pinapp.notifications.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.pinapp.notifications.config.Secret;
import com.pinapp.notifications.http.Json;
import com.pinapp.notifications.provider.ProviderException;
import com.pinapp.notifications.provider.ProviderResponse;
import com.pinapp.notifications.slack.SlackNotification;
import com.pinapp.notifications.slack.SlackWebhookProvider;
import com.pinapp.notifications.support.ScriptedTransport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PushAndSlackProvidersTest {

    private static final FcmPushProvider.Config FCM = new FcmPushProvider.Config("proj-1", Secret.of("ya29.token"));

    @Test
    void fcmBuildsV1MessageWithNotificationAndData() throws Exception {
        var transport = ScriptedTransport.responding(200, "{\"name\":\"projects/proj-1/messages/0:99\"}");
        var push = PushNotification.builder().deviceToken("tok-1").title("Hi").body("There").data("k", "v").build();

        ProviderResponse response = new FcmPushProvider(FCM, transport).send(push);

        JsonNode message = Json.read(transport.lastRequest().body()).path("message");
        assertEquals("https://fcm.googleapis.com/v1/projects/proj-1/messages:send", transport.lastRequest().url());
        assertEquals("Bearer ya29.token", transport.lastRequest().headers().get("Authorization"));
        assertEquals("tok-1", message.path("token").asText());
        assertEquals("Hi", message.at("/notification/title").asText());
        assertEquals("v", message.at("/data/k").asText());
        assertEquals("projects/proj-1/messages/0:99", response.messageId());
    }

    @Test
    void fcmOmitsEmptyTitleAndData() throws Exception {
        var transport = ScriptedTransport.responding(200, "{\"name\":\"n\"}");

        new FcmPushProvider(FCM, transport).send(PushNotification.builder().deviceToken("t").body("b").build());

        JsonNode message = Json.read(transport.lastRequest().body()).path("message");
        assertTrue(message.path("notification").path("title").isMissingNode());
        assertTrue(message.path("data").isMissingNode());
    }

    @Test
    void fcmUnregisteredTokenIsPermanentFailure() {
        var transport = ScriptedTransport.responding(404, "{\"error\":{\"status\":\"NOT_FOUND\",\"message\":\"Requested entity was not found\"}}");

        ProviderException e = assertThrows(ProviderException.class, () -> new FcmPushProvider(FCM, transport)
                .send(PushNotification.builder().deviceToken("dead").body("b").build()));

        assertFalse(e.retryable());
        assertTrue(e.getMessage().contains("NOT_FOUND"));
    }

    @Test
    void slackPostsOnlyTheTextBecauseTheWebhookFixesTheChannel() throws Exception {
        var transport = ScriptedTransport.responding(200, "ok");
        var provider = new SlackWebhookProvider(
                new SlackWebhookProvider.Config(Secret.of("https://hooks.slack.com/services/T/B/SECRET")), transport);

        provider.send(new SlackNotification("Deploy done"));

        JsonNode json = Json.read(transport.lastRequest().body());
        assertEquals("Deploy done", json.path("text").asText());
        assertTrue(json.path("channel").isMissingNode(), "modern webhooks cannot override the channel");
        assertFalse(transport.lastRequest().toString().contains("SECRET"), "webhook URL must not be logged");
    }

    @Test
    void slackErrorsAreReported() {
        var transport = ScriptedTransport.responding(404, "no_service");
        var provider = new SlackWebhookProvider(
                new SlackWebhookProvider.Config(Secret.of("https://hooks.slack.com/services/T/B/X")), transport);

        ProviderException e = assertThrows(ProviderException.class, () -> provider.send(new SlackNotification("x")));

        assertTrue(e.getMessage().contains("no_service"));
    }

    @Test
    void simulatedApiAnswersSuccessfully() throws Exception {
        assertEquals("fcm", new FcmPushProvider(FCM)
                .send(PushNotification.builder().deviceToken("t").body("b").build()).provider());
    }
}
