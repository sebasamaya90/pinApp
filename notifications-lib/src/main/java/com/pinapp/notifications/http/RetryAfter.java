package com.pinapp.notifications.http;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Parses the {@code Retry-After} header: delay in seconds, or an HTTP-date (RFC 9110). */
public final class RetryAfter {

    /** Upper bound so a faulty or hostile vendor cannot make us wait for hours. */
    public static final Duration MAX = Duration.ofMinutes(5);

    private RetryAfter() {
    }

    /** @return the wait (capped at {@link #MAX}, never negative) or {@code null} if the value is unparseable */
    public static Duration parse(String value, Instant now) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return seconds < 0 ? null : cap(Duration.ofSeconds(seconds));
        } catch (NumberFormatException notSeconds) {
            try {
                Instant when = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration wait = Duration.between(now, when);
                return wait.isNegative() ? Duration.ZERO : cap(wait);
            } catch (DateTimeParseException notDate) {
                return null;
            }
        }
    }

    private static Duration cap(Duration wait) {
        return wait.compareTo(MAX) > 0 ? MAX : wait;
    }
}
