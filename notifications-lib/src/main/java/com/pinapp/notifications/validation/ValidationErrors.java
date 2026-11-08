package com.pinapp.notifications.validation;

import java.util.ArrayList;
import java.util.List;

/** Small fluent collector so validators read as a list of rules. */
public final class ValidationErrors {

    private final List<ValidationError> errors = new ArrayList<>();

    public ValidationErrors require(boolean condition, String field, String message) {
        if (!condition) {
            errors.add(new ValidationError(field, message));
        }
        return this;
    }

    public ValidationErrors requireNotBlank(String field, String value) {
        return require(value != null && !value.isBlank(), field, "must not be blank");
    }

    /** Blocks header injection (e.g. a subject like "Hi\r\nBcc: attacker@evil.com"). */
    public ValidationErrors requireNoLineBreaks(String field, String value) {
        return require(value == null || (value.indexOf('\r') < 0 && value.indexOf('\n') < 0),
                field, "must not contain line breaks");
    }

    public ValidationErrors requireMaxLength(String field, String value, int max) {
        return require(value == null || value.length() <= max, field, "must not exceed " + max + " characters");
    }

    public List<ValidationError> toList() {
        return List.copyOf(errors);
    }
}
