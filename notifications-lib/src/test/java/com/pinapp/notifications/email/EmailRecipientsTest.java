package com.pinapp.notifications.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.pinapp.notifications.config.Secret;
import com.pinapp.notifications.http.Json;
import com.pinapp.notifications.support.ScriptedTransport;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailRecipientsTest {

    private static final EmailNotification EMAIL = EmailNotification.builder()
            .to("ana@example.com").to("luis@example.com")
            .cc("boss@example.com")
            .bcc("audit@example.com").bcc("legal@example.com")
            .subject("Report").body("Body").build();

    @Test
    void validatorRequiresAtLeastOneToRecipient() {
        var errors = new EmailValidator().validate(
                EmailNotification.builder().cc("a@b.co").subject("s").body("b").build());

        assertEquals("to", errors.get(0).field());
        assertTrue(errors.get(0).message().contains("at least one"));
    }

    @Test
    void validatorChecksEveryAddressInEveryList() {
        var validator = new EmailValidator();

        assertTrue(validator.validate(EMAIL).isEmpty());
        assertEquals("cc", validator.validate(EmailNotification.builder().to("a@b.co").cc("nope")
                .subject("s").body("b").build()).get(0).field());
        assertEquals("bcc", validator.validate(EmailNotification.builder().to("a@b.co").bcc("a@b.co").bcc("nope")
                .subject("s").body("b").build()).get(0).field());
    }

    @Test
    void recipientIsTheFirstToAddressAndListsAreImmutable() {
        assertEquals("ana@example.com", EMAIL.recipient());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> EMAIL.to().add("x@y.zz"));
    }

    @Test
    void sendGridSendsAllRecipientListsInOnePersonalization() throws Exception {
        var transport = ScriptedTransport.responding(202, "");

        new SendGridEmailProvider(new SendGridEmailProvider.Config(Secret.of("k"), "no-reply@x.com", null), transport).send(EMAIL);

        JsonNode personalization = Json.read(transport.lastRequest().body()).at("/personalizations/0");
        assertEquals(2, personalization.path("to").size());
        assertEquals("boss@example.com", personalization.at("/cc/0/email").asText());
        assertEquals(2, personalization.path("bcc").size());
    }

    @Test
    void sendGridOmitsEmptyCcAndBcc() throws Exception {
        var transport = ScriptedTransport.responding(202, "");

        new SendGridEmailProvider(new SendGridEmailProvider.Config(Secret.of("k"), "no-reply@x.com", null), transport)
                .send(EmailNotification.of("ana@example.com", "s", "b"));

        JsonNode personalization = Json.read(transport.lastRequest().body()).at("/personalizations/0");
        assertTrue(personalization.path("cc").isMissingNode());
        assertTrue(personalization.path("bcc").isMissingNode());
    }

    @Test
    void mailgunJoinsRecipientsWithCommas() throws Exception {
        var transport = ScriptedTransport.responding(200, "{\"id\":\"<1>\"}");

        new MailgunEmailProvider(new MailgunEmailProvider.Config(Secret.of("k"), "mg.x.com", "a@mg.x.com"), transport).send(EMAIL);

        String form = URLDecoder.decode(transport.lastRequest().body(), StandardCharsets.UTF_8);
        assertTrue(form.contains("to=ana@example.com,luis@example.com"));
        assertTrue(form.contains("cc=boss@example.com"));
        assertTrue(form.contains("bcc=audit@example.com,legal@example.com"));
    }

    @Test
    void mailgunOmitsEmptyCcAndBcc() throws Exception {
        var transport = ScriptedTransport.responding(200, "{\"id\":\"<1>\"}");

        new MailgunEmailProvider(new MailgunEmailProvider.Config(Secret.of("k"), "mg.x.com", "a@mg.x.com"), transport)
                .send(EmailNotification.of("ana@example.com", "s", "b"));

        String form = transport.lastRequest().body();
        assertFalse(form.contains("cc="));
        assertFalse(form.contains("bcc="));
    }
}
