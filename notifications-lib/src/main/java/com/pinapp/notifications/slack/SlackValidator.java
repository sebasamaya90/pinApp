package com.pinapp.notifications.slack;

import com.pinapp.notifications.validation.NotificationValidator;
import com.pinapp.notifications.validation.ValidationError;
import com.pinapp.notifications.validation.ValidationErrors;

import java.util.List;

public final class SlackValidator implements NotificationValidator<SlackNotification> {

    /** Slack truncates messages above 40,000 characters. */
    public static final int MAX_BODY_LENGTH = 40_000;

    @Override
    public List<ValidationError> validate(SlackNotification n) {
        return new ValidationErrors()
                .requireNotBlank("body", n.body())
                .requireMaxLength("body", n.body(), MAX_BODY_LENGTH)
                .require(n.target() == null || n.target().startsWith("#") || n.target().startsWith("@"),
                        "target", "must start with '#' (channel) or '@' (user)")
                .requireNoLineBreaks("target", n.target())
                .toList();
    }
}
