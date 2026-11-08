package com.pinapp.notifications.push;

import com.pinapp.notifications.validation.NotificationValidator;
import com.pinapp.notifications.validation.ValidationError;
import com.pinapp.notifications.validation.ValidationErrors;

import java.util.List;

public final class PushValidator implements NotificationValidator<PushNotification> {

    @Override
    public List<ValidationError> validate(PushNotification n) {
        return new ValidationErrors()
                .requireNotBlank("deviceToken", n.deviceToken())
                .requireNotBlank("body", n.body())
                .toList();
    }
}
