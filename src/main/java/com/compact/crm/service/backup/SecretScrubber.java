package com.compact.crm.service.backup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Turns pg_dump / pg_restore error output into a short message that is safe to
// store, log and show to an admin. libpq errors name the host, its IP address,
// the user and the database ("connection to server at "h" (1.2.3.4), port 5432
// failed: FATAL: password authentication failed for user "u""); all of those,
// the password, any connection URI and any key=value secret are replaced with
// [redacted]. The result is a single line of at most MAX_LENGTH characters.
public final class SecretScrubber {

    static final int MAX_LENGTH = 300;
    private static final String REDACTED = "[redacted]";

    private static final Pattern URI = Pattern.compile("(?i)postgres(?:ql)?://\\S+");
    private static final Pattern KEY_VALUE = Pattern.compile("(?i)\\b(password|passwd|pwd|user|host|hostaddr|dbname|apikey|token|secret)\\s*=\\s*\\S+");
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");
    // a run of hex digits and colons with at least two colons (IPv6, possibly "::"-compressed)
    private static final Pattern IPV6 = Pattern.compile("(?i)(?<![\\w:])(?=[0-9a-f:]*:[0-9a-f:]*:)[0-9a-f:]{3,39}(?![\\w:])");

    private final List<String> secrets;

    public SecretScrubber(String... secrets) {
        List<String> s = new ArrayList<>();
        for (String secret : secrets) {
            if (secret != null && secret.length() >= 3) {
                s.add(secret);
            }
        }
        // longest first, so a secret containing another is replaced whole
        s.sort(Comparator.comparingInt(String::length).reversed());
        this.secrets = List.copyOf(s);
    }

    public String scrub(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String out = text;
        for (String secret : secrets) {
            out = Pattern.compile(Pattern.quote(secret), Pattern.CASE_INSENSITIVE).matcher(out)
                    .replaceAll(Matcher.quoteReplacement(REDACTED));
        }
        out = URI.matcher(out).replaceAll(REDACTED);
        out = KEY_VALUE.matcher(out).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "=" + REDACTED));
        out = IPV4.matcher(out).replaceAll(REDACTED);
        out = IPV6.matcher(out).replaceAll(REDACTED);
        out = out.replaceAll("\\s+", " ").trim();
        return out.length() <= MAX_LENGTH ? out : out.substring(0, MAX_LENGTH - 3) + "...";
    }
}
