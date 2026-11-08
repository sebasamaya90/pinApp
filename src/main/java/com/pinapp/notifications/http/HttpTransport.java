package com.pinapp.notifications.http;

import java.io.IOException;

/**
 * Minimal HTTP port. Real integrations implement it with java.net.http / OkHttp / etc.; this
 * library ships {@link SimulatedHttpTransport} so nothing here touches the network.
 */
@FunctionalInterface
public interface HttpTransport {
    HttpResponse execute(HttpRequest request) throws IOException;
}
