/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox.service;

import common.exception.ConflictException;
import common.exception.ResourceNotFoundException;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import inv.shared.outbox.web.OutboxRecordView;
import inv.shared.outbox.web.OutboxReplayResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class OutboxReplayService {

    private final OutboxRepository outboxRepository;
    private final Clock clock;

    public OutboxReplayService(OutboxRepository outboxRepository, Clock clock) {
        this.outboxRepository = outboxRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<OutboxRecordView> listQuarantined(Pageable pageable) {
        return outboxRepository
                .findByStatusOrderByQuarantinedAtDescIdDesc(Outbox.STATUS_QUARANTINED, pageable)
                .map(OutboxRecordView::from);
    }

    @Transactional(readOnly = true)
    public OutboxRecordView findById(Long id) {
        return outboxRepository.findById(id)
                .map(OutboxRecordView::from)
                .orElseThrow(() -> new ResourceNotFoundException("Registro da outbox não encontrado: " + id));
    }

    @Transactional
    public OutboxReplayResponse replay(Long id) {
        LocalDateTime agora = LocalDateTime.now(clock);
        int updated = outboxRepository.replayQuarantined(id, agora);
        if (updated == 1) {
            Outbox replayed = outboxRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Registro da outbox não encontrado: " + id));
            return new OutboxReplayResponse(
                    replayed.getId(),
                    replayed.getStatus(),
                    replayed.getReplayedAt(),
                    replayed.getReplayCount());
        }
        if (outboxRepository.existsById(id)) {
            throw new ConflictException(
                    "Registro da outbox ID " + id + " não está em quarentena e não pode ser reprocessado.");
        }
        throw new ResourceNotFoundException("Registro da outbox não encontrado: " + id);
    }
}
