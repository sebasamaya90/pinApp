package com.pinapp.notifications.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.pinapp.notifications.config.Secret;
import com.pinapp.notifications.http.HttpRequest;
import com.pinapp.notifications.http.Json;
import com.pinapp.notifications.provider.ProviderException;
import com.pinapp.notifications.provider.ProviderResponse;
import com.pinapp.notifications.support.ScriptedTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailProvidersTest {

    private static final EmailNotification EMAIL = EmailNotification.of("ana@example.com", "Hello", "Body text");
    private static final SendGridEmailProvider.Config SG_CONFIG =
            new SendGridEmailProvider.Config(Secret.of("SG.secret"), "no-reply@pinapp.dev", "PinApp");

    @Test
    void sendGridBuildsTheV3MailSendPayload() throws Exception {
        var transport = ScriptedTransport.responding(202, Map.of("X-Message-Id", "abc123"), "");
        var provider = new SendGridEmailProvider(SG_CONFIG, transport);

        ProviderResponse response = provider.send(EMAIL);

        HttpRequest request = transport.lastRequest();
        JsonNode json = Json.read(request.body());
        assertEquals("POST", request.method());
        assertEquals("https://api.sendgrid.com/v3/mail/send", request.url());
        assertEquals("Bearer SG.secret", request.headers().get("Authorization"));
        assertEquals("ana@example.com", json.at("/personalizations/0/to/0/email").asText());
        assertEquals("no-reply@pinapp.dev", json.at("/from/email").asText());
        assertEquals("Hello", json.path("subject").asText());
        assertEquals("text/plain", json.at("/content/0/type").asText());
        assertEquals("abc123", response.messageId());
        assertEquals("sendgrid", response.provider());
    }

    @Test
    void sendGridUsesHtmlContentType() throws Exception {
        var transport = ScriptedTransport.responding(202, "");
        new SendGridEmailProvider(SG_CONFIG, transport)
                .send(EmailNotification.builder().to("a@b.co").subject("s").body("<b>x</b>").html(true).build());

        assertEquals("text/html", Json.read(transport.lastRequest().body()).at("/content/0/type").asText());
    }

    @Test
    void sendGridClientErrorIsPermanentAndReadable() {
        var transport = ScriptedTransport.responding(400, "{\"errors\":[{\"message\":\"The from address is invalid\"}]}");

        ProviderException e = assertThrows(ProviderException.class,
                () -> new SendGridEmailProvider(SG_CONFIG, transport).send(EMAIL));

        assertFalse(e.retryable());
        assertEquals(400, e.statusCode());
        assertTrue(e.getMessage().contains("The from address is invalid"));
    }

    @Test
    void serverErrorsRateLimitsAndNetworkFailuresAreRetryable() {
        for (int status : new int[]{429, 500, 503}) {
            var transport = ScriptedTransport.responding(status, "");
            ProviderException e = assertThrows(ProviderException.class,
                    () -> new SendGridEmailProvider(SG_CONFIG, transport).send(EMAIL));
            assertTrue(e.retryable(), "HTTP " + status + " should be retryable");
        }
        var broken = new ScriptedTransport().thenFail(new IOException("connection reset"));
        ProviderException e = assertThrows(ProviderException.class,
                () -> new SendGridEmailProvider(SG_CONFIG, broken).send(EMAIL));
        assertTrue(e.retryable());
    }

    @Test
    void credentialsNeverAppearInRequestToStringOrConfigToString() throws Exception {
        var transport = ScriptedTransport.responding(202, "");
        new SendGridEmailProvider(SG_CONFIG, transport).send(EMAIL);

        assertFalse(transport.lastRequest().toString().contains("SG.secret"));
        assertFalse(SG_CONFIG.toString().contains("SG.secret"));
    }

    @Test
    void mailgunSendsFormEncodedMessageWithBasicAuth() throws Exception {
        var transport = ScriptedTransport.responding(200, "{\"id\":\"<2024@mg.pinapp.dev>\",\"message\":\"Queued. Thank you.\"}");
        var provider = new MailgunEmailProvider(
                new MailgunEmailProvider.Config(Secret.of("key-1"), "mg.pinapp.dev", "PinApp <no-reply@mg.pinapp.dev>"), transport);

        ProviderResponse response = provider.send(EMAIL);

        HttpRequest request = transport.lastRequest();
        String body = URLDecoder.decode(request.body(), StandardCharsets.UTF_8);
        assertEquals("https://api.mailgun.net/v3/mg.pinapp.dev/messages", request.url());
        assertTrue(request.headers().get("Authorization").startsWith("Basic "));
        assertTrue(body.contains("to=ana@example.com"));
        assertTrue(body.contains("text=Body text"));
        assertEquals("<2024@mg.pinapp.dev>", response.messageId());
    }

    @Test
    void simulatedApisAnswerSuccessfully() throws Exception {
        assertEquals("sendgrid", new SendGridEmailProvider(SG_CONFIG).send(EMAIL).provider());
        assertEquals("mailgun", new MailgunEmailProvider(
                new MailgunEmailProvider.Config(Secret.of("k"), "d.com", "a@d.com")).send(EMAIL).provider());
    }
}
