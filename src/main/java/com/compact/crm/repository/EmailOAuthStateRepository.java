package com.compact.crm.repository;

import com.compact.crm.entity.EmailOAuthState;
import com.compact.crm.enums.MailboxProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface EmailOAuthStateRepository extends JpaRepository<EmailOAuthState, Long> {

    Optional<EmailOAuthState> findByStateHash(String stateHash);

    // Single use: returns 1 only for the one request that consumed the state.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("delete from EmailOAuthState s where s.id = :id")
    int consume(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("delete from EmailOAuthState s where s.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);

    // Starting a new connection supersedes any unfinished one for the same provider.
    @Modifying
    @Transactional
    @Query("delete from EmailOAuthState s where s.employeeId = :employeeId and s.provider = :provider")
    int deleteForEmployee(@Param("employeeId") Long employeeId, @Param("provider") MailboxProvider provider);
}
