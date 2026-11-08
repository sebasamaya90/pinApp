package com.pinapp.notifications;

public enum DeliveryStatus {
    /** The provider accepted the notification. */
    SENT,
    /** The notification was valid but could not be delivered (provider error, channel missing...). */
    FAILED,
    /** The notification was rejected before sending because it is invalid. */
    REJECTED,
    /** Not sent: the same idempotency key was already processed (a successful earlier send). */
    DUPLICATE
}
