package com.pinapp.notifications.channel;

import com.pinapp.notifications.ChannelType;
import com.pinapp.notifications.email.EmailNotification;
import com.pinapp.notifications.email.EmailValidator;
import com.pinapp.notifications.push.PushNotification;
import com.pinapp.notifications.push.PushValidator;
import com.pinapp.notifications.sms.SmsNotification;
import com.pinapp.notifications.sms.SmsValidator;

/** Entry points to configure the built-in channels. Custom channels do not need to touch this class. */
public final class Channels {

    private Channels() {
    }

    public static StandardChannel.Builder<EmailNotification> email() {
        return StandardChannel.builder(ChannelType.EMAIL, EmailNotification.class, new EmailValidator());
    }

    public static StandardChannel.Builder<SmsNotification> sms() {
        return StandardChannel.builder(ChannelType.SMS, SmsNotification.class, new SmsValidator());
    }

    public static StandardChannel.Builder<PushNotification> push() {
        return StandardChannel.builder(ChannelType.PUSH, PushNotification.class, new PushValidator());
    }
}
