package com.pinapp.notifications.slack;

import com.pinapp.notifications.validation.NotificationValidator;
import com.pinapp.notifications.validation.ValidationError;
import com.pinapp.notifications.validation.ValidationErrors;

import java.util.List;

public final class SlackValidator implements NotificationValidator<SlackNotification> {

    @Override
    public List<ValidationError> validate(SlackNotification n) {
        return new ValidationErrors()
                .requireNotBlank("body", n.body())
                .require(n.target() == null || n.target().startsWith("#") || n.target().startsWith("@"),
                        "target", "must start with '#' (channel) or '@' (user)")
                .toList();
    }
}
