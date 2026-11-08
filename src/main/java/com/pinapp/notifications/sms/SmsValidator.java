package com.pinapp.notifications.sms;

import com.pinapp.notifications.validation.NotificationValidator;
import com.pinapp.notifications.validation.ValidationError;
import com.pinapp.notifications.validation.ValidationErrors;

import java.util.List;
import java.util.regex.Pattern;

public final class SmsValidator implements NotificationValidator<SmsNotification> {

    /** Twilio rejects bodies over 1600 characters. */
    public static final int MAX_BODY_LENGTH = 1600;

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");

    @Override
    public List<ValidationError> validate(SmsNotification n) {
        return new ValidationErrors()
                .require(n.to() != null && E164.matcher(n.to()).matches(), "to",
                        "must be an E.164 phone number (e.g. +5491155550000)")
                .requireNotBlank("body", n.body())
                .require(n.body() == null || n.body().length() <= MAX_BODY_LENGTH, "body",
                        "must not exceed " + MAX_BODY_LENGTH + " characters")
                .toList();
    }
}
