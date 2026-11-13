package com.compact.crm.enums;

// WhatsApp consent of ONE normalized phone number (whatsapp_consent). A number
// with no row has given no consent: it is never messaged in bulk.
//   OPTED_IN  - documented opt-in (source + date + evidence) is on record
//   OPTED_OUT - the person asked not to be messaged; always wins, and only an
//               administrator can record a new, later opt-in
public enum WhatsAppConsentStatus {
    OPTED_IN,
    OPTED_OUT
}
