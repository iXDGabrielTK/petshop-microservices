/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox.repository;

import inv.shared.outbox.model.Outbox;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface OutboxRepository extends JpaRepository<Outbox, Long> {

    // Busca 1 item elegível ordenado pelo mais antigo e bloqueia ignorando os já travados
    @Query(value = """
        SELECT * FROM outbox
         WHERE status = 'PENDING'
           AND (next_attempt_at IS NULL OR next_attempt_at <= CAST(:agora AS TIMESTAMP))
         ORDER BY created_at ASC, id ASC
         LIMIT 1
         FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<Outbox> findNextMessageToProcess(@Param("agora") LocalDateTime agora);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE outbox
           SET status = :status,
               attempts = :attempts,
               next_attempt_at = CAST(:nextAttemptAt AS TIMESTAMP),
               last_attempt_at = CAST(:agora AS TIMESTAMP),
               last_error_type = CAST(:errorType AS VARCHAR),
               last_error = CAST(:errorMsg AS TEXT),
               quarantined_at = COALESCE(quarantined_at, CAST(:quarantinedAt AS TIMESTAMP))
         WHERE id = :id AND status = 'PENDING'
        """, nativeQuery = true)
    int recordFailure(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("attempts") int attempts,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("agora") LocalDateTime agora,
            @Param("errorType") String errorType,
            @Param("errorMsg") String errorMsg,
            @Param("quarantinedAt") LocalDateTime quarantinedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE outbox
           SET status = 'PENDING',
               next_attempt_at = CAST(:agora AS TIMESTAMP),
               attempts = 0,
               replayed_at = CAST(:agora AS TIMESTAMP),
               replay_count = replay_count + 1
         WHERE id = :id AND status = 'QUARANTINED'
        """, nativeQuery = true)
    int replayQuarantined(@Param("id") Long id, @Param("agora") LocalDateTime agora);

    Page<Outbox> findByStatusOrderByQuarantinedAtDescIdDesc(String status, Pageable pageable);
}
