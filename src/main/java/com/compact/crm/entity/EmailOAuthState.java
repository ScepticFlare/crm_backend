package com.compact.crm.entity;

import com.compact.crm.enums.MailboxProvider;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

// One pending "connect my mailbox" authorization (see
// service.mail.MailboxConnectionService). The OAuth `state` sent to the
// provider is a random 256-bit value; only its SHA-256 is stored, together with
// the employee who started the flow, the provider, the PKCE code verifier
// (encrypted) and the OpenID nonce. Single use: the row is deleted as soon as a
// callback presents it, and it expires after a few minutes. The callback is
// unauthenticated (it is a browser redirect from Google/Microsoft), so this row
// is what ties the resulting mailbox to the right CRM employee.
@Entity
@Table(
        name = "email_oauth_state",
        uniqueConstraints = @UniqueConstraint(name = "uq_email_oauth_state_hash", columnNames = "state_hash"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailOAuthState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "state_hash", nullable = false, length = 64)
    private String stateHash;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private MailboxProvider provider;

    @Column(name = "code_verifier_encrypted", nullable = false, length = 500)
    private String codeVerifierEncrypted;

    @Column(name = "nonce", nullable = false, length = 100)
    private String nonce;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
