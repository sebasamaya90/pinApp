package com.pinapp.notifications.provider;

/** What the vendor answered when it accepted a notification. */
public record ProviderResponse(String provider, String messageId, int attempts) {

    public static ProviderResponse of(String provider, String messageId) {
        return new ProviderResponse(provider, messageId, 1);
    }

    public ProviderResponse withAttempts(int attempts) {
        return new ProviderResponse(provider, messageId, attempts);
    }
}
