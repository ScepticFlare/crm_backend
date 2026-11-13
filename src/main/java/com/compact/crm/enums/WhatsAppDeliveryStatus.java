package com.compact.crm.enums;

// Delivery progress of an ACCEPTED message, taken ONLY from the provider's
// documented `message.ack` webhook (WAHA ack values):
//   ack -1 ERROR   -> FAILED
//   ack  0 PENDING -> PENDING
//   ack  1 SERVER  -> SENT       (reached WhatsApp's servers)
//   ack  2 DEVICE  -> DELIVERED  (reached the recipient's device)
//   ack  3 READ    -> READ
//   ack  4 PLAYED  -> READ       (voice/video played - treated as read)
//
// Updates are monotonic by `rank`: a delayed or duplicated webhook can never
// move a message backwards (e.g. READ -> DELIVERED). FAILED ranks above SENT
// (an error after the server accepted it is news) but below DELIVERED (a
// delivery receipt is stronger evidence than an earlier error).
public enum WhatsAppDeliveryStatus {
    PENDING(0),
    SENT(10),
    FAILED(15),
    DELIVERED(20),
    READ(30);

    private final int rank;

    WhatsAppDeliveryStatus(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    // Maps a documented WAHA ack number; anything else is not mapped (null).
    public static WhatsAppDeliveryStatus fromAck(Integer ack) {

        if (ack == null) {
            return null;
        }

        return switch (ack) {
            case -1 -> FAILED;
            case 0 -> PENDING;
            case 1 -> SENT;
            case 2 -> DELIVERED;
            case 3, 4 -> READ;
            default -> null;
        };
    }
}
