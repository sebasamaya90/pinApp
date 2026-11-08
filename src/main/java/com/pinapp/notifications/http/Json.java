package com.pinapp.notifications.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

/** Thin wrapper over Jackson so providers do not deal with checked exceptions. */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize payload", e);
        }
    }

    /** Lenient parse: non-JSON or empty bodies yield a missing node instead of an exception. */
    public static JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return MissingNode.getInstance();
        }
    }
}
