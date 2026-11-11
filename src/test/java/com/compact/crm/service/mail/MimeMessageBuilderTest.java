package com.compact.crm.service.mail;

import com.compact.crm.service.EmailAttachment;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MimeMessageBuilderTest {

    private static String headers(String mime) {
        return mime.substring(0, mime.indexOf("\r\n\r\n"));
    }

    @Test
    void plainText_fromTheConnectedMailbox_toOneRecipient() {

        String mime = new String(MimeMessageBuilder.build(new OutgoingEmail(
                "asha@company.test", "Asha Rao", "lead@customer.test", "Hello", "Line 1\nLine 2", List.of(),
                "batch-1:LEAD:42")), StandardCharsets.US_ASCII);

        String h = headers(mime);

        assertThat(h).contains("From: \"Asha Rao\" <asha@company.test>\r\n");
        assertThat(h).contains("To: <lead@customer.test>\r\n");
        assertThat(h).contains("Subject: Hello\r\n");
        assertThat(h).contains("Content-Type: text/plain; charset=UTF-8\r\n");
        assertThat(h).endsWith("Content-Transfer-Encoding: base64");
        assertThat(h).containsPattern("Message-ID: <crm\\.[0-9a-f]{32}@company\\.test>");

        // the trace id is opaque: no batch or record id is visible to the recipient
        assertThat(mime).doesNotContain("batch-1").doesNotContain("LEAD:42");

        String body = mime.substring(mime.indexOf("\r\n\r\n") + 4).replace("\r\n", "");
        assertThat(new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8)).isEqualTo("Line 1\r\nLine 2");
    }

    @Test
    void theSameLogicalSend_getsTheSameMessageId() {

        OutgoingEmail e = new OutgoingEmail("a@c.test", null, "b@c.test", "S", "B", List.of(), "k1");

        String id1 = headers(new String(MimeMessageBuilder.build(e), StandardCharsets.US_ASCII)).lines()
                .filter(l -> l.startsWith("Message-ID")).findFirst().orElseThrow();
        String id2 = headers(new String(MimeMessageBuilder.build(e), StandardCharsets.US_ASCII)).lines()
                .filter(l -> l.startsWith("Message-ID")).findFirst().orElseThrow();

        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void headerInjectionIsImpossible() {

        String mime = new String(MimeMessageBuilder.build(new OutgoingEmail(
                "a@c.test", "Name\r\nBcc: evil@x.test", "b@c.test\r\nBcc: evil@x.test",
                "Subject\r\nBcc: evil@x.test", "Body", List.of(), null)), StandardCharsets.US_ASCII);

        assertThat(headers(mime).lines()).noneMatch(l -> l.startsWith("Bcc:"));
    }

    @Test
    void nonAsciiSubjectAndName_areEncodedWords() {

        String subject = "Offre spéciale – ₹ 5,000 pour l'été, valable jusqu'au 31 décembre pour tous nos clients";

        String mime = new String(MimeMessageBuilder.build(new OutgoingEmail(
                "a@c.test", "Zoë", "b@c.test", subject, "x", List.of(), null)), StandardCharsets.US_ASCII);

        String h = headers(mime);

        assertThat(h).contains("From: =?UTF-8?B?");

        // every encoded-word fits RFC 2047's 75-character limit, and they decode back to the subject
        String subjectHeader = h.substring(h.indexOf("Subject: ") + 9, h.indexOf("\r\nDate:"));
        StringBuilder decoded = new StringBuilder();
        for (String word : subjectHeader.split("\r\n ")) {
            assertThat(word.length()).isLessThanOrEqualTo(75);
            decoded.append(new String(Base64.getDecoder().decode(
                    word.substring("=?UTF-8?B?".length(), word.length() - 2)), StandardCharsets.UTF_8));
        }
        assertThat(decoded.toString()).isEqualTo(subject);
    }

    @Test
    void attachments_goInAMultipartMixedMessage() {

        String mime = new String(MimeMessageBuilder.build(new OutgoingEmail(
                "a@c.test", null, "b@c.test", "S", "Body",
                List.of(new EmailAttachment("Brochure 2026.pdf", new byte[]{1, 2, 3})), null)), StandardCharsets.US_ASCII);

        assertThat(mime).contains("Content-Type: multipart/mixed; boundary=\"=_crm_");
        assertThat(mime).contains("Content-Type: application/pdf; name=\"Brochure 2026.pdf\"");
        assertThat(mime).contains("filename*=UTF-8''Brochure%202026.pdf");
        assertThat(mime).contains(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
        assertThat(mime.trim()).endsWith("--");
    }
}
