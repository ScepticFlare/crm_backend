package com.compact.crm.service;

import com.compact.crm.entity.WhatsAppConsent;
import com.compact.crm.entity.WhatsAppSendLog;
import com.compact.crm.enums.BulkEmailTarget;
import com.compact.crm.enums.WhatsAppConsentStatus;
import com.compact.crm.enums.WhatsAppDeliveryStatus;
import com.compact.crm.enums.WhatsAppSendStatus;
import com.compact.crm.repository.WhatsAppConsentRepository;
import com.compact.crm.repository.WhatsAppSendLogRepository;
import com.compact.crm.repository.WhatsAppWebhookEventRepository;
import com.compact.crm.security.AccessControlService;
import com.compact.crm.security.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WAHA webhook authenticity, idempotency, ordering and opt-out handling on a real
 * H2 database. No WhatsApp provider is involved.
 */
@DataJpaTest
@Import({WhatsAppWebhookService.class, WhatsAppConsentService.class, ActivityLogService.class,
        AccessControlService.class, CurrentUserService.class})
@TestPropertySource(properties = {
        "crm.whatsapp.waha.webhook-hmac-key=test-hmac-secret",
        "crm.whatsapp.waha.session=crm"
})
class WhatsAppWebhookServiceTest {

    @Autowired private WhatsAppWebhookService webhooks;
    @Autowired private WhatsAppSendLogRepository sendLogRepository;
    @Autowired private WhatsAppConsentRepository consentRepository;
    @Autowired private WhatsAppWebhookEventRepository eventRepository;

    // ============================================================ authenticity

    @Test
    void aCorrectSha512HmacOverTheRawBodyIsAccepted_anythingElseIsRejected() throws Exception {

        byte[] body = ack("m-1", 2, "crm");
        String good = hmac("test-hmac-secret", body);

        assertThat(webhooks.verify(body, good, "sha512")).isEqualTo(WhatsAppWebhookService.Verdict.OK);
        assertThat(webhooks.verify(body, good.toUpperCase(), null)).isEqualTo(WhatsAppWebhookService.Verdict.OK);

        assertThat(webhooks.verify(body, null, "sha512")).isEqualTo(WhatsAppWebhookService.Verdict.REJECTED_SIGNATURE);
        assertThat(webhooks.verify(body, hmac("wrong-secret", body), "sha512"))
                .isEqualTo(WhatsAppWebhookService.Verdict.REJECTED_SIGNATURE);
        assertThat(webhooks.verify(body, good, "sha256")).isEqualTo(WhatsAppWebhookService.Verdict.REJECTED_SIGNATURE);

        // one changed byte invalidates the signature
        byte[] tampered = ack("m-1", 3, "crm");
        assertThat(webhooks.verify(tampered, good, "sha512")).isEqualTo(WhatsAppWebhookService.Verdict.REJECTED_SIGNATURE);
    }

    // ============================================================ delivery status

    @Test
    void ackUpdatesTheMatchingMessage_monotonically_andDuplicatesAreIgnored() {

        sendLog("m-1");

        assertThat(webhooks.handle(ack("m-1", 1, "crm"))).isEqualTo(WhatsAppWebhookService.Outcome.APPLIED);
        assertThat(status("m-1")).isEqualTo(WhatsAppDeliveryStatus.SENT);

        assertThat(webhooks.handle(ack("m-1", 3, "crm"))).isEqualTo(WhatsAppWebhookService.Outcome.APPLIED);
        assertThat(status("m-1")).isEqualTo(WhatsAppDeliveryStatus.READ);

        // delayed, out-of-order events never move it back
        webhooks.handle(ack("m-1", 2, "crm"));
        webhooks.handle(ack("m-1", -1, "crm"));
        assertThat(status("m-1")).isEqualTo(WhatsAppDeliveryStatus.READ);

        // the same event delivered again is recognised
        assertThat(webhooks.handle(ack("m-1", 3, "crm"))).isEqualTo(WhatsAppWebhookService.Outcome.DUPLICATE);
    }

    @Test
    void anErrorAfterSentIsRecorded_butADeliveryReceiptBeatsAnEarlierError() {

        sendLog("m-2");
        webhooks.handle(ack("m-2", 1, "crm"));
        webhooks.handle(ack("m-2", -1, "crm"));
        assertThat(status("m-2")).isEqualTo(WhatsAppDeliveryStatus.FAILED);

        webhooks.handle(ack("m-2", 2, "crm"));
        assertThat(status("m-2")).isEqualTo(WhatsAppDeliveryStatus.DELIVERED);
    }

    @Test
    void anAckForAnUnknownMessageIsKept_andAppliedWhenTheRowAppears() {

        assertThat(webhooks.handle(ack("m-late", 2, "crm"))).isEqualTo(WhatsAppWebhookService.Outcome.PENDING);

        sendLog("m-late");
        assertThat(webhooks.applyPending(java.util.List.of("m-late"))).isEqualTo(1);
        assertThat(status("m-late")).isEqualTo(WhatsAppDeliveryStatus.DELIVERED);

        // a redelivery of the same ack later is a harmless duplicate
        assertThat(webhooks.handle(ack("m-late", 2, "crm"))).isEqualTo(WhatsAppWebhookService.Outcome.DUPLICATE);
    }

    @Test
    void eventsForAnotherSessionOrUndocumentedAcksChangeNothing() {

        sendLog("m-3");

        assertThat(webhooks.handle(ack("m-3", 3, "someone-else"))).isEqualTo(WhatsAppWebhookService.Outcome.IGNORED);
        assertThat(webhooks.handle(ack("m-3", 99, "crm"))).isEqualTo(WhatsAppWebhookService.Outcome.IGNORED);
        assertThat(status("m-3")).isNull();

        assertThat(webhooks.handle("not json".getBytes(StandardCharsets.UTF_8))).isEqualTo(WhatsAppWebhookService.Outcome.INVALID);
        assertThat(webhooks.handle("{\"event\":\"message.ack\",\"session\":\"crm\"}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(WhatsAppWebhookService.Outcome.INVALID);
    }

    // ============================================================ opt-out

    @Test
    void anInboundStopOrUnsubscribe_optsTheSenderOut_onlyOnce() {

        consentRepository.save(WhatsAppConsent.builder().phoneE164("919876500001").status(WhatsAppConsentStatus.OPTED_IN)
                .source("FORM").consentedAt(LocalDateTime.now().minusDays(3)).build());

        assertThat(webhooks.handle(inbound("in-1", "919876500001@c.us", " stop. ", "crm")))
                .isEqualTo(WhatsAppWebhookService.Outcome.OPTED_OUT);

        WhatsAppConsent c = consentRepository.findByPhoneE164("919876500001").orElseThrow();
        assertThat(c.getStatus()).isEqualTo(WhatsAppConsentStatus.OPTED_OUT);
        assertThat(c.getSource()).isEqualTo(WhatsAppConsentService.SOURCE_INBOUND_STOP);

        // the same webhook again
        assertThat(webhooks.handle(inbound("in-1", "919876500001@c.us", "STOP", "crm")))
                .isEqualTo(WhatsAppWebhookService.Outcome.DUPLICATE);

        assertThat(webhooks.handle(inbound("in-2", "919876500002@c.us", "Unsubscribe", "crm")))
                .isEqualTo(WhatsAppWebhookService.Outcome.OPTED_OUT);
        assertThat(consentRepository.findByPhoneE164("919876500002").orElseThrow().getStatus())
                .isEqualTo(WhatsAppConsentStatus.OPTED_OUT);
    }

    @Test
    void onlyAnExactKeywordCounts_andOrdinaryMessagesAreNeitherActedOnNorStored() {

        for (String text : new String[]{"please don't stop", "STOP sending price lists?? call me", "stopwatch", "Hi"}) {
            assertThat(webhooks.handle(inbound("x-" + text.hashCode(), "919876500003@c.us", text, "crm")))
                    .as(text).isEqualTo(WhatsAppWebhookService.Outcome.IGNORED);
        }

        assertThat(consentRepository.findByPhoneE164("919876500003")).isEmpty();
        assertThat(eventRepository.count()).isZero();

        assertThat(WhatsAppWebhookService.isOptOut("STOP")).isTrue();
        assertThat(WhatsAppWebhookService.isOptOut("  unsubscribe!  ")).isTrue();
        assertThat(WhatsAppWebhookService.isOptOut("stop it")).isFalse();
    }

    @Test
    void ownMessages_otherSessions_andHiddenSenderIdsNeverChangeConsent() {

        assertThat(webhooks.handle(inboundFromMe("in-3", "919876500004@c.us", "STOP")))
                .isEqualTo(WhatsAppWebhookService.Outcome.IGNORED);
        assertThat(webhooks.handle(inbound("in-4", "919876500004@c.us", "STOP", "other")))
                .isEqualTo(WhatsAppWebhookService.Outcome.IGNORED);
        assertThat(webhooks.handle(inbound("in-5", "123456789012345@lid", "STOP", "crm")))
                .isEqualTo(WhatsAppWebhookService.Outcome.IGNORED);

        assertThat(consentRepository.count()).isZero();
    }

    // ============================================================ helpers

    private void sendLog(String messageId) {
        sendLogRepository.saveAndFlush(WhatsAppSendLog.builder().batchId("batch-" + messageId)
                .target(BulkEmailTarget.LEAD).targetId(1L).phoneE164("919876500001")
                .status(WhatsAppSendStatus.ACCEPTED).providerMessageId(messageId).build());
    }

    private WhatsAppDeliveryStatus status(String messageId) {
        return sendLogRepository.findByProviderMessageId(messageId).orElseThrow().getDeliveryStatus();
    }

    private static byte[] ack(String id, int ack, String session) {
        return ("{\"id\":\"evt_1\",\"event\":\"message.ack\",\"session\":\"" + session + "\",\"engine\":\"WEBJS\","
                + "\"payload\":{\"id\":\"" + id + "\",\"from\":\"919876500001@c.us\",\"fromMe\":true,\"ack\":" + ack
                + ",\"ackName\":\"X\"}}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] inbound(String id, String from, String body, String session) {
        return ("{\"event\":\"message\",\"session\":\"" + session + "\",\"payload\":{\"id\":\"false_" + from + "_" + id
                + "\",\"from\":\"" + from + "\",\"fromMe\":false,\"body\":\"" + body + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] inboundFromMe(String id, String from, String body) {
        return ("{\"event\":\"message\",\"session\":\"crm\",\"payload\":{\"id\":\"true_" + from + "_" + id
                + "\",\"from\":\"" + from + "\",\"fromMe\":true,\"body\":\"" + body + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    static String hmac(String key, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
