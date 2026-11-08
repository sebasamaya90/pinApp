package com.pinapp.notifications.security;

/**
 * Helpers to log or display personal data safely. The library itself never logs recipients or message
 * bodies; use these if your own code needs to reference a recipient in logs.
 */
public final class Masking {

    private Masking() {
    }

    /** {@code ana@example.com} becomes {@code a***@example.com}. */
    public static String email(String email) {
        if (email == null) {
            return "***";
        }
        int at = email.indexOf('@');
        if (at < 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    /** {@code +5491155550000} becomes {@code +54*********00} (first 3 and last 2 characters kept). */
    public static String phone(String phone) {
        if (phone == null || phone.length() <= 6) {
            return "***";
        }
        return phone.substring(0, 3) + "*".repeat(phone.length() - 5) + phone.substring(phone.length() - 2);
    }

    /** Device tokens / API keys: only the first 4 characters survive. */
    public static String token(String token) {
        if (token == null || token.length() <= 8) {
            return "****";
        }
        return token.substring(0, 4) + "****";
    }
}
