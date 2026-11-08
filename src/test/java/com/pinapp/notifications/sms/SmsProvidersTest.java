package com.pinapp.notifications.sms;

import com.pinapp.notifications.config.Secret;
import com.pinapp.notifications.provider.ProviderException;
import com.pinapp.notifications.provider.ProviderResponse;
import com.pinapp.notifications.support.ScriptedTransport;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmsProvidersTest {

    private static final SmsNotification SMS = new SmsNotification("+5491155550000", "Your code: 1234");

    @Test
    void twilioPostsToMessagesEndpointAndReturnsSid() throws Exception {
        var transport = ScriptedTransport.responding(201, "{\"sid\":\"SM123\",\"status\":\"queued\"}");
        var provider = new TwilioSmsProvider(new TwilioSmsProvider.Config("AC1", Secret.of("tok"), "+15005550006"), transport);

        ProviderResponse response = provider.send(SMS);

        String form = URLDecoder.decode(transport.lastRequest().body(), StandardCharsets.UTF_8);
        assertEquals("https://api.twilio.com/2010-04-01/Accounts/AC1/Messages.json", transport.lastRequest().url());
        assertTrue(form.contains("To=+5491155550000"));
        assertTrue(form.contains("From=+15005550006"));
        assertEquals("SM123", response.messageId());
    }

    @Test
    void twilioErrorsExposeCodeAndMessage() {
        var transport = ScriptedTransport.responding(400, "{\"code\":21211,\"message\":\"Invalid 'To' Phone Number\"}");
        var provider = new TwilioSmsProvider(new TwilioSmsProvider.Config("AC1", Secret.of("tok"), "+15005550006"), transport);

        ProviderException e = assertThrows(ProviderException.class, () -> provider.send(SMS));

        assertTrue(e.getMessage().contains("21211"));
        assertFalse(e.retryable());
    }

    @Test
    void vonageStripsPlusAndAcceptsStatusZero() throws Exception {
        var transport = ScriptedTransport.responding(200, "{\"messages\":[{\"status\":\"0\",\"message-id\":\"m-1\"}]}");
        var provider = new VonageSmsProvider(new VonageSmsProvider.Config("key", Secret.of("secret"), "PinApp"), transport);

        ProviderResponse response = provider.send(SMS);

        assertTrue(transport.lastRequest().body().contains("to=5491155550000"));
        assertEquals("m-1", response.messageId());
    }

    @Test
    void vonageFailureInsideHttp200IsDetected() {
        var throttled = ScriptedTransport.responding(200, "{\"messages\":[{\"status\":\"1\",\"error-text\":\"Throttled\"}]}");
        var invalid = ScriptedTransport.responding(200, "{\"messages\":[{\"status\":\"3\",\"error-text\":\"Invalid params\"}]}");
        var config = new VonageSmsProvider.Config("key", Secret.of("secret"), "PinApp");

        ProviderException transientError = assertThrows(ProviderException.class,
                () -> new VonageSmsProvider(config, throttled).send(SMS));
        ProviderException permanentError = assertThrows(ProviderException.class,
                () -> new VonageSmsProvider(config, invalid).send(SMS));

        assertTrue(transientError.retryable());
        assertFalse(permanentError.retryable());
        assertTrue(permanentError.getMessage().contains("Invalid params"));
    }

    @Test
    void simulatedApisAnswerSuccessfully() throws Exception {
        assertEquals("twilio", new TwilioSmsProvider(new TwilioSmsProvider.Config("AC", Secret.of("t"), "+1500")).send(SMS).provider());
        assertEquals("vonage", new VonageSmsProvider(new VonageSmsProvider.Config("k", Secret.of("s"), "App")).send(SMS).provider());
    }
}
