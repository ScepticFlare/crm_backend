package com.compact.crm.service;

import com.compact.crm.entity.WhatsAppSendLog;
import com.compact.crm.entity.WhatsAppWebhookEvent;
import com.compact.crm.enums.WhatsAppDeliveryStatus;
import com.compact.crm.repository.WhatsAppSendLogRepository;
import com.compact.crm.repository.WhatsAppWebhookEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Inbound WAHA webhooks (POST /api/public/whatsapp/webhook).
//
// Authenticity: WAHA signs each webhook with HMAC when a key is configured
// (WHATSAPP_HOOK_HMAC_KEY on WAHA / per-session `hmac.key`): header
// `X-Webhook-Hmac` over the raw body, `X-Webhook-Hmac-Algorithm: sha512`. The
// digest is compared as hex (WAHA's documented example is a 128-hex-char value).
// No key configured here, a missing / wrong signature or another algorithm =>
// the event is REJECTED and nothing is changed. The signature covers the body
// only (WAHA does not sign its timestamp header), so a captured request could be
// replayed - which is harmless here because every effect is idempotent and
// delivery states only move forward.
//
// Events handled (everything else is acknowledged and ignored):
//   message.ack     -> delivery status of a message WE sent, matched by the
//                      provider message id; monotonic (never downgraded); stored
//                      unapplied if it arrives before its send-log row exists.
//   message         -> inbound text from a customer; ONLY an exact "STOP" or
//                      "UNSUBSCRIBE" (case-insensitive, surrounding punctuation /
//                      spaces ignored) records an opt-out for the sender's number.
//                      No other inbound content is stored or acted on.
//   session.status  -> logged (the access endpoint asks WAHA live).
// Events for another session name are ignored. Inbound text is never treated as
// an instruction beyond that single opt-out keyword match.
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppWebhookService {

    public static final Set<String> OPT_OUT_KEYWORDS = Set.of("STOP", "UNSUBSCRIBE");

    public enum Verdict { REJECTED_NOT_CONFIGURED, REJECTED_SIGNATURE, OK }

    public enum Outcome { APPLIED, PENDING, DUPLICATE, IGNORED, OPTED_OUT, INVALID }

    private final WhatsAppWebhookEventRepository eventRepository;
    private final WhatsAppSendLogRepository sendLogRepository;
    private final WhatsAppConsentService consentService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${crm.whatsapp.waha.webhook-hmac-key:}")
    private String hmacKey = "";

    @Value("${crm.whatsapp.waha.session:default}")
    private String sessionName = "default";

    // ------------------------------------------------------------ authenticity

    public Verdict verify(byte[] body, String signature, String algorithm) {

        if (hmacKey == null || hmacKey.isBlank()) {
            return Verdict.REJECTED_NOT_CONFIGURED;
        }

        if (signature == null || signature.isBlank() || body == null) {
            return Verdict.REJECTED_SIGNATURE;
        }

        if (algorithm != null && !algorithm.isBlank() && !"sha512".equalsIgnoreCase(algorithm.trim())) {
            return Verdict.REJECTED_SIGNATURE;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(hmacKey.trim().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(body)).getBytes(StandardCharsets.US_ASCII);
            byte[] given = signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);

            return MessageDigest.isEqual(expected, given) ? Verdict.OK : Verdict.REJECTED_SIGNATURE;

        } catch (Exception e) {
            log.warn("WhatsApp webhook: signature check failed ({})", e.getClass().getSimpleName());
            return Verdict.REJECTED_SIGNATURE;
        }
    }

    // ------------------------------------------------------------ handling

    // Call only after verify(...) == OK.
    public Outcome handle(byte[] body) {

        JsonNode root;

        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            return Outcome.INVALID;
        }

        if (root == null || !root.isObject()) {
            return Outcome.INVALID;
        }

        String event = text(root, "event");
        String session = text(root, "session");
        JsonNode payload = root.get("payload");

        if (event == null || payload == null || !payload.isObject()) {
            return Outcome.INVALID;
        }

        if (session == null || !session.equals(sessionName)) {
            log.warn("WhatsApp webhook: event for an unexpected session ignored");
            return Outcome.IGNORED;
        }

        return switch (event) {
            case "message.ack" -> handleAck(payload, session);
            case "message" -> handleInbound(payload, session);
            case "session.status" -> {
                log.info("WhatsApp session status reported by provider: {}", safe(text(payload, "status")));
                yield Outcome.IGNORED;
            }
            default -> Outcome.IGNORED;
        };
    }

    private Outcome handleAck(JsonNode payload, String session) {

        String messageId = messageId(payload);
        Integer ack = payload.hasNonNull("ack") && payload.get("ack").canConvertToInt() ? payload.get("ack").asInt() : null;

        if (messageId == null || ack == null) {
            return Outcome.INVALID;
        }

        // Only acks of messages this account sent are delivery statuses.
        if (payload.has("fromMe") && !payload.get("fromMe").asBoolean(false)) {
            return Outcome.IGNORED;
        }

        String key = "ack:" + messageId + ":" + ack;

        if (eventRepository.existsByEventKey(key)) {
            // A redelivery: make sure an earlier, not-yet-applied copy gets applied.
            applyPending(List.of(messageId));
            return Outcome.DUPLICATE;
        }

        WhatsAppDeliveryStatus status = WhatsAppDeliveryStatus.fromAck(ack);

        WhatsAppWebhookEvent saved;

        try {
            saved = eventRepository.saveAndFlush(WhatsAppWebhookEvent.builder()
                    .eventKey(key)
                    .eventType("message.ack")
                    .providerMessageId(messageId)
                    .ack(ack)
                    .sessionName(session)
                    .applied(status == null)
                    .receivedAt(LocalDateTime.now())
                    .build());
        } catch (DataIntegrityViolationException e) {
            return Outcome.DUPLICATE;
        }

        if (status == null) {
            return Outcome.IGNORED;
        }

        if (apply(messageId, status)) {
            saved.setApplied(true);
            eventRepository.save(saved);
            return Outcome.APPLIED;
        }

        // The send-log row is not written yet (the webhook raced the send
        // response); applied when the row is saved, or on the next batch read.
        return Outcome.PENDING;
    }

    private Outcome handleInbound(JsonNode payload, String session) {

        if (payload.path("fromMe").asBoolean(false)) {
            return Outcome.IGNORED;
        }

        String bodyText = text(payload, "body");

        if (!isOptOut(bodyText)) {
            // Not stored, not acted on.
            return Outcome.IGNORED;
        }

        String messageId = messageId(payload);
        String from = text(payload, "from");

        if (messageId == null || from == null) {
            return Outcome.INVALID;
        }

        String key = "stop:" + messageId;

        if (eventRepository.existsByEventKey(key)) {
            return Outcome.DUPLICATE;
        }

        if (!from.endsWith("@c.us")) {
            // e.g. a hidden "@lid" sender id: the phone number is not known, so
            // the opt-out cannot be attributed. Logged for an administrator.
            log.warn("WhatsApp webhook: an opt-out request arrived from a sender id that is not a phone number; "
                    + "it could not be recorded automatically");
            saveMarker(key, "message.stop", messageId, session);
            return Outcome.IGNORED;
        }

        String digits = from.substring(0, from.length() - "@c.us".length()).replaceAll("[^0-9]", "");
        String phone = consentService.normalize("+" + digits);

        if (phone == null) {
            // Not a number this CRM can message, so no consent exists for it.
            saveMarker(key, "message.stop", messageId, session);
            return Outcome.IGNORED;
        }

        // Persist the opt-out first, then the idempotency marker.
        consentService.optOutFromInbound(phone, "WhatsApp reply \"" + bodyText.strip().toUpperCase(Locale.ROOT)
                + "\" (message " + truncate(messageId, 120) + ")");

        saveMarker(key, "message.stop", messageId, session);

        log.info("WhatsApp opt-out recorded from an inbound reply for {}", consentService.mask(phone));

        return Outcome.OPTED_OUT;
    }

    // ------------------------------------------------------------ delivery updates

    // Applies stored-but-unapplied delivery events for these message ids (their
    // send-log rows now exist). Returns how many were applied.
    public int applyPending(Collection<String> messageIds) {

        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }

        int applied = 0;

        for (WhatsAppWebhookEvent event : eventRepository.findByProviderMessageIdInAndAppliedFalse(messageIds)) {

            WhatsAppDeliveryStatus status = WhatsAppDeliveryStatus.fromAck(event.getAck());

            if (status == null || apply(event.getProviderMessageId(), status)) {
                event.setApplied(true);
                eventRepository.save(event);
                applied++;
            }
        }

        return applied;
    }

    // True when the send-log row exists (whether or not it advanced - a lower
    // or equal status is simply not applied).
    boolean apply(String messageId, WhatsAppDeliveryStatus status) {

        if (sendLogRepository.findByProviderMessageId(messageId).map(WhatsAppSendLog::getId).isEmpty()) {
            return false;
        }

        List<WhatsAppDeliveryStatus> lower = Arrays.stream(WhatsAppDeliveryStatus.values())
                .filter(s -> s.rank() < status.rank())
                .toList();

        if (lower.isEmpty()) {
            // The lowest status (PENDING) only fills an unset one ("in ()" is not portable JPQL).
            sendLogRepository.advanceDeliveryFromUnset(messageId, status, LocalDateTime.now());
        } else {
            sendLogRepository.advanceDelivery(messageId, status, lower, LocalDateTime.now());
        }

        return true;
    }

    // ------------------------------------------------------------ helpers

    // Exactly one keyword, ignoring case, surrounding whitespace and punctuation.
    public static boolean isOptOut(String body) {

        if (body == null) {
            return false;
        }

        String cleaned = body.strip().replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "").toUpperCase(Locale.ROOT);

        return OPT_OUT_KEYWORDS.contains(cleaned);
    }

    private void saveMarker(String key, String type, String messageId, String session) {
        try {
            eventRepository.saveAndFlush(WhatsAppWebhookEvent.builder()
                    .eventKey(key).eventType(type).providerMessageId(truncate(messageId, 200))
                    .sessionName(session).applied(true).receivedAt(LocalDateTime.now()).build());
        } catch (DataIntegrityViolationException e) {
            // Already recorded by a concurrent delivery of the same webhook.
        }
    }

    // `id` as a string, or an object with `_serialized`.
    private static String messageId(JsonNode payload) {

        JsonNode id = payload.get("id");

        if (id == null || id.isNull()) {
            return null;
        }

        String value = id.isTextual() ? id.asText() : id.hasNonNull("_serialized") ? id.get("_serialized").asText() : null;

        return value == null || value.isBlank() || value.length() > 200 ? null : value.trim();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static String safe(String value) {
        return value == null ? "?" : value.replaceAll("[^A-Za-z_]", "");
    }

    private static String truncate(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }
}
