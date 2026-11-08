package com.pinapp.notifications.http;

import java.net.URI;
import java.util.Map;

/** Outgoing vendor request. {@code toString()} is log-safe: no URL path/query, header values or body. */
public record HttpRequest(String method, String url, Map<String, String> headers, String body) {

    public HttpRequest {
        headers = Map.copyOf(headers);
    }

    public String host() {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "unknown" : host;
        } catch (IllegalArgumentException e) {
            return "unknown";
        }
    }

    @Override
    public String toString() {
        return method + " " + host() + " headers=" + headers.keySet() + " bodyLength=" + body.length();
    }
}
