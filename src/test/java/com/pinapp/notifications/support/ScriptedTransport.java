package com.pinapp.notifications.support;

import com.pinapp.notifications.http.HttpRequest;
import com.pinapp.notifications.http.HttpResponse;
import com.pinapp.notifications.http.HttpTransport;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** Test double: replays scripted responses/IOExceptions (the last one repeats) and records requests. */
public final class ScriptedTransport implements HttpTransport {

    private final Deque<Object> script = new ArrayDeque<>();
    private final List<HttpRequest> requests = new ArrayList<>();

    public static ScriptedTransport responding(int status, String body) {
        return new ScriptedTransport().thenRespond(status, body);
    }

    public static ScriptedTransport responding(int status, Map<String, String> headers, String body) {
        ScriptedTransport t = new ScriptedTransport();
        t.script.add(new HttpResponse(status, headers, body));
        return t;
    }

    public ScriptedTransport thenRespond(int status, String body) {
        script.add(new HttpResponse(status, Map.of(), body));
        return this;
    }

    public ScriptedTransport thenFail(IOException e) {
        script.add(e);
        return this;
    }

    public List<HttpRequest> requests() {
        return requests;
    }

    public HttpRequest lastRequest() {
        return requests.get(requests.size() - 1);
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws IOException {
        requests.add(request);
        Object next = script.size() > 1 ? script.poll() : script.peek();
        if (next instanceof IOException e) {
            throw e;
        }
        return (HttpResponse) next;
    }
}
