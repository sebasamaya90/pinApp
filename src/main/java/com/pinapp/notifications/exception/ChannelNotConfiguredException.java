package com.pinapp.notifications.exception;

import com.pinapp.notifications.ChannelType;

/** No channel has been registered for the notification's {@link ChannelType}. */
public class ChannelNotConfiguredException extends NotificationException {

    public ChannelNotConfiguredException(ChannelType channel) {
        super(channel, "No channel configured for '" + channel + "'. Register one in NotificationService.builder().", null);
    }
}
