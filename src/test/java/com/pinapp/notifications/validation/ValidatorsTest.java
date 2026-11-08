package com.pinapp.notifications.validation;

import com.pinapp.notifications.email.EmailNotification;
import com.pinapp.notifications.email.EmailValidator;
import com.pinapp.notifications.push.PushNotification;
import com.pinapp.notifications.push.PushValidator;
import com.pinapp.notifications.slack.SlackNotification;
import com.pinapp.notifications.slack.SlackValidator;
import com.pinapp.notifications.sms.SmsNotification;
import com.pinapp.notifications.sms.SmsValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatorsTest {

    private final EmailValidator email = new EmailValidator();
    private final SmsValidator sms = new SmsValidator();
    private final PushValidator push = new PushValidator();
    private final SlackValidator slack = new SlackValidator();

    @Test
    void validEmailHasNoErrors() {
        assertTrue(email.validate(EmailNotification.of("ana@example.com", "Hi", "Body")).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "plain", "a@b", "a b@c.com", "@c.com"})
    void invalidEmailAddressesAreReported(String address) {
        var errors = email.validate(EmailNotification.of(address, "Hi", "Body"));
        assertEquals("to", errors.get(0).field());
    }

    @Test
    void reportsAllEmailProblemsAtOnce() {
        var errors = email.validate(EmailNotification.builder().build());
        assertEquals(3, errors.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"+5491155550000", "+14155552671"})
    void acceptsE164Numbers(String number) {
        assertTrue(sms.validate(new SmsNotification(number, "hi")).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5491155550000", "+0123456789", "+12", "abc", ""})
    void rejectsNonE164Numbers(String number) {
        assertEquals("to", sms.validate(new SmsNotification(number, "hi")).get(0).field());
    }

    @Test
    void rejectsTooLongSmsBody() {
        var errors = sms.validate(new SmsNotification("+14155552671", "x".repeat(SmsValidator.MAX_BODY_LENGTH + 1)));
        assertEquals("body", errors.get(0).field());
    }

    @Test
    void pushRequiresTokenAndBodyButTitleIsOptional() {
        assertTrue(push.validate(PushNotification.builder().deviceToken("t").body("b").build()).isEmpty());
        assertEquals(2, push.validate(PushNotification.builder().build()).size());
    }

    @Test
    void slackRequiresANonBlankBody() {
        assertTrue(slack.validate(new SlackNotification("hi")).isEmpty());
        assertEquals("body", slack.validate(new SlackNotification(" ")).get(0).field());
    }
}
