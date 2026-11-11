package com.compact.crm.service.mail;

import com.compact.crm.service.EmailAttachment;

import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

// Builds the RFC 5322 / MIME message the Gmail API sends (messages.send with a
// raw message/rfc822 upload). Plain text only - the CRM has only ever sent
// plain-text bodies - with optional attachments (multipart/mixed). Header
// values are sanitized (CR/LF removed, so no header can be injected) and
// non-ASCII text is RFC 2047 encoded. Body and attachments are base64 with
// 76-character lines.
public final class MimeMessageBuilder {

    private static final String CRLF = "\r\n";

    private MimeMessageBuilder() {
    }

    public static byte[] build(OutgoingEmail email) {

        StringBuilder out = new StringBuilder(1024);

        header(out, "From", mailbox(email.fromName(), email.fromAddress()));
        header(out, "To", "<" + clean(email.to()) + ">");
        header(out, "Subject", encodeText(clean(email.subject())));
        header(out, "Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now()));
        header(out, "Message-ID", messageId(email));
        header(out, "MIME-Version", "1.0");

        if (email.sendKey() != null) {
            header(out, "X-CRM-Send-Id", sendId(email.sendKey()));
        }

        String body = email.body() == null ? "" : email.body().replace("\r\n", "\n").replace("\n", CRLF);

        if (email.attachments().isEmpty()) {

            textPartHeaders(out);
            out.append(CRLF).append(base64Lines(body.getBytes(StandardCharsets.UTF_8)));

        } else {

            String boundary = "=_crm_" + UUID.randomUUID().toString().replace("-", "");

            header(out, "Content-Type", "multipart/mixed; boundary=\"" + boundary + "\"");
            out.append(CRLF);
            out.append("This is a multi-part message in MIME format.").append(CRLF);

            out.append("--").append(boundary).append(CRLF);
            textPartHeaders(out);
            out.append(CRLF).append(base64Lines(body.getBytes(StandardCharsets.UTF_8)));

            for (EmailAttachment a : email.attachments()) {

                String name = clean(a.filename() == null || a.filename().isBlank() ? "attachment" : a.filename());

                out.append("--").append(boundary).append(CRLF);
                header(out, "Content-Type", contentType(name) + "; name=\"" + asciiFallback(name) + "\"");
                header(out, "Content-Transfer-Encoding", "base64");
                header(out, "Content-Disposition", "attachment; filename=\"" + asciiFallback(name) + "\"; "
                        + "filename*=UTF-8''" + percentEncode(name));
                out.append(CRLF).append(base64Lines(a.bytes() == null ? new byte[0] : a.bytes()));
            }

            out.append("--").append(boundary).append("--").append(CRLF);
        }

        return out.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static void textPartHeaders(StringBuilder out) {
        header(out, "Content-Type", "text/plain; charset=UTF-8");
        header(out, "Content-Transfer-Encoding", "base64");
    }

    private static void header(StringBuilder out, String name, String value) {
        out.append(name).append(": ").append(value).append(CRLF);
    }

    // Deterministic per logical send, so a message can be matched to its CRM
    // record in the Sent folder (and a client that de-duplicates by Message-ID
    // will treat a repeated send as the same message).
    private static String messageId(OutgoingEmail email) {

        String domain = email.fromAddress() != null && email.fromAddress().contains("@")
                ? email.fromAddress().substring(email.fromAddress().indexOf('@') + 1) : "crm.local";

        String local = email.sendKey() != null
                ? sendId(email.sendKey())
                : UUID.randomUUID().toString().replace("-", "");

        return "<crm." + local + "@" + clean(domain) + ">";
    }

    // Opaque trace id for a logical send: a hash, so no internal record id or
    // batch id is visible to the recipient.
    static String sendId(String sendKey) {
        return OAuthSupport.sha256Hex(sendKey).substring(0, 32);
    }

    private static String mailbox(String name, String address) {

        String addr = "<" + clean(address) + ">";

        if (name == null || name.isBlank()) {
            return addr;
        }

        return encodePhrase(clean(name)) + " " + addr;
    }

    // Removes CR, LF and other control characters: a header value can never
    // start a new header line.
    static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\\t\\x00-\\x1F\\x7F]+", " ").trim();
    }

    private static boolean isPrintableAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    // Display names: quoted when ASCII, RFC 2047 encoded otherwise.
    private static String encodePhrase(String name) {

        if (isPrintableAscii(name)) {
            return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        return encodeWords(name);
    }

    // Unstructured text (Subject).
    static String encodeText(String text) {
        return isPrintableAscii(text) && text.length() <= 900 ? text : encodeWords(text);
    }

    // RFC 2047 "B" encoded-words of at most 75 characters each, split on code
    // point boundaries and folded onto continuation lines.
    private static String encodeWords(String text) {

        StringBuilder result = new StringBuilder();
        StringBuilder chunk = new StringBuilder();
        int chunkBytes = 0;

        for (int i = 0; i < text.length(); ) {

            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int bytes = ch.getBytes(StandardCharsets.UTF_8).length;

            if (chunkBytes + bytes > 45) {
                appendWord(result, chunk.toString());
                chunk.setLength(0);
                chunkBytes = 0;
            }

            chunk.append(ch);
            chunkBytes += bytes;
            i += Character.charCount(cp);
        }

        if (chunk.length() > 0 || result.length() == 0) {
            appendWord(result, chunk.toString());
        }

        return result.toString();
    }

    private static void appendWord(StringBuilder result, String part) {

        if (result.length() > 0) {
            result.append(CRLF).append(' ');
        }

        result.append("=?UTF-8?B?")
                .append(Base64.getEncoder().encodeToString(part.getBytes(StandardCharsets.UTF_8)))
                .append("?=");
    }

    private static String base64Lines(byte[] bytes) {
        return Base64.getMimeEncoder(76, CRLF.getBytes(StandardCharsets.US_ASCII)).encodeToString(bytes) + CRLF;
    }

    private static String contentType(String filename) {
        String guessed = URLConnection.guessContentTypeFromName(filename.toLowerCase(Locale.ROOT));
        return guessed != null ? guessed : "application/octet-stream";
    }

    private static String asciiFallback(String name) {
        return name.replaceAll("[^A-Za-z0-9._ ()-]", "_");
    }

    private static String percentEncode(String name) {

        StringBuilder out = new StringBuilder();

        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_') {
                out.append((char) c);
            } else {
                out.append('%').append(String.format("%02X", c));
            }
        }

        return out.toString();
    }

    static String contentTypeOf(String filename) {
        return contentType(filename == null ? "" : filename);
    }
}
