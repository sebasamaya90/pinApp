package com.pinapp.notifications.validation;

import com.pinapp.notifications.Notification;

import java.util.List;

/** Validates one kind of notification. One implementation per channel (Single Responsibility). */
@FunctionalInterface
public interface NotificationValidator<N extends Notification> {

    /** @return every problem found; empty list when the notification is valid. */
    List<ValidationError> validate(N notification);
}
