package com.pinapp.notifications;

import com.pinapp.notifications.config.Secret;
import com.pinapp.notifications.template.MessageTemplate;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateAndSecretTest {

    @Test
    void rendersVariablesIncludingRepeatedAndSpacedOnes() {
        String out = MessageTemplate.of("Hi {{ name }}, {{name}}! Total: {{total}}$")
                .render(Map.of("name", "Ana", "total", 10));

        assertEquals("Hi Ana, Ana! Total: 10$", out);
    }

    @Test
    void missingVariablesFailFastListingAllOfThem() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> MessageTemplate.of("{{a}} {{b}}").render(Map.of()));

        assertTrue(e.getMessage().contains("[a, b]"));
    }

    @Test
    void secretNeverRevealsItselfByAccident() {
        Secret secret = Secret.of("super-secret");

        assertFalse(secret.toString().contains("super-secret"));
        assertEquals("super-secret", secret.reveal());
    }

    @Test
    void secretRejectsBlankAndMissingEnvironmentVariables() {
        assertThrows(IllegalArgumentException.class, () -> Secret.of(" "));
        assertThrows(IllegalStateException.class, () -> Secret.fromEnv("PINAPP_DEFINITELY_NOT_SET_12345"));
    }
}
