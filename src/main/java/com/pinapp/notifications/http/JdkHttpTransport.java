package com.pinapp.notifications.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Real transport on top of {@code java.net.http} (no extra dependency), hardened by default:
 * <ul>
 *   <li><b>HTTPS only</b>: plain HTTP is refused except for loopback hosts (local test servers);</li>
 *   <li><b>explicit timeouts</b>: surface as {@link java.net.http.HttpTimeoutException}, which providers
 *       translate into a retryable {@code ProviderException} with reason TIMEOUT;</li>
 *   <li><b>bounded responses</b>: the body is cut at {@code maxResponseBytes}, so a faulty or hostile
 *       endpoint cannot exhaust memory;</li>
 *   <li><b>no redirects</b>: credentials in headers cannot be forwarded to another host.</li>
 * </ul>
 *
 * <p>Caveat: after a request timeout the vendor may still have processed the message, so a retry can
 * (rarely) produce a duplicate. Connection-phase failures are always safe to retry.
 */
public final class JdkHttpTransport implements HttpTransport {

    /**
     * @param connectTimeout   max time to establish the TCP/TLS connection
     * @param requestTimeout   max time to receive the response after the request is sent
     * @param maxResponseBytes response bytes kept (the rest is discarded)
     */
    public record Config(Duration connectTimeout, Duration requestTimeout, int maxResponseBytes) {
        public Config {
            Objects.requireNonNull(connectTimeout, "connectTimeout");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            if (connectTimeout.isZero() || connectTimeout.isNegative()
                    || requestTimeout.isZero() || requestTimeout.isNegative()) {
                throw new IllegalArgumentException("timeouts must be positive");
            }
            if (maxResponseBytes < 1) {
                throw new IllegalArgumentException("maxResponseBytes must be positive");
            }
        }

        public Config(Duration connectTimeout, Duration requestTimeout) {
            this(connectTimeout, requestTimeout, 64 * 1024);
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
        URI uri = URI.create(request.url());
        requireSecure(uri);

        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(config.requestTimeout());
        request.headers().forEach(builder::header);
        builder.method(request.method(), java.net.http.HttpRequest.BodyPublishers.ofString(request.body()));

        try {
            java.net.http.HttpResponse<InputStream> response =
                    client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            Map<String, String> headers = new HashMap<>();
            response.headers().map().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    headers.put(name, values.get(0));
                }
            });
            try (InputStream in = response.body()) {
                String body = new String(in.readNBytes(config.maxResponseBytes()), StandardCharsets.UTF_8);
                return new HttpResponse(response.statusCode(), headers, body);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("HTTP call interrupted");
        }
    }

    /** The message names only the host: the path/query may carry a secret (e.g. Slack webhook tokens). */
    private static void requireSecure(URI uri) {
        String scheme = uri.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            return;
        }
        if ("http".equalsIgnoreCase(scheme) && isLoopback(uri.getHost())) {
            return;
        }
        throw new IllegalArgumentException("Refusing non-HTTPS request to host '" + uri.getHost()
                + "' (plain HTTP is only allowed for loopback addresses)");
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
    }
}
