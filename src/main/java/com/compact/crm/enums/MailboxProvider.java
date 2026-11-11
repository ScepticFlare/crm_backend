package com.compact.crm.enums;

import java.util.Locale;

// Where an employee's connected mailbox lives (see entity.EmailMailboxAccount).
// GOOGLE = Gmail / Google Workspace through the Gmail API; MICROSOFT = Outlook.com
// / Microsoft 365 through Microsoft Graph.
public enum MailboxProvider {

    GOOGLE,
    MICROSOFT;

    // Path segment ("google" / "microsoft") -> provider; anything else is a 400.
    public static MailboxProvider fromPath(String value) {

        if (value != null) {
            for (MailboxProvider p : values()) {
                if (p.name().equalsIgnoreCase(value.trim())) {
                    return p;
                }
            }
        }

        throw new IllegalArgumentException("Unknown email provider. Use google or microsoft.");
    }

    public String pathName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String label() {
        return this == GOOGLE ? "Google" : "Microsoft";
    }
}
