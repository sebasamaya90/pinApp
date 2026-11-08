package com.pinapp.notifications;

/**
 * Common abstraction for everything that can be sent. Each channel defines its own concrete
 * notification (an email has a subject, an SMS does not) while the client code always talks to
 * {@link NotificationService#send(Notification)}.
 */
public interface Notification {

    /** Channel this notification must be routed to. */
    ChannelType channel();

    /** Destination (address, phone number, device token...). */
    String recipient();

    /** Main text of the message. */
    String body();
}
