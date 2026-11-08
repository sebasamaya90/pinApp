package com.pinapp.notifications.channel;

import com.pinapp.notifications.ChannelType;
import com.pinapp.notifications.Notification;
import com.pinapp.notifications.exception.NotificationException;
import com.pinapp.notifications.provider.ProviderResponse;

/**
 * A fully configured delivery channel. {@code NotificationService} only depends on this interface,
 * so new channels are added by registering another implementation, never by editing the service.
 */
public interface NotificationChannel {

    ChannelType type();

    /**
     * Validates and delivers the notification.
     *
     * @throws NotificationException ValidationException or DeliveryException
     */
    ProviderResponse deliver(Notification notification);
}
