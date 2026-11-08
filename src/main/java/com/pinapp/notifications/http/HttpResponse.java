package com.pinapp.notifications.http;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public record HttpResponse(int status, Map<String, String> headers, String body) {

    public HttpResponse {
        TreeMap<String, String> normalized = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            normalized.putAll(headers);
        }
        headers = Collections.unmodifiableMap(normalized);
        body = body == null ? "" : body;
    }

    public boolean isSuccessful() {
        return status >= 200 && status < 300;
    }

    public Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name));
    }
}
