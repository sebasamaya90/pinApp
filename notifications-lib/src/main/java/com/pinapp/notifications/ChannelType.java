package com.pinapp.notifications;

import java.util.Locale;

/**
 * Identifies a delivery channel. Deliberately a value object and NOT an enum: an enum is closed,
 * so adding a channel would force a change in core code (Open/Closed Principle).
 * Anyone can define {@code ChannelType.of("whatsapp")} from outside the library.
 */
public record ChannelType(String name) {

    public static final ChannelType EMAIL = new ChannelType("email");
    public static final ChannelType SMS = new ChannelType("sms");
    public static final ChannelType PUSH = new ChannelType("push");

    public ChannelType {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Channel name must not be blank");
        }
        name = name.trim().toLowerCase(Locale.ROOT);
    }

    public static ChannelType of(String name) {
        return new ChannelType(name);
    }

    @Override
    public String toString() {
        return name;
    }
}
