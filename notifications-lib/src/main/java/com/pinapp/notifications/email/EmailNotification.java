package com.pinapp.notifications.email;

import com.pinapp.notifications.ChannelType;
import com.pinapp.notifications.Notification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Email: one or more {@code to} recipients plus optional {@code cc}/{@code bcc}, a subject, and a
 * text or HTML body. Fields are not content-checked here on purpose; the {@link EmailValidator}
 * reports every problem at once instead of failing on the first one.
 */
public record EmailNotification(List<String> to, List<String> cc, List<String> bcc,
                                String subject, String body, boolean html) implements Notification {

    public EmailNotification {
        to = immutableCopy(to);
        cc = immutableCopy(cc);
        bcc = immutableCopy(bcc);
    }

    public static EmailNotification of(String to, String subject, String body) {
        return builder().to(to).subject(subject).body(body).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ChannelType channel() {
        return ChannelType.EMAIL;
    }

    /** The first {@code to} address ({@code null} when there is none). */
    @Override
    public String recipient() {
        return to.isEmpty() ? null : to.get(0);
    }

    // Collections.unmodifiableList (not List.copyOf) so a null element reaches the validator
    // instead of blowing up the constructor.
    private static List<String> immutableCopy(List<String> list) {
        return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
    }

    public static final class Builder {
        private final List<String> to = new ArrayList<>();
        private final List<String> cc = new ArrayList<>();
        private final List<String> bcc = new ArrayList<>();
        private String subject;
        private String body;
        private boolean html;

        private Builder() {
        }

        /** Adds a primary recipient (may be called several times). */
        public Builder to(String address) {
            this.to.add(address);
            return this;
        }

        public Builder cc(String address) {
            this.cc.add(address);
            return this;
        }

        public Builder bcc(String address) {
            this.bcc.add(address);
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
            return new EmailNotification(to, cc, bcc, subject, body, html);
        }
    }
}
