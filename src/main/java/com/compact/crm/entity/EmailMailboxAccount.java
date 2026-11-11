package com.compact.crm.entity;

import com.compact.crm.enums.MailboxProvider;
import com.compact.crm.enums.MailboxStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

// An employee's own connected mailbox (Gmail or Microsoft 365) that the CRM
// sends their email through - see service.mail.MailboxSender. At most ONE per
// employee, and a mailbox address / provider account can belong to only one
// employee. Holds only what sending needs: the mailbox identity (address,
// provider account id, Microsoft tenant), the granted scopes, and the refresh
// token ENCRYPTED with AES-GCM (service.mail.TokenCipher). Access tokens are
// never persisted (kept in memory until they expire). No mailbox content is
// ever read or stored. Never serialized to the frontend - controllers return
// dto.response.MailboxStatusResponse instead.
@Entity
@Table(
        name = "email_mailbox_account",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_email_mailbox_account_employee", columnNames = "employee_id"),
                @UniqueConstraint(name = "uq_email_mailbox_account_subject", columnNames = {"provider", "provider_subject"}),
                @UniqueConstraint(name = "uq_email_mailbox_account_email", columnNames = "email_address")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailMailboxAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private MailboxProvider provider;

    // Normalized (trimmed, lower-case) sending address of the mailbox.
    @Column(name = "email_address", nullable = false, length = 254)
    private String emailAddress;

    // Google "sub" / Microsoft "oid": the stable account id. Every token
    // refresh that returns an ID token is checked against it, so a credential
    // can never silently start sending as a different account.
    @Column(name = "provider_subject", nullable = false, length = 255)
    private String providerSubject;

    // Microsoft "tid" (null for Google).
    @Column(name = "provider_tenant_id", length = 64)
    private String providerTenantId;

    @Column(name = "granted_scopes", length = 1000)
    private String grantedScopes;

    // "<key id>.<base64 iv+ciphertext+tag>" - see TokenCipher. Null once the
    // provider has rejected it (status NEEDS_REAUTH).
    @Column(name = "refresh_token_encrypted", columnDefinition = "TEXT")
    private String refreshTokenEncrypted;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MailboxStatus status;

    // Sanitized, user-facing reason for NEEDS_REAUTH (never a provider payload).
    @Column(name = "status_detail", length = 300)
    private String statusDetail;

    @Column(name = "connected_at", nullable = false)
    private LocalDateTime connectedAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = LocalDateTime.now();
        if (connectedAt == null) {
            connectedAt = updatedAt;
        }
    }
}
