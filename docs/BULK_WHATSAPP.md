# Bulk WhatsApp (WAHA)

Bulk WhatsApp lets authorised users send an admin-made message template to selected Leads or Opportunities over WhatsApp. It uses a self-hosted **WAHA** (WhatsApp HTTP API, <https://waha.devlike.pro/>) instance. It follows the Bulk Email design: server-pinned batches, claim-before-send, ACCEPTED/FAILED/UNKNOWN outcomes and recovery after a refresh. On top of that it adds explicit opt-in consent and delivery status from webhooks.

> **Status:** implemented and covered by automated tests that use a fake provider. It has **not** been connected to a real WAHA instance or a real WhatsApp number. Do that only in staging, with a separate test number, after the checklist below.

---

## 1. Important caveats — read first

- **WAHA is not the official WhatsApp Business Platform.** It automates a linked WhatsApp account through WhatsApp Web (default engine `WEBJS`). WhatsApp can restrict or ban numbers that send unwanted messages. WAHA's own guidance ("How to avoid blocking") warns that bulk messages to people who have not contacted you, and being reported as spam a few times, can get a number banned. This CRM does **not** include, and must not get, any anti-ban, anti-detection or account-rotation features.
- **Use a dedicated test number**, never the company's primary number, until the behaviour has been observed in staging.
- **Templates here are CRM drafts.** They are not WhatsApp/Meta-approved templates and nothing is submitted to Meta.
- **Licensing:** as of WAHA 2026.6.1, "WAHA Plus" is merged into the free, open-source Core and there is a single image, `devlikeapro/waha` (<https://waha.devlike.pro/docs/how-to/waha-plus/>). Check the current docs before deploying, because this has changed before.
- **"Accepted" is not "delivered".** A 2xx from WAHA only means WAHA took the message. Sent / Delivered / Read come later from `message.ack` webhooks, when WAHA reports them.

---

## 2. Deploying WAHA (staging first)

Run WAHA as a **separate service**, not inside the CRM backend container. It needs persistent storage for the session and keeps a long-running browser/WebSocket connection, which the CRM's Render web service is not designed for. A small VM or a Render private service with a persistent disk are both reasonable choices. Only the CRM backend should be able to reach it.

### docker-compose (example — pin a real version)

```yaml
services:
  waha:
    # Pin an exact release tag from Docker Hub (devlikeapro/waha) - never "latest" in production.
    image: devlikeapro/waha:<PINNED_VERSION>
    restart: always
    ports:
      - "127.0.0.1:3000:3000"          # not exposed publicly; the CRM reaches it over a private network / tunnel
    volumes:
      - ./.sessions:/app/.sessions      # session persistence: survives restarts without re-scanning the QR code
    environment:
      # API key: WAHA recommends storing only a hash, "sha512:<hash>" (see WAHA Security docs).
      WAHA_API_KEY: "sha512:<hex-sha512-of-your-plain-key>"
      WAHA_DASHBOARD_USERNAME: "<admin-user>"
      WAHA_DASHBOARD_PASSWORD: "<long-random-password>"
      WHATSAPP_SWAGGER_USERNAME: "<admin-user>"
      WHATSAPP_SWAGGER_PASSWORD: "<long-random-password>"
      # Webhooks for every session -> the CRM backend (public HTTPS URL)
      WHATSAPP_HOOK_URL: "https://<crm-backend-host>/api/public/whatsapp/webhook"
      WHATSAPP_HOOK_EVENTS: "message,message.ack,session.status"
      WHATSAPP_HOOK_HMAC_KEY: "<long-random-secret>"       # same value as the CRM's WAHA_WEBHOOK_HMAC_KEY
      WHATSAPP_HOOK_RETRIES_POLICY: "exponential"
      WHATSAPP_HOOK_RETRIES_ATTEMPTS: "10"
      WHATSAPP_HOOK_RETRIES_DELAY_SECONDS: "5"
```

Notes:
- Generate the plain API key randomly and keep it only in the CRM's secret store. Produce the hash with the method in WAHA's Security docs (<https://waha.devlike.pro/docs/how-to/security/>). Verify the exact hash format there before relying on it.
- If you set no keys, WAHA generates random secrets at startup and prints them in its logs. Always set your own.
- **Do not expose the dashboard or Swagger publicly.** Password protection for Swagger does not protect the API itself.
- Sessions restart automatically after a container restart (`WAHA_WORKER_RESTART_SESSIONS`, default on).

### Linking the WhatsApp account (QR pairing)

1. Start the session. The default session is named `default`; set `WAHA_SESSION` on the CRM if you use another name. You can start it from the dashboard or with `POST /api/sessions`.
2. Get the QR code from `GET /api/{session}/auth/qr` (or the dashboard) and scan it with the **test** phone: WhatsApp → Linked devices.
3. The first QR code is valid for 60 seconds and later ones for 20 seconds, up to 6 in total. After that the session goes to `FAILED` and must be restarted.
4. `GET /api/sessions/{session}` must report `"status": "WORKING"`. The CRM refuses to send unless this is the case.

### When the session disconnects

WAHA statuses: `STOPPED`, `STARTING`, `SCAN_QR_CODE`, `PASSKEY_REQUIRED`, `PASSKEY_CONFIRMATION_REQUIRED`, `WORKING`, `FAILED`.

- When the status is not `WORKING`, the CRM reports it in the Bulk WhatsApp dialog (`GET /api/bulk-whatsapp/access`). It refuses new batches and sends with **HTTP 503** before claiming any recipient. Nothing is lost or half-sent.
- A disconnect in the middle of a send gives a provider-level failure (timeout, 5xx, 401/403/422/429). That **pauses** the chunk: the recipients not yet attempted stay unclaimed as `NOT_ATTEMPTED` (code `PROVIDER_UNAVAILABLE`) and can be sent later from the dialog.
- `FAILED` usually means the account must be re-linked. Restart the session first; if that does not help, log out, start again and re-scan.

---

## 3. CRM backend configuration

All variables are optional. Without `WAHA_BASE_URL` and `WAHA_API_KEY` the CRM starts normally, logs `WhatsApp sending is DISABLED`, reports *not configured* to authorised users, and refuses every WhatsApp send with 503. Bulk Email is unaffected.

| Env var | Property | Default | Purpose |
|---|---|---|---|
| `WHATSAPP_ENABLED` | `crm.whatsapp.enabled` | `true` | Kill switch. `false` disables all WhatsApp sending without touching anything else |
| `WAHA_BASE_URL` | `crm.whatsapp.waha.base-url` | — | e.g. `http://waha.internal:3000`; must be reachable from the backend |
| `WAHA_API_KEY` | `crm.whatsapp.waha.api-key` | — | The **plain** key (WAHA itself holds only the hash). Sent as `X-Api-Key` |
| `WAHA_SESSION` | `crm.whatsapp.waha.session` | `default` | WAHA session name; webhooks for any other session are ignored |
| `WAHA_WEBHOOK_HMAC_KEY` | `crm.whatsapp.waha.webhook-hmac-key` | — | Same value as WAHA's `WHATSAPP_HOOK_HMAC_KEY`. While unset, every webhook is rejected (503) |
| `WHATSAPP_DEFAULT_COUNTRY_CODE` | `crm.whatsapp.default-country-code` | `91` | Country for 10-digit numbers; only this country is accepted |
| `WHATSAPP_BULK_MAX_RECIPIENTS` | `crm.whatsapp.bulk.max-recipients` | `100` | Records per batch |
| `WHATSAPP_BULK_MAX_CHUNK` | `crm.whatsapp.bulk.max-chunk` | `5` | Records per send request |
| `WHATSAPP_BULK_MIN_INTERVAL_MS` | `crm.whatsapp.bulk.min-interval-ms` | `1500` | Fixed pause between two sends within a request |
| `WHATSAPP_BULK_DEDUPE_HOURS` | `crm.whatsapp.bulk.dedupe-hours` | `24` | "Recently messaged" window (see §6) |

Set the secrets in Render's environment (or another secret store). **Never** commit them or put them in a `.env` file in git. The frontend never receives any WAHA credential.

### Webhook

- URL: `https://<crm-backend-host>/api/public/whatsapp/webhook` (POST). It must be public HTTPS so WAHA can reach it.
- Authentication: WAHA's **HMAC**. The `X-Webhook-Hmac` header is computed over the **raw body** with the shared key, and `X-Webhook-Hmac-Algorithm` is `sha512`. The CRM compares it as a **hex** digest. WAHA's docs show a 128-hex-character example but do not state the encoding explicitly, so **verify with the first staging webhook**. If the encoding differs, every webhook is rejected with 401 (fail-safe) and must be fixed before go-live.
- Limitation: WAHA signs the body only, not its timestamp, so a captured request could be replayed. That is harmless here: every event is stored idempotently, delivery statuses only move forward, and an opt-out is idempotent.
- Events used: `message.ack` (delivery), `message` (inbound — **only** an exact `STOP` / `UNSUBSCRIBE`, case-insensitive, surrounding spaces and punctuation ignored, is acted on), and `session.status` (logged). Inbound message text is **not stored** and is never interpreted as an instruction.
- Render free tier: a sleeping backend misses webhook deliveries until it wakes. WAHA's retry policy (above) re-delivers them.

---

## 4. Templates (admin)

**WhatsApp Templates** (ADMIN only, `WHATSAPP_TEMPLATE_MANAGE`):

- Create, edit, activate or deactivate. Each template has a name, an internal description and a message of at most 4,096 characters.
- **Insert field** offers exactly the merge fields of the existing email renderer: `{{contactPerson}}`, `{{companyName}}`, `{{designation}}`, `{{city}}`, `{{interestedProduct}}`, `{{interestedBattery}}` and `{{opportunityTitle}}` (opportunities only). The aliases `{{name}}`, `{{company}}`, `{{product}}` and so on also work.
- The preview is rendered **by the server** with labelled sample data. A template with an unsupported field, stray `{{`/`}}`, an unfinished `[[...]]` section, or more than 4,096 characters cannot be saved.
- Changing the text creates a new **version**. A batch pins the body and version it was created with, so editing a template never changes an existing batch.

Employees pick an active template and see each recipient's final text, read-only. They never type or fill in fields.

## 5. Consent (opt-in / opt-out)

- Consent belongs to the **normalized number** (`whatsapp_consent`, unique per number). Every lead or opportunity sharing a number shares its consent.
- **No number is opted in automatically.** A phone number on a lead is not consent.
- **Opt-in** needs a source, the date consent was given (not in the future) and evidence. It can be recorded on the lead details page by whoever may edit the lead (`LEAD_MANAGE` within their scope), or by an admin.
- **Opt-out** comes from the lead page, from the admin page, or automatically from an inbound `STOP` / `UNSUBSCRIBE`. It takes effect immediately and is checked again right before every provider call.
- Once a number has opted out, **only an admin** (`WHATSAPP_CONSENT_MANAGE`) can record a new opt-in, and only with a consent date later than the opt-out.
- **Admin bulk import** (WhatsApp Consent page) takes a list of numbers plus one source, date and evidence for all of them. It **never** changes an opted-out number and reports per-row outcomes. A list of numbers on its own is not consent.
- Every change is written to `whatsapp_consent_audit`.
- Limitation: newer WhatsApp accounts may send from a hidden `@lid` id instead of a phone number. An opt-out from such an id cannot be matched to a number automatically; it is logged, and an admin must record it manually. The keyword list (`STOP`, `UNSUBSCRIBE`) is deliberately small and may not cover every legal or language requirement.

## 6. Sending, duplicate protection and retries

1. **Preview** (advisory). This checks scope, number validity, consent, the de-duplication window and whether the template resolves, and lists exclusion reasons: `NOT_AVAILABLE`, `NO_PHONE`, `INVALID_PHONE`, `NO_OPT_IN`, `OPTED_OUT`, `DUPLICATE_PHONE`, `RECENTLY_MESSAGED`, `UNRESOLVED_VARIABLE`. Numbers are shown masked (`+91 ******3210`).
2. **Batch.** Explicit confirmation is required. The server issues a UUID and pins owner, target, template id + version + text + SHA-256 hash, and the ordered record ids. It refuses (503) unless WAHA is configured and `WORKING`.
3. **Chunks.** At most 5 records per request, fixed pacing. Every chunk:
   - re-authorizes and re-validates each record against current data;
   - **claims** each recipient in `whatsapp_send_log` (UNIQUE batch + record) *before* calling WAHA;
   - re-checks consent and earlier claims on the same number;
   - then sends.

   Database transactions are never held open around the HTTP call.
4. **Outcomes:**
   - `ACCEPTED`: WAHA 2xx; logged as `WHATSAPP_SENT` activity, explicitly "not proof of delivery".
   - `FAILED`: a definite 4xx; the only outcome that may be retried.
   - `UNKNOWN`: timeout, 5xx or I/O error; may have been sent; **never retried automatically**, because WAHA has no idempotency key.
   - A claim stuck in PENDING for more than 2 minutes reads as UNKNOWN.
5. **Delivery status:** `message.ack` → `PENDING`/`SENT`/`DELIVERED`/`READ`/`FAILED`, matched by WAHA message id. Updates are monotonic, so late or duplicate events never move a status backwards. An ack that arrives before its send-log row is stored and applied when the row is written, or on the next batch read. If WAHA returns no message id (the response shape differs by engine), the message is still ACCEPTED but cannot be tracked.
6. **De-duplication:** a number or record with an ACCEPTED, UNKNOWN or in-flight attempt in the last 24h (any batch) is excluded as `RECENTLY_MESSAGED`. This window is independent of consent and is not a permanent block. A later delivery `FAILED` ack does **not** clear it, which is conservative.
7. **Recovery:** the browser keeps only a pointer (batch id and record ids, no text or numbers) in sessionStorage. After a refresh, the Leads/Opportunities page offers **Review / resume**, and the server's `GET /api/bulk-whatsapp/batches/{id}` is the source of truth.

## 7. Permissions (migration V15)

| Permission | ADMIN | MANAGER | EMPLOYEE |
|---|---|---|---|
| `WHATSAPP_SEND` | ALL | TEAM | OWN |
| `WHATSAPP_TEMPLATE_MANAGE` | ALL | — | — |
| `WHATSAPP_CONSENT_MANAGE` | ALL | — | — |

## 8. Database (Flyway)

- `V15__whatsapp_templates_consent_and_permissions.sql`: `whatsapp_templates`, `whatsapp_consent`, `whatsapp_consent_audit`, permissions.
- `V16__whatsapp_bulk_batches_send_log_and_webhook_events.sql`: `whatsapp_bulk_batch`, `whatsapp_send_log`, `whatsapp_webhook_event`.

Both are purely additive and safe to re-run. They do not change any existing table and they opt no one in.

## 9. Testing

- `./mvnw test` with JDK 21. All WhatsApp tests use a mocked `WhatsAppClient` or a `MockRestServiceServer`, so no test can reach WhatsApp.
- Migration test against a **disposable** PostgreSQL. It **drops the `public` schema**, so never point it at a real database:
  ```bash
  CRM_TEST_PG_URL=jdbc:postgresql://localhost:5432/crm_throwaway CRM_TEST_PG_USER=postgres CRM_TEST_PG_PASSWORD=... \
    ./mvnw test -Dtest=WhatsAppMigrationPostgresTest
  ```
- Frontend: `npx vitest run` in `crm-github/frontend`.

## 10. Staging rollout checklist (manual, needs approval)

1. Deploy WAHA (§2) privately with hashed API key, protected dashboard and HMAC key. Link a **dedicated test number**.
2. Set the CRM staging env vars (§3) and deploy. Confirm `GET /api/bulk-whatsapp/access` shows `ready: true`.
3. Send an unsigned POST to the webhook and confirm it returns 401. Confirm WAHA's `session.status` webhook is accepted: the backend logs `WhatsApp session status reported by provider: WORKING`.
4. Create one template. Record an opt-in, with evidence, on 1–2 staff-owned leads whose phones are team handsets that agreed to the test.
5. Send a batch to those 1–2 leads. Check Accepted, then Delivered/Read after **Check status**.
6. Reply `STOP` from one handset. Confirm the lead page shows *Opted out* and the next preview excludes it.
7. Stop the WAHA session and confirm the dialog shows the status and Send is disabled.
8. Only then consider production, with a separately approved number and a small first audience.

**Disable at any time:** set `WHATSAPP_ENABLED=false`, or remove `WAHA_BASE_URL`/`WAHA_API_KEY`, and redeploy.

## 11. Moving to the official Meta Cloud API later

The business logic only depends on `WhatsAppClient` (`sendText`, `sessionStatus`, `isReady`, ...). A migration needs:

- a new `WhatsAppClient` implementation for the Cloud API;
- a webhook variant: Meta signs with `X-Hub-Signature-256` and has a GET verification handshake;
- **template changes:** business-initiated Cloud API messages must use Meta-approved templates with positional variables. The CRM templates would have to map to approved ones, and free text is only allowed inside the 24-hour customer-service window.

Consent, batches, claims, de-duplication, audit and the UI stay as they are.
