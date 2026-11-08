package com.pinapp.notifications.template;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal {@code {{variable}}} template. Fails fast when a variable is missing so a half-rendered
 * message is never sent to a customer.
 */
public record MessageTemplate(String pattern) {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");

    public MessageTemplate {
        if (pattern == null) {
            throw new IllegalArgumentException("pattern must not be null");
        }
    }

    public static MessageTemplate of(String pattern) {
        return new MessageTemplate(pattern);
    }

    public String render(Map<String, ?> variables) {
        List<String> missing = new ArrayList<>();
        Matcher matcher = VARIABLE.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object value = variables.get(matcher.group(1));
            if (value == null) {
                missing.add(matcher.group(1));
                continue;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(value)));
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing template variables: " + missing);
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
