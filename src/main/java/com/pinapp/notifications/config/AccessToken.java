package com.pinapp.notifications.config;

import java.time.Instant;
import java.util.Objects;

/** A short-lived credential (e.g. an OAuth2 access token) and the instant it stops being valid. */
public record AccessToken(Secret value, Instant expiresAt) {
    public AccessToken {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
