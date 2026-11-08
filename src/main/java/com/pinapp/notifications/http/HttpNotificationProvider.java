package com.pinapp.notifications.http;

import com.pinapp.notifications.Notification;
import com.pinapp.notifications.provider.NotificationProvider;
import com.pinapp.notifications.provider.ProviderException;
import com.pinapp.notifications.provider.ProviderResponse;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Objects;

/**
 * Template Method for HTTP vendors: the flow (build request, call, classify errors, extract id) is
 * fixed here; subclasses only supply what is vendor specific.
 */
public abstract class HttpNotificationProvider<N extends Notification> implements NotificationProvider<N> {

    private final String name;
    private final HttpTransport transport;
    private final InstantSource clock;

    protected HttpNotificationProvider(String name, HttpTransport transport) {
        this(name, transport, InstantSource.system());
    }

    protected HttpNotificationProvider(String name, HttpTransport transport, InstantSource clock) {
        this.name = Objects.requireNonNull(name);
        this.transport = Objects.requireNonNull(transport);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final ProviderResponse send(N notification) throws ProviderException {
        HttpRequest request = buildRequest(notification);
        HttpResponse response;
        try {
            response = transport.execute(request);
        } catch (HttpTimeoutException e) {
            throw ProviderException.of(ProviderException.Reason.TIMEOUT,
                    name + ": timed out (" + e.getMessage() + ")", true, null, null, e);
        } catch (IOException e) {
            throw new ProviderException(name + ": network failure (" + e.getMessage() + ")", true, null, e);
        }
        if (!response.isSuccessful()) {
            throw ProviderException.of(ProviderException.Reason.FAILURE,
                    name + " rejected the request (HTTP " + response.status() + "): " + describeError(response),
                    isRetryable(response), response.status(), retryAfter(response), null);
        }
        verifyResponse(response);
        return ProviderResponse.of(name, extractMessageId(response));
    }

    /** Wait requested by the vendor for an error response. Default: the {@code Retry-After} header, if any. */
    protected Duration retryAfter(HttpResponse response) {
        return response.header("Retry-After").map(value -> RetryAfter.parse(value, clock.instant())).orElse(null);
    }

    /** Translates the notification into the vendor's request format. */
    protected abstract HttpRequest buildRequest(N notification) throws ProviderException;

    /** Reads the vendor's message identifier from a successful response. */
    protected abstract String extractMessageId(HttpResponse response);

    /** Whether an error response is transient. Default: HTTP 429 and 5xx. */
    protected boolean isRetryable(HttpResponse response) {
        return response.status() == 429 || response.status() >= 500;
    }

    /** Human readable reason from an error response. Default: the (truncated) body. */
    protected String describeError(HttpResponse response) {
        String body = response.body();
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    /** Hook for vendors that report failures inside a 2xx response (e.g. Vonage). */
    protected void verifyResponse(HttpResponse response) throws ProviderException {
        // successful by default
    }
}
