package com.compact.crm.repository;

import com.compact.crm.entity.EmailMailboxAccount;
import com.compact.crm.enums.MailboxProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailMailboxAccountRepository extends JpaRepository<EmailMailboxAccount, Long> {

    Optional<EmailMailboxAccount> findByEmployeeId(Long employeeId);

    Optional<EmailMailboxAccount> findByEmailAddress(String emailAddress);

    Optional<EmailMailboxAccount> findByProviderAndProviderSubject(MailboxProvider provider, String providerSubject);
}
