package com.pinapp.notifications.exception;

import com.pinapp.notifications.ChannelType;

/**
 * Root of the library's (unchecked) exception hierarchy. Callers can catch this type for "anything
 * went wrong" or a subtype to distinguish validation errors from delivery errors.
 */
public class NotificationException extends RuntimeException {

    private final transient ChannelType channel;

    public NotificationException(ChannelType channel, String message, Throwable cause) {
        super(message, cause);
        this.channel = channel;
    }

    public ChannelType channel() {
        return channel;
    }
}
