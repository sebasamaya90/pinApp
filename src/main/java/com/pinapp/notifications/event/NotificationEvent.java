package com.pinapp.notifications.event;

import com.pinapp.notifications.ChannelType;

import java.time.Instant;

/** Status change published to every {@link NotificationListener} (Observer / Pub-Sub). */
public record NotificationEvent(
        String notificationId,
        ChannelType channel,
        Type type,
        String provider,
        String detail,
        Instant occurredAt) {

    public enum Type { SENT, FAILED, REJECTED }
}
