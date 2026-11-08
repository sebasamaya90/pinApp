package com.pinapp.notifications.exception;

import com.pinapp.notifications.ChannelType;
import com.pinapp.notifications.validation.ValidationError;

import java.util.List;
import java.util.stream.Collectors;

/** The notification is invalid; nothing was sent to any provider. */
public class ValidationException extends NotificationException {

    private final transient List<ValidationError> errors;

    public ValidationException(ChannelType channel, List<ValidationError> errors) {
        super(channel, "Invalid " + channel + " notification: " + errors.stream()
                .map(ValidationError::toString).collect(Collectors.joining("; ")), null);
        this.errors = List.copyOf(errors);
    }

    public List<ValidationError> errors() {
        return errors;
    }
}
