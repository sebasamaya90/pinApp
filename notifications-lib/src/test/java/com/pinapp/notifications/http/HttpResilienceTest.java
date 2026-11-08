package com.pinapp.notifications.http;

import com.pinapp.notifications.config.Secret;
import com.pinapp.notifications.email.EmailNotification;
import com.pinapp.notifications.email.SendGridEmailProvider;
import com.pinapp.notifications.provider.ProviderException;
import com.pinapp.notifications.provider.ProviderException.Reason;
import com.pinapp.notifications.support.ScriptedTransport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpResilienceTest {

    private static final EmailNotification EMAIL = EmailNotification.of("ana@example.com", "Hi", "Body");
    private static final SendGridEmailProvider.Config CONFIG =
            new SendGridEmailProvider.Config(Secret.of("SG.x"), "no-reply@pinapp.dev", null);

    private HttpServer server;
    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ok", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Message-Id", "42");
            exchange.sendResponseHeaders(201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(1500);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException | IOException ignored) {
                // client gave up; nothing to do
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static JdkHttpTransport transport() {
        return new JdkHttpTransport(new JdkHttpTransport.Config(Duration.ofMillis(500), Duration.ofMillis(300)));
    }

    // ------------------------------------------------------------ JdkHttpTransport (real sockets, loopback)

    @Test
    void jdkTransportSendsRequestAndReadsStatusHeadersAndBody() throws Exception {
        HttpResponse response = transport().execute(
                new HttpRequest("POST", url("/ok"), Map.of("Content-Type", "application/json"), "{\"a\":1}"));

        assertEquals(201, response.status());
        assertEquals("42", response.header("x-message-id").orElseThrow());
        assertEquals("{\"ok\":true}", response.body());
        assertEquals("{\"a\":1}", receivedBody.get());
    }

    @Test
    void jdkTransportEnforcesTheRequestTimeout() {
        long start = System.nanoTime();

        assertThrows(HttpTimeoutException.class,
                () -> transport().execute(new HttpRequest("POST", url("/slow"), Map.of(), "x")));

        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1400, "must not wait for the slow server");
    }

    @Test
    void jdkTransportFailsFastWhenNobodyListens() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }

        assertThrows(IOException.class, () -> transport().execute(
                new HttpRequest("POST", "http://127.0.0.1:" + closedPort + "/x", Map.of(), "x")));
    }

    @Test
    void jdkTransportRejectsNonPositiveTimeouts() {
        assertThrows(IllegalArgumentException.class,
                () -> new JdkHttpTransport.Config(Duration.ZERO, Duration.ofSeconds(1)));
    }

    // ------------------------------------------------------------ how providers classify failures

    @Test
    void timeoutsBecomeRetryableProviderExceptionsWithReasonTimeout() {
        var transport = new ScriptedTransport().thenFail(new HttpTimeoutException("request timed out"));

        ProviderException e = assertThrows(ProviderException.class,
                () -> new SendGridEmailProvider(CONFIG, transport).send(EMAIL));

        assertEquals(Reason.TIMEOUT, e.reason());
        assertTrue(e.retryable());
    }

    @Test
    void retryAfterHeaderIsExposedOnRateLimitResponses() {
        var transport = ScriptedTransport.responding(429, Map.of("Retry-After", "7"), "");

        ProviderException e = assertThrows(ProviderException.class,
                () -> new SendGridEmailProvider(CONFIG, transport).send(EMAIL));

        assertEquals(Duration.ofSeconds(7), e.retryAfter());
        assertTrue(e.retryable());
    }

    @Test
    void unparseableRetryAfterIsIgnored() {
        var transport = ScriptedTransport.responding(503, Map.of("Retry-After", "soon"), "");

        ProviderException e = assertThrows(ProviderException.class,
                () -> new SendGridEmailProvider(CONFIG, transport).send(EMAIL));

        assertNull(e.retryAfter());
    }
}
