package com.pinapp.notifications.push;

import com.pinapp.notifications.ChannelType;
import com.pinapp.notifications.Notification;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mobile push: addressed to a device token, with an optional title and key/value data payload. */
public record PushNotification(String deviceToken, String title, String body, Map<String, String> data)
        implements Notification {

    public PushNotification {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ChannelType channel() {
        return ChannelType.PUSH;
    }

    @Override
    public String recipient() {
        return deviceToken;
    }

    public static final class Builder {
        private String deviceToken;
        private String title;
        private String body;
        private final Map<String, String> data = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder deviceToken(String deviceToken) {
            this.deviceToken = deviceToken;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder body(String body) {
            this.body = body;
            return this;
        }

        public Builder data(String key, String value) {
            this.data.put(key, value);
            return this;
        }

        public PushNotification build() {
            return new PushNotification(deviceToken, title, body, data);
        }
    }
}
