package com.pinapp.notifications.config;

/**
 * Wraps a credential so it never leaks through {@code toString()}, logs or exception messages.
 * The raw value is only available through the explicit {@link #reveal()} call, used by providers
 * at the very moment they build a request.
 */
public final class Secret {

    private final String value;

    private Secret(String value) {
        this.value = value;
    }

    public static Secret of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Secret must not be blank");
        }
        return new Secret(value);
    }

    /** Preferred way to obtain credentials: from the environment / a secrets manager, never hard-coded. */
    public static Secret fromEnv(String variable) {
        String value = System.getenv(variable);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable '" + variable + "' is not set");
        }
        return new Secret(value);
    }

    public String reveal() {
        return value;
    }

    @Override
    public String toString() {
        return "Secret[****]";
    }
}
