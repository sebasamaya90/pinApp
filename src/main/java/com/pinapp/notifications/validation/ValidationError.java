package com.pinapp.notifications.validation;

public record ValidationError(String field, String message) {
    @Override
    public String toString() {
        return field + ": " + message;
    }
}
