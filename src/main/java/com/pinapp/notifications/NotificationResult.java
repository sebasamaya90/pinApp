package com.pinapp.notifications;

import com.pinapp.notifications.exception.DeliveryException;
import com.pinapp.notifications.exception.NotificationException;
import com.pinapp.notifications.exception.ValidationException;
import com.pinapp.notifications.provider.ProviderResponse;

import java.time.Instant;

/** Immutable outcome of one send operation. */
public record NotificationResult(
        String id,
        ChannelType channel,
        DeliveryStatus status,
        String provider,
        String providerMessageId,
        int attempts,
        Instant timestamp,
        String error) {

    public boolean isSuccess() {
        return status == DeliveryStatus.SENT;
    }

    public boolean isDuplicate() {
        return status == DeliveryStatus.DUPLICATE;
    }

    static NotificationResult duplicate(String id, ChannelType channel) {
        return new NotificationResult(id, channel, DeliveryStatus.DUPLICATE, "-", null, 0, Instant.now(),
                "already processed (same idempotency key)");
    }

    static NotificationResult sent(String id, ChannelType channel, ProviderResponse response) {
        return new NotificationResult(id, channel, DeliveryStatus.SENT, response.provider(),
                response.messageId(), response.attempts(), Instant.now(), null);
    }

    static NotificationResult failure(String id, ChannelType channel, NotificationException e) {
        return switch (e) {
            case ValidationException v -> new NotificationResult(id, channel, DeliveryStatus.REJECTED,
                    "-", null, 0, Instant.now(), v.getMessage());
            case DeliveryException d -> new NotificationResult(id, channel, DeliveryStatus.FAILED,
                    d.provider(), null, d.attempts(), Instant.now(), d.getMessage());
            default -> new NotificationResult(id, channel, DeliveryStatus.FAILED,
                    "-", null, 0, Instant.now(), e.getMessage());
        };
    }
}
