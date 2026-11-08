package com.pinapp.notifications.exception;

import com.pinapp.notifications.ChannelType;

/** The notification was valid but the provider failed to deliver it (after any retries). */
public class DeliveryException extends NotificationException {

    private final String provider;
    private final int attempts;

    public DeliveryException(ChannelType channel, String provider, int attempts, String message, Throwable cause) {
        super(channel, "Delivery via " + provider + " failed after " + attempts + " attempt(s): " + message, cause);
        this.provider = provider;
        this.attempts = attempts;
    }

    public String provider() {
        return provider;
    }

    public int attempts() {
        return attempts;
    }
}
