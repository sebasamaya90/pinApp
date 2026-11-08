package com.pinapp.notifications;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChannelTypeTest {

    @Test
    void normalizesNamesSoCustomChannelsCompareEqual() {
        assertEquals(ChannelType.of(" WhatsApp "), ChannelType.of("whatsapp"));
        assertEquals("email", ChannelType.EMAIL.toString());
    }

    @Test
    void rejectsBlankNames() {
        assertThrows(IllegalArgumentException.class, () -> ChannelType.of("  "));
        assertThrows(IllegalArgumentException.class, () -> ChannelType.of(null));
    }
}
