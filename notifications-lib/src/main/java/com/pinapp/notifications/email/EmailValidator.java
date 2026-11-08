package com.pinapp.notifications.email;

import com.pinapp.notifications.validation.NotificationValidator;
import com.pinapp.notifications.validation.ValidationError;
import com.pinapp.notifications.validation.ValidationErrors;

import java.util.List;
import java.util.regex.Pattern;

public final class EmailValidator implements NotificationValidator<EmailNotification> {

    /** Total of to + cc + bcc addresses (vendors accept up to ~1000; keep abuse and blast radius small). */
    public static final int MAX_RECIPIENTS = 50;
    /** RFC 5322 hard limit for a header line. */
    public static final int MAX_SUBJECT_LENGTH = 998;
    public static final int MAX_BODY_LENGTH = 500_000;

    // [^@\s] also rejects CR/LF, so addresses cannot be used for header injection.
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Override
    public List<ValidationError> validate(EmailNotification n) {
        ValidationErrors errors = new ValidationErrors()
                .require(!n.to().isEmpty(), "to", "must contain at least one recipient")
                .require(allValid(n.to()), "to", "must be a valid email address")
                .require(allValid(n.cc()), "cc", "must be a valid email address")
                .require(allValid(n.bcc()), "bcc", "must be a valid email address")
                .require(n.to().size() + n.cc().size() + n.bcc().size() <= MAX_RECIPIENTS,
                        "to", "too many recipients (max " + MAX_RECIPIENTS + " across to, cc and bcc)");
        return errors
                .requireNotBlank("subject", n.subject())
                .requireNoLineBreaks("subject", n.subject())
                .requireMaxLength("subject", n.subject(), MAX_SUBJECT_LENGTH)
                .requireNotBlank("body", n.body())
                .requireMaxLength("body", n.body(), MAX_BODY_LENGTH)
                .toList();
    }

    private static boolean allValid(List<String> addresses) {
        return addresses.stream().allMatch(a -> a != null && EMAIL.matcher(a).matches());
    }
}
