# Email provider migration: Brevo → employee-owned Gmail / Microsoft 365 mailboxes

Every CRM email is now sent **from the signed-in employee's own mailbox**:

- **Gmail / Google Workspace** through the Gmail API (`users.messages.send`, scope `gmail.send`);
- **Outlook / Microsoft 365** through Microsoft Graph (`/me/sendMail`, delegated `Mail.Send`).

Each employee connects their mailbox once (OAuth authorization code + PKCE). There is no shared sender, and there is **no fallback** to Brevo, to a shared account or to another employee's mailbox. If the employee has no usable mailbox, the CRM refuses to send and tells them to connect one.

This covers all three send paths: individual **Send Email** on a lead, **Keep in Touch** bulk, and **Bulk Email** for leads and opportunities.

> **Status (2026-11-11).** The code changes below are complete and covered by automated tests that **mock** Google and Microsoft. Nothing has been sent through a real Gmail or Microsoft 365 mailbox yet. The provider setup in sections 3–5 is **manual** and has not been done. Live delivery is **not verified**.

---

## 1. What changed (completed code changes)

### Architecture

```
React (Email Account page, Bulk Email modal - unchanged API contract)
   │  JWT
   ▼
EmailAccountController ── MailboxConnectionService ── GoogleOAuthClient / MicrosoftOAuthClient
                                   │                         (code + PKCE exchange, ID-token checks, refresh, revoke)
                                   ▼
                        email_mailbox_account (refresh token AES-GCM encrypted by TokenCipher)
                                   ▲
BulkEmailService ─┐                │
LeadEmailService ─┴──► MailboxSender ──► EmailProvider
                       (resolve caller's mailbox,      ├─ GmailEmailProvider     (Gmail API)
                        refresh token, pace, classify) └─ MicrosoftEmailProvider (Microsoft Graph)
```

- `MailboxSender` is the only way email leaves the CRM. It resolves the **authenticated** employee's mailbox, refreshes the access token when needed (access tokens are cached in memory only, never stored), paces sends per mailbox, sends from the connected address, and classifies the outcome as `ACCEPTED` / `FAILED` (definitely not sent) / `UNKNOWN` (may have been sent; never resent automatically).
- `BulkEmailService` keeps everything it did before: server-issued batches, recipient validation and authorization, placeholder rendering, the do-not-email list, the 24-hour de-duplication window, per-recipient claims, partial-failure reporting and the reload-safe results view. Only the dispatch step changed. In addition:
  - a batch **pins the sender mailbox** when it is created; later chunks must go out from that same mailbox (`409 MAILBOX_MISMATCH` otherwise);
  - when the mailbox is throttled, its credential is refused or it is blocked, the chunk **stops at that recipient**. Recorded outcomes are kept, untouched recipients stay `NOT_ATTEMPTED`, and the request answers `429` (+ `Retry-After`) or `409` so the browser pauses and reconciles;
  - a **per-mailbox daily cap** (below the provider's own limit) is enforced from the send log;
  - the claim-and-recheck step is now serialized per address. This closes a race in which two concurrent batches could both email the same address: the tests exposed it as an intermittent failure in `BulkEmailConcurrencyTest`.
- `LeadEmailService` (individual send, Keep in Touch) sends through `MailboxSender` too. Attachments are supported: Gmail up to 35 MB per message; Microsoft up to about 3 MB of attachments in total (Graph's 4 MB request limit). A larger Microsoft message is refused without being sent.
- **Brevo removed:** `BrevoEmailClient` and its test are deleted; `brevo.api-key`, `crm.mail.from-address`, `crm.mail.from-name`, `crm.mail.reply-to-address` and the Brevo startup diagnostic are gone from `application.properties`. No Maven dependency was involved (Brevo was called over plain HTTP). Existing send history is untouched.
- **No new dependencies.** Both providers are called over HTTPS with Spring's `RestClient`, as the previous Brevo client was. The Google/Microsoft Java SDKs and Spring Security's `oauth2Login` were not used. The CRM authenticates users with its own stateless JWT, and the OAuth flow here *connects a mailbox to an already-signed-in employee*; it is not a login. The session-based authorized-client machinery doesn't fit that, and the hand-written flow is small and fully tested.

### Files

Backend (`crm_backend-main`):

| File | Change |
|---|---|
| `service/mail/EmailProvider.java`, `GmailEmailProvider.java`, `MicrosoftEmailProvider.java` | provider abstraction + Gmail API / Graph implementations |
| `service/mail/MailboxOAuthClient.java`, `GoogleOAuthClient.java`, `MicrosoftOAuthClient.java`, `OAuthSupport.java`, `OAuthTokens.java`, `OAuthException.java` | OAuth code + PKCE flow, ID-token validation, refresh, revoke |
| `service/mail/MailboxSender.java`, `MailboxRateLimiter.java`, `SenderIdentity.java`, `OutgoingEmail.java`, `EmailSendOutcome.java`, `EmailOutcomeUnknownException.java`, `MimeMessageBuilder.java` | mailbox resolution, token refresh, pacing, MIME building |
| `service/mail/MailboxConnectionService.java`, `controller/EmailAccountController.java`, `dto/response/MailboxStatusResponse.java` | connect / callback / status / disconnect |
| `service/mail/TokenCipher.java`, `service/mail/MailboxProperties.java` | AES-256-GCM encryption, configuration |
| `entity/EmailMailboxAccount.java`, `entity/EmailOAuthState.java`, `repository/EmailMailboxAccountRepository.java`, `repository/EmailOAuthStateRepository.java`, `enums/MailboxProvider.java`, `enums/MailboxStatus.java` | storage |
| `db/migration/V14_1__employee_mailbox_accounts_and_oauth_state.sql` | new tables + sender columns (additive) |
| `service/BulkEmailService.java`, `service/LeadEmailService.java`, `repository/EmailSendLogRepository.java`, `entity/EmailSendLog.java`, `entity/EmailBulkBatch.java` | dispatch through `MailboxSender`, sender pinning, daily cap, per-address lock |
| `exception/MailboxUnavailableException.java`, `exception/TooManyRequestsException.java`, `exception/GlobalExceptionHandler.java` | `409` for mailbox problems, `429` + `Retry-After` |
| `config/SecurityConfig.java` | OAuth callback reachable without JWT; `Retry-After` exposed via CORS |
| `enums/ActivityAction.java` | `MAILBOX_CONNECTED`, `MAILBOX_DISCONNECTED` (activity log) |
| `service/EmailAttachment.java` | now public (used by the providers) |
| `application.properties`, `.env.example`, `README.md`, test `application.properties` | configuration (placeholders only) |
| deleted: `service/BrevoEmailClient.java`, `test/.../BrevoEmailClientTest.java` | |
| tests: `service/mail/*Test.java`, `controller/EmailAccountApiTest.java`, `repository/MailboxMigrationPostgresTest.java`, new cases in `BulkEmailServiceIntegrationTest`; the existing email tests now mock `MailboxSender` instead of Brevo | |

Frontend (`crm-github/frontend`):

| File | Change |
|---|---|
| `src/pages/EmailAccount.jsx`, `src/services/emailAccountService.js` | new **Email Account** page: connect Google / Microsoft, status, reconnect, disconnect |
| `src/App.jsx`, `src/components/Sidebar.jsx` | `/email-account` route and menu entry (all signed-in users) |
| `src/services/bulkEmailService.js` | a chunk answered `429` with a short `Retry-After` is waited out and re-sent (safe: the server never sends a recipient twice), at most 6 times; longer waits pause as before |
| tests: `src/pages/__tests__/EmailAccount.test.jsx`, `src/components/email/__tests__/BulkEmailThrottle.test.js` | |

The Bulk Email screen itself is unchanged. "No mailbox connected" and "reconnect" arrive as ordinary `409` errors whose message the modal already displays.

### HTTP API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/api/email-accounts/me` | JWT | the caller's own mailbox: provider, address, status, which providers the server supports. Never a token. |
| `POST` | `/api/email-accounts/connect/{google\|microsoft}` | JWT + `EMAIL_SEND` | starts OAuth; returns `{ authorizationUrl }` |
| `GET` | `/api/email-accounts/oauth/{provider}/callback` | none (browser redirect from the provider) | validates state, exchanges the code, stores the mailbox, redirects to `EMAIL_OAUTH_RETURN_URL?result=…` |
| `DELETE` | `/api/email-accounts/me` | JWT | disconnect (revokes at Google; deletes the CRM's copy) |

No endpoint takes an employee id. Every call acts on the caller only.

---

## 2. Security model

- **OAuth**: authorization code flow, confidential web client (secret stays on the server), **PKCE S256**, **OpenID nonce**, and a **single-use, 10-minute `state`**. Only the state's SHA-256 is stored, together with the employee who started the flow, so the unauthenticated callback attaches the mailbox to *that* employee only. A replayed, forged, expired or cross-provider state is rejected before any token exchange.
- **ID token** (from the provider's token endpoint over TLS): issuer, audience, expiry and nonce are checked. Google requires a verified email. For Microsoft, the tenant is restricted per `MICROSOFT_TENANT_ID` and the mailbox address comes from Graph `/me`.
- **Identity binding**: by default (`EMAIL_REQUIRE_EMPLOYEE_EMAIL_MATCH=true`) a mailbox can be connected only if its address equals the employee's CRM login email. A mailbox can belong to only one employee (unique constraints), and an employee has at most one mailbox. Every token refresh that returns an ID token must still be the *same* provider account; otherwise the mailbox is disabled. Graph sends set `from` to the connected address, so a mismatched token is refused instead of sending as someone else.
  - *Why the email-match rule matters:* any OAuth start link can be forwarded. Without the rule, an employee could send their consent link to a colleague, and if the colleague approved it, the colleague's mailbox would be connected to the first employee's CRM account. Turn the rule off only if employees' CRM logins differ from their mailboxes, and accept that risk.
- **Storage**: refresh tokens are AES-256-GCM encrypted with `EMAIL_TOKEN_ENCRYPTION_KEY`, bound to the employee and provider (associated data), and wiped when the provider rejects them. Access tokens are memory-only. No mailbox content is read or stored: the scopes only allow sending.
- **Never exposed or logged**: tokens, client secrets, authorization headers and request bodies are never returned to React or written to logs. Logs carry only HTTP status codes and short OAuth error codes.
- **Least privilege**: Google `openid email gmail.send`; Microsoft `openid profile offline_access User.Read Mail.Send` (`profile` makes Microsoft include the user's object id, `oid`, in the ID token). `User.Read` is needed to read the signed-in user's own mailbox address from Graph `/me`, because the ID token's username is not always the SMTP address.

---

## 3. Google setup (manual)

1. **Google Cloud project**: in <https://console.cloud.google.com/> create or select a project for the CRM.
2. **Enable the Gmail API**: *APIs & Services → Library → Gmail API → Enable*.
3. **OAuth consent screen** (*Google Auth Platform*):
   - **Audience / user type**:
     - **Internal**: only if every employee uses a Google **Workspace** account in *your* organisation. No Google verification is needed, and refresh tokens don't expire because of the publishing status.
     - **External**: required if anyone uses a personal `@gmail.com` account or another organisation's Workspace.
   - **Data access**: add the scopes `openid`, `.../auth/userinfo.email` and `https://www.googleapis.com/auth/gmail.send`.
   - **Branding**: app name, support email, and the authorised domain of the backend and frontend.
4. **Verification (External only)**: `gmail.send` is a **sensitive** scope.
   - While the app is in **Testing**, only listed **test users** (max 100) can connect, they see an "unverified app" warning, and their **refresh tokens expire after 7 days** (they'd have to reconnect weekly).
   - For production use with External users, publish the app and complete Google's **brand and sensitive-scope verification**. This needs a privacy policy, a homepage on a verified domain, a justification and a demo video, and can take days to weeks. `gmail.send` is not a *restricted* scope, so no third-party security assessment should be required; confirm with Google's current policy.
5. **OAuth client**: *Clients → Create client → Web application*.
   - **Authorised redirect URI** (exact match): `https://<backend-host>/api/email-accounts/oauth/google/callback`
   - Copy the **Client ID** and **Client secret** into Render (section 5). For local development, also add `http://localhost:8080/api/email-accounts/oauth/google/callback`.
6. **Workspace admins**: if the Workspace has *API controls* restricting third-party apps, an admin must mark the CRM's OAuth client as trusted (*Admin console → Security → API controls → App access control*).

Account eligibility: the Google account must have Gmail enabled. A Workspace user whose Gmail service is turned off cannot send.

---

## 4. Microsoft setup (manual)

**Production values, derived from the code and the live deployment (2026-11-11):**

| Setting | Value | Where it comes from |
|---|---|---|
| Redirect URI (Entra *and* `MICROSOFT_REDIRECT_URI`) | `https://crm-backend-bfwr.onrender.com/api/email-accounts/oauth/microsoft/callback` | `EmailAccountController` (`/api/email-accounts` + `/oauth/{provider}/callback`, provider `microsoft`); no servlet context path is configured. Backend host: frontend commit `c6d3756`, "Configure frontend for production backend", and the host answers this app's `/health` (`{"status":"ok"}`). |
| `EMAIL_OAUTH_RETURN_URL` | `https://crm-frontend-q5nk.onrender.com/email-account` | the deployed frontend (also the CORS default); its static host serves deep links such as `/email-account` |

Confirm in Render that the frontend's `VITE_API_URL` is `https://crm-backend-bfwr.onrender.com/api`. If a custom domain is ever put in front of the backend, the redirect URI changes with it, in both places. The value must match **exactly** (scheme, host, path, no trailing slash); otherwise Microsoft stops at its own error page with **AADSTS50011** and never returns to the CRM.

1. **App registration**: <https://entra.microsoft.com/> → *Identity → Applications → App registrations → New registration*.
   - **Supported account types**. This choice and `MICROSOFT_TENANT_ID` must agree; a mismatch fails at Microsoft with **AADSTS50194** (single-tenant app used through `common`/`organizations`) or **AADSTS50020** (account from another tenant).
     - *Accounts in this organizational directory only*: **recommended** when all employees are in your Microsoft 365 tenant. Set `MICROSOFT_TENANT_ID` to the **Directory (tenant) ID** shown on the app's *Overview* page. The code's default, `common`, does **not** work with this choice, so the variable must be set.
     - *Any organizational directory*: set `MICROSOFT_TENANT_ID=organizations`.
     - *Any organizational directory and personal Microsoft accounts*: needed only for Outlook.com/Hotmail users. Set `MICROSOFT_TENANT_ID=common`.
   - **Redirect URI**: platform **Web** (not "Single-page application"; the token exchange uses the client secret on the server). Value: the redirect URI above. For local development, also add `http://localhost:8080/api/email-accounts/oauth/microsoft/callback`; Microsoft allows plain `http` only for localhost.
2. **Client secret**: *Certificates & secrets → New client secret*. Copy the **Value** (shown once; not the *Secret ID*) straight into Render as `MICROSOFT_CLIENT_SECRET`. Never paste it into a file, chat or ticket. Secrets expire (max 24 months): diary the expiry. When it lapses every connection and refresh fails ("sign-in settings are not valid", Microsoft error **AADSTS7000222**/**AADSTS7000215**) until a new secret is set.
3. **API permissions**: *Add a permission → Microsoft Graph → Delegated permissions*: `Mail.Send` and `User.Read` (usually already listed), plus, under *OpenId permissions*, `openid`, `profile` and `offline_access`. Do **not** add application permissions: the CRM only ever acts as the signed-in user.
   - `profile` is required. Without it Microsoft leaves the user's object id (`oid`) out of the ID token and the CRM refuses the connection ("identity_invalid"). Fixed 2026-11-11: the CRM now requests `profile` at sign-in.
4. **Consent**: don't assume you can grant admin consent. What employees see depends on the tenant's *Enterprise applications → Consent and permissions → User consent settings*, which only an administrator can view or change:
   - *Allow user consent for apps*: each employee approves `Mail.Send` for themselves on first connect. No admin is needed.
   - *Allow user consent for apps from verified publishers, for selected permissions*: apps registered **in your own tenant** qualify, but only for permissions an admin has classified as *low impact*. `Mail.Send` usually isn't, so employees will see **"Need admin approval"**.
   - *Do not allow user consent*: every app needs an administrator.
   - When approval is needed, an administrator (Global Administrator, Privileged Role Administrator, Cloud Application Administrator or Application Administrator) opens the app under *Enterprise applications → Permissions* and clicks **Grant admin consent for <organisation>**. This grants only the delegated permissions above, and each employee still connects their own mailbox. If the tenant has the **admin consent workflow** enabled, employees can request approval from the "Need admin approval" screen instead.
   - In the CRM, a declined or blocked consent shows "No permission was granted… your organisation may require an administrator to approve the CRM app" (`access_denied` / `consent_required`).
   - Conditional Access (MFA, compliant device) still applies at sign-in.
5. **Eligibility**: each user needs an **Exchange Online mailbox** (a licensed Microsoft 365 user). Accounts without a mailbox are refused at connect time. With the default `EMAIL_REQUIRE_EMPLOYEE_EMAIL_MATCH=true`, the mailbox's primary SMTP address (Graph `mail`) must equal the employee's CRM login email, ignoring case. An employee whose CRM login is an alias or a different address is refused ("not your CRM login address"); correct the CRM login rather than turning the rule off. Exchange admins can block sending per mailbox, or the tenant can be restricted for spam. The CRM reports that as "mailbox cannot send" and does not retry.
6. **Refresh tokens**: Microsoft refresh tokens are rotated on every use (the CRM stores the newest, encrypted) and lapse after a period without use (90 days by default). An employee who hasn't sent for that long, or whose password, consent or Conditional Access state changes, sees "Needs reconnecting" on the Email Account page.

---

## 5. Render environment variables

Set on the **backend** service. Values marked **secret** must only ever live in Render (or another secret manager), never in the repository.

| Variable | Example / notes |
|---|---|
| `EMAIL_TOKEN_ENCRYPTION_KEY` | **secret**: 32 random bytes, base64. Generate with `openssl rand -base64 32`. Losing or changing it forces every employee to reconnect. |
| `EMAIL_TOKEN_ENCRYPTION_KEY_PREVIOUS` | only while rotating keys (see below) |
| `EMAIL_OAUTH_RETURN_URL` | `https://<frontend-host>/email-account` |
| `GOOGLE_CLIENT_ID` | from section 3.5 |
| `GOOGLE_CLIENT_SECRET` | **secret** |
| `GOOGLE_REDIRECT_URI` | `https://<backend-host>/api/email-accounts/oauth/google/callback` |
| `MICROSOFT_CLIENT_ID` | Application (client) ID |
| `MICROSOFT_CLIENT_SECRET` | **secret** (the Value) |
| `MICROSOFT_TENANT_ID` | tenant GUID (recommended; required for a single-tenant registration), `organizations` or `common`. Must match the registration's *Supported account types*. The code default is `common`. |
| `MICROSOFT_REDIRECT_URI` | `https://crm-backend-bfwr.onrender.com/api/email-accounts/oauth/microsoft/callback`: exactly the value registered in Entra (see section 4) |
| `EMAIL_REQUIRE_EMPLOYEE_EMAIL_MATCH` | optional, default `true` (see section 2) |
| `EMAIL_GOOGLE_DAILY_LIMIT` / `EMAIL_MICROSOFT_DAILY_LIMIT` | optional, defaults `400` / `2000` bulk emails per mailbox per 24 h |
| `EMAIL_GOOGLE_PER_MINUTE` / `EMAIL_MICROSOFT_PER_MINUTE` | optional, defaults `60` / `30` |

A provider whose three variables are not all set is shown as unavailable on the Email Account page. Without `EMAIL_TOKEN_ENCRYPTION_KEY`, connecting and sending answer `503` and the rest of the CRM runs normally.

**No longer read (remove after a successful rollout, see section 9):** `BREVO_API_KEY`, `MAIL_FROM_ADDRESS`, `MAIL_FROM_NAME`, `MAIL_REPLY_TO_ADDRESS`. No other variable was removed or renamed.

**Key rotation:** set `EMAIL_TOKEN_ENCRYPTION_KEY_PREVIOUS` to the old key and `EMAIL_TOKEN_ENCRYPTION_KEY` to the new one, then deploy. Tokens are re-encrypted with the new key at their next refresh. Once every active mailbox has been used, remove the previous key; any mailbox not refreshed by then has to reconnect.

---

## 6. Database migration

`V14_1__employee_mailbox_accounts_and_oauth_state.sql` runs automatically through Flyway at startup, like V2–V14 (it is numbered 14.1 so it sorts after V14 and before any later V15+). It is **purely additive** and safe to re-run:

- creates `email_mailbox_account` (FK to `employees`, `ON DELETE CASCADE`: a credential never outlives its employee) and `email_oauth_state`;
- adds nullable `sender_provider` / `sender_email` to `email_send_log` and `email_bulk_batch`, plus an index for the daily cap.

No existing row is changed or deleted. Rows from the Brevo period keep `NULL` sender columns. A Brevo-era batch with unsent recipients is pinned to the employee's connected mailbox on its next chunk.

`MailboxMigrationPostgresTest` checks V14.1 against real PostgreSQL. It is skipped unless `CRM_TEST_PG_URL` points at a **disposable** database, because it drops the schema. It was **not** run for this change.

---

## 7. Employees: connecting and disconnecting

1. Sign in to the CRM → **Email Account** (side menu).
2. Click **Connect Google** or **Connect Microsoft** and sign in with **your own work mailbox** (the same address you use for the CRM).
3. Approve "send email on your behalf". You land back on Email Account with your address shown as **Connected**.
4. Send email as usual. Customers see your address, and replies go to your inbox. Sent messages appear in your mailbox's Sent folder.
5. **Disconnect** on the same page deletes the CRM's access. For Google it is also revoked at Google. For Microsoft, the CRM's copy is deleted; to remove the app's consent as well, use <https://myapps.microsoft.com> (work) or <https://account.live.com/consent/Manage> (personal).
6. If the page says **Needs reconnecting** (password changed, consent revoked, token expired), click **Reconnect**. Nothing is sent until you do.

Common connection errors are shown on the page, for example "That mailbox is not your CRM login address", "Permission to send email was not granted", or "already connected to another CRM user".

---

## 8. Sending quotas and provider restrictions

The CRM paces each mailbox **below** the providers' published limits, and stops when a provider says to stop. It never rotates mailboxes, splits recipients or retries in a loop to get past a limit.

| | Gmail (consumer) | Google Workspace | Exchange Online / Microsoft 365 |
|---|---|---|---|
| Daily | about 500 recipients / day | about 2,000 messages / day (lower for trial accounts) | 10,000 recipients / day |
| Rate | per-user API rate limits | per-user API rate limits | 30 messages / minute per mailbox |
| CRM default cap | 400 bulk emails / 24 h, ≤ 60 / min | (same: raise `EMAIL_GOOGLE_DAILY_LIMIT` up to, never above, your plan's limit) | 2,000 / 24 h, ≤ 30 / min |

Figures are from Google's and Microsoft's documentation at the time of writing; check the current pages before changing the defaults:
<https://support.google.com/a/answer/166852>, <https://developers.google.com/gmail/api/reference/quota>, <https://learn.microsoft.com/office365/servicedescriptions/exchange-online-service-description/exchange-online-limits>, <https://learn.microsoft.com/graph/throttling-limits>.

Behaviour:

- **Short throttling** (CRM pacing or the provider's `429`): the request answers `429` + `Retry-After`. The browser waits and re-sends the same chunk; recipients already handled are never sent twice.
- **Daily limit** (CRM cap, Gmail `dailyLimitExceeded`, Exchange `ErrorExceededMessageLimit`): sending pauses with a message. Continue the batch later.
- Bulk email remains subject to anti-spam policy. Mail sent from personal mailboxes is scrutinised by Google and Microsoft, and complaints can get a mailbox suspended. The do-not-email list and the 24-hour de-duplication still apply.

**Duplicate prevention**: neither Gmail nor Graph offers exactly-once delivery or an idempotency key. The CRM claims each recipient in `email_send_log` before calling the provider. A timeout or `5xx` is recorded as `UNKNOWN` and **never resent**, and confirmed `FAILED` recipients (`4xx`) are the only ones that can be retried. Each message also carries a stable `Message-ID` / `X-CRM-Send-Id` per batch recipient, so it can be traced in the Sent folder.

---

## 9. Deployment and rollback

**Before deploying**

1. Complete sections 3 and/or 4 (at least one provider).
2. Set the section 5 variables on Render. **Leave the Brevo variables in place for now**, so a rollback works without reconfiguration.
3. Take a database backup (Render → PostgreSQL → Backups).

**Deploy**

4. Deploy the backend. Check the log: Flyway reports V14.1 applied, and there is no "Mailbox email is DISABLED" warning.
5. Deploy the frontend.
6. As one employee: connect a mailbox, send one individual email to an **internal** test address, then a two-recipient Bulk Email to internal addresses. Confirm receipt, the sender address and the Sent folder copy. *This live check has not been done yet.*
7. Ask employees to connect their mailboxes. Until they do, their sends are refused with "Connect your Gmail or Microsoft 365 mailbox first".

**After a successful rollout**

8. Remove `BREVO_API_KEY`, `MAIL_FROM_ADDRESS`, `MAIL_FROM_NAME` and `MAIL_REPLY_TO_ADDRESS` from Render, and cancel the Brevo plan if it is not used elsewhere.

**Rollback**

- Redeploy the previous backend and frontend commits. V14.1 is additive, so the previous code ignores the new tables and columns. With the Brevo variables still set (step 2), email goes back through Brevo immediately.
- Do **not** delete the V14.1 tables during a rollback; rolling forward again then needs no reconnection. If they must go (for example, to purge stored credentials), run while the old version is live:
  ```sql
  DROP TABLE IF EXISTS email_oauth_state;
  DROP TABLE IF EXISTS email_mailbox_account;
  DELETE FROM flyway_schema_history WHERE version = '18';
  ```
  The nullable sender columns can stay.
- Emergency stop without a rollback: unset `EMAIL_TOKEN_ENCRYPTION_KEY`. All sending is refused with `503` and nothing else in the CRM is affected. To cut the CRM off at the provider, delete the client secret in Google Cloud or Entra.

---

## 10. Known limitations and open items

- Live sending through real Google / Microsoft accounts is **untested** (manual setup pending).
- Pacing and the address lock are **in-process**. They're correct for the single Render instance the CRM runs on. With several instances, the provider's own `429` and the per-recipient claims remain the safeguards, but cross-instance pacing would need a shared store.
- Microsoft attachments are limited to about 3 MB in total (a Graph upload session would be needed for more).
- The daily cap counts bulk email only. Individual "Send Email" messages are not in the send log; the provider's own limit still applies.
- There is no admin view of other employees' mailbox connections. A deactivated employee cannot sign in or send, and deleting the employee deletes the credential.
