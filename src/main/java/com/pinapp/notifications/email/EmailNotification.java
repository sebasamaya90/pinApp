package com.pinapp.notifications.email;

import com.pinapp.notifications.ChannelType;
import com.pinapp.notifications.Notification;

/**
 * Email: has a subject and may be HTML. Fields are not null-checked here on purpose; the
 * {@link EmailValidator} reports every problem at once instead of failing on the first one.
 */
public record EmailNotification(String to, String subject, String body, boolean html) implements Notification {

    public static EmailNotification of(String to, String subject, String body) {
        return new EmailNotification(to, subject, body, false);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ChannelType channel() {
        return ChannelType.EMAIL;
    }

    @Override
    public String recipient() {
        return to;
    }

    public static final class Builder {
        private String to;
        private String subject;
        private String body;
        private boolean html;

        private Builder() {
        }

        public Builder to(String to) {
            this.to = to;
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder body(String body) {
            this.body = body;
            return this;
        }

        public Builder html(boolean html) {
            this.html = html;
            return this;
        }

        public EmailNotification build() {
            return new EmailNotification(to, subject, body, html);
        }
    }
}
