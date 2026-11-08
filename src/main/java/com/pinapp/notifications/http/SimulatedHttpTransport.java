package com.pinapp.notifications.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Function;

/** Transport that never opens a socket: it answers like the vendor's API would. */
public final class SimulatedHttpTransport implements HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(SimulatedHttpTransport.class);

    private final Function<HttpRequest, HttpResponse> responder;

    public SimulatedHttpTransport(Function<HttpRequest, HttpResponse> responder) {
        this.responder = Objects.requireNonNull(responder);
    }

    @Override
    public HttpResponse execute(HttpRequest request) {
        HttpResponse response = responder.apply(request);
        log.info("[simulated] {} -> HTTP {}", request, response.status());
        return response;
    }
}
