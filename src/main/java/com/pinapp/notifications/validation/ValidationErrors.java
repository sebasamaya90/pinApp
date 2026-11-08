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

    public List<ValidationError> toList() {
        return List.copyOf(errors);
    }
}
