package com.pinapp.notifications.provider;

import com.pinapp.notifications.Notification;

/**
 * SPI implemented once per external vendor (SendGrid, Twilio, FCM...). A provider only knows how to
 * translate a notification into the vendor's API; validation, retries and routing live elsewhere.
 * Swapping vendors is therefore a one-line change in configuration (Liskov / Dependency Inversion).
 */
public interface NotificationProvider<N extends Notification> {

    /** Stable vendor name used in logs, results and events (e.g. "sendgrid"). */
    String name();

    ProviderResponse send(N notification) throws ProviderException;
}
