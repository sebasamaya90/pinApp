package com.pinapp.notifications;

import com.pinapp.notifications.channel.StandardChannel;
import com.pinapp.notifications.provider.NotificationProvider;
import com.pinapp.notifications.provider.ProviderResponse;
import com.pinapp.notifications.validation.ValidationErrors;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Open/Closed proof: a brand-new "whatsapp" channel is defined ENTIRELY here, in test code, with no
 * change to any class of the library.
 */
class ExtensibilityTest {

    record WhatsAppNotification(String phone, String body) implements Notification {
        static final ChannelType WHATSAPP = ChannelType.of("whatsapp");

        @Override
        public ChannelType channel() {
            return WHATSAPP;
        }

        @Override
        public String recipient() {
            return phone;
        }
    }

    @Test
    void newChannelWorksThroughTheUnchangedService() {
        List<String> sent = new ArrayList<>();
        NotificationProvider<WhatsAppNotification> provider = new NotificationProvider<>() {
            @Override
            public String name() {
                return "fake-whatsapp-api";
            }

            @Override
            public ProviderResponse send(WhatsAppNotification n) {
                sent.add(n.phone() + ":" + n.body());
                return ProviderResponse.of(name(), "wa-1");
            }
        };

        try (var service = NotificationService.builder()
                .register(StandardChannel.builder(WhatsAppNotification.WHATSAPP, WhatsAppNotification.class,
                        n -> new ValidationErrors().requireNotBlank("phone", n.phone()).toList())
                        .provider(provider).build())
                .build()) {

            NotificationResult result = service.send(new WhatsAppNotification("+14155552671", "hola"));

            assertEquals("wa-1", result.providerMessageId());
            assertEquals(List.of("+14155552671:hola"), sent);
            assertThrows(com.pinapp.notifications.exception.ValidationException.class,
                    () -> service.send(new WhatsAppNotification(" ", "hola")));
        }
    }
}
