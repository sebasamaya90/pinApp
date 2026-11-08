package com.pinapp.notifications.event;

/** Subscriber for notification status events (audit, metrics, alerting on another channel...). */
@FunctionalInterface
public interface NotificationListener {
    void onEvent(NotificationEvent event);
}
