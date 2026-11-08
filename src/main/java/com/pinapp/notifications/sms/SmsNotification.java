package com.pinapp.notifications.sms;

import com.pinapp.notifications.ChannelType;
import com.pinapp.notifications.Notification;

/** SMS: no subject, plain text only; {@code to} must be an E.164 number such as +5491155550000. */
public record SmsNotification(String to, String body) implements Notification {

    @Override
    public ChannelType channel() {
        return ChannelType.SMS;
    }

    @Override
    public String recipient() {
        return to;
    }
}
