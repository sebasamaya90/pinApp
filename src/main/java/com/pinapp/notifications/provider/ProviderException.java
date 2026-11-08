package com.pinapp.notifications.provider;

import java.time.Duration;

/**
 * A vendor call failed. Checked on purpose: the provider contract forces implementors to translate
 * every vendor failure into this type and to say whether retrying can help.
 */
public class ProviderException extends Exception {

    /** Why the call failed; lets callers and decorators react without parsing messages. */
    public enum Reason {
        /** The vendor answered with an error or the network failed. */
        FAILURE,
        /** The vendor did not answer in time. The message may or may not have been delivered. */
        TIMEOUT,
        /** Rejected locally: the circuit breaker is open. */
        CIRCUIT_OPEN,
        /** Rejected locally: too many concurrent calls to this provider. */
        BULKHEAD_FULL,
        /** Rejected locally: provider quota exhausted. */
        RATE_LIMITED
    }

    private final boolean retryable;
    private final Integer statusCode;
    private final int attempts;
    private final Reason reason;
    private final Duration retryAfter;

    public ProviderException(String message, boolean retryable, Integer statusCode, Throwable cause) {
        this(message, retryable, statusCode, 1, Reason.FAILURE, null, cause);
    }

    private ProviderException(String message, boolean retryable, Integer statusCode, int attempts,
                              Reason reason, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.statusCode = statusCode;
        this.attempts = attempts;
        this.reason = reason;
        this.retryAfter = retryAfter;
    }

    public static ProviderException of(Reason reason, String message, boolean retryable, Integer statusCode,
                                       Duration retryAfter, Throwable cause) {
        return new ProviderException(message, retryable, statusCode, 1, reason, retryAfter, cause);
    }

    /** True for transient problems (network, timeouts, HTTP 429/5xx); false for permanent ones (HTTP 4xx). */
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

    public Reason reason() {
        return reason;
    }

    /** Minimum wait requested by the vendor (Retry-After) or by a local limiter; {@code null} if none. */
    public Duration retryAfter() {
        return retryAfter;
    }

    public ProviderException withAttempts(int attempts) {
        ProviderException copy = new ProviderException(getMessage(), retryable, statusCode, attempts,
                reason, retryAfter, getCause());
        copy.setStackTrace(getStackTrace());
        for (Throwable suppressed : getSuppressed()) {
            copy.addSuppressed(suppressed);
        }
        return copy;
    }
}
