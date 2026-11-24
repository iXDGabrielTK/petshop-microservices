/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "outbox.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

    private final OutboxProcessor outboxProcessor;

    public OutboxScheduler(OutboxProcessor outboxProcessor) {
        this.outboxProcessor = outboxProcessor;
    }

    @Scheduled(
            fixedDelayString = "${outbox.scheduler.fixed-delay-ms:2000}",
            initialDelayString = "${outbox.scheduler.initial-delay-ms:1000}")
    public void processOutbox() {
        OutboxStepResult result;
        do {
            try {
                // Processa uma mensagem por vez, em transações isoladas
                result = outboxProcessor.processNextStep();
            } catch (Exception e) {
                log.error("Erro inesperado no ciclo do OutboxScheduler: {}", e.getMessage(), e);
                result = OutboxStepResult.FAILED_PAUSE;
            }
        } while (result == OutboxStepResult.DELIVERED || result == OutboxStepResult.FAILED_CONTINUE);
    }
}
