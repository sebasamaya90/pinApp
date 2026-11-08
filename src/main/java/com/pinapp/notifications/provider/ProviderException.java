package com.pinapp.notifications.provider;

/**
 * A vendor call failed. Checked on purpose: the provider contract forces implementors to translate
 * every vendor failure into this type and to say whether retrying can help.
 */
public class ProviderException extends Exception {

    private final boolean retryable;
    private final Integer statusCode;
    private final int attempts;

    public ProviderException(String message, boolean retryable, Integer statusCode, Throwable cause) {
        this(message, retryable, statusCode, 1, cause);
    }

    private ProviderException(String message, boolean retryable, Integer statusCode, int attempts, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.statusCode = statusCode;
        this.attempts = attempts;
    }

    /** True for transient problems (network, HTTP 429/5xx); false for permanent ones (HTTP 4xx). */
    public boolean retryable() {
        return retryable;
    }

    /** HTTP status when the failure came from a response; {@code null} otherwise. */
    public Integer statusCode() {
        return statusCode;
    }

    public int attempts() {
        return attempts;
    }

    public ProviderException withAttempts(int attempts) {
        ProviderException copy = new ProviderException(getMessage(), retryable, statusCode, attempts, getCause());
        copy.setStackTrace(getStackTrace());
        for (Throwable suppressed : getSuppressed()) {
            copy.addSuppressed(suppressed);
        }
        return copy;
    }
}
