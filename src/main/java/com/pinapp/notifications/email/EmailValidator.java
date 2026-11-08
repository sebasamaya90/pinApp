package com.pinapp.notifications.email;

import com.pinapp.notifications.validation.NotificationValidator;
import com.pinapp.notifications.validation.ValidationError;
import com.pinapp.notifications.validation.ValidationErrors;

import java.util.List;
import java.util.regex.Pattern;

public final class EmailValidator implements NotificationValidator<EmailNotification> {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Override
    public List<ValidationError> validate(EmailNotification n) {
        return new ValidationErrors()
                .require(n.to() != null && EMAIL.matcher(n.to()).matches(), "to", "must be a valid email address")
                .requireNotBlank("subject", n.subject())
                .requireNotBlank("body", n.body())
                .toList();
    }
}
