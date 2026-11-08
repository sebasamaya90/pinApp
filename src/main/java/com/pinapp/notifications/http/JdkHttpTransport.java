package com.pinapp.notifications.http;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Real transport on top of {@code java.net.http} (no extra dependency) with explicit timeouts.
 * Timeouts surface as {@link java.net.http.HttpTimeoutException}, which providers translate into a
 * retryable {@code ProviderException} with reason TIMEOUT. Redirects are never followed so that
 * credentials in headers cannot be forwarded to another host.
 *
 * <p>Caveat: after a request timeout the vendor may still have processed the message, so a retry can
 * (rarely) produce a duplicate. Connection-phase failures are always safe to retry.
 */
public final class JdkHttpTransport implements HttpTransport {

    /**
     * @param connectTimeout max time to establish the TCP/TLS connection
     * @param requestTimeout max time to receive the response after the request is sent
     */
    public record Config(Duration connectTimeout, Duration requestTimeout) {
        public Config {
            Objects.requireNonNull(connectTimeout, "connectTimeout");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            if (connectTimeout.isZero() || connectTimeout.isNegative()
                    || requestTimeout.isZero() || requestTimeout.isNegative()) {
                throw new IllegalArgumentException("timeouts must be positive");
            }
        }

        public static Config defaults() {
            return new Config(Duration.ofSeconds(2), Duration.ofSeconds(5));
        }
    }

    private final HttpClient client;
    private final Config config;

    public JdkHttpTransport() {
        this(Config.defaults());
    }

    public JdkHttpTransport(Config config) {
        this.config = Objects.requireNonNull(config);
        this.client = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws IOException {
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(URI.create(request.url()))
                .timeout(config.requestTimeout());
        request.headers().forEach(builder::header);
        builder.method(request.method(), java.net.http.HttpRequest.BodyPublishers.ofString(request.body()));

        try {
            java.net.http.HttpResponse<String> response =
                    client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            Map<String, String> headers = new HashMap<>();
            response.headers().map().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    headers.put(name, values.get(0));
                }
            });
            return new HttpResponse(response.statusCode(), headers, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("HTTP call interrupted");
        }
    }
}
