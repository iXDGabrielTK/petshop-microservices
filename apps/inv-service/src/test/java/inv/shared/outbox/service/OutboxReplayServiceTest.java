/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox.service;

import common.exception.ConflictException;
import common.exception.ResourceNotFoundException;
import inv.shared.config.DateTimeConfig;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import inv.shared.outbox.web.OutboxRecordView;
import inv.shared.outbox.web.OutboxReplayResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxReplayService — Testes Unitários de Inspeção e Replay Condicional")
class OutboxReplayServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-11-24T16:30:00Z");

    @Mock
    private OutboxRepository outboxRepository;

    private Clock clock;
    private OutboxReplayService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_INSTANT, DateTimeConfig.ZONE_SAO_PAULO);
        service = new OutboxReplayService(outboxRepository, clock);
    }

    @Test
    @DisplayName("DEVE listar registros em quarentena preservando payload, versão e diagnóstico")
    void deveListarRegistrosEmQuarentena() {
        Outbox legado = criarQuarentena(7L, 0, "{\"vendaId\":1}");
        Pageable pageable = PageRequest.of(0, 20);
        when(outboxRepository.findByStatusOrderByQuarantinedAtDescIdDesc(Outbox.STATUS_QUARANTINED, pageable))
                .thenReturn(new PageImpl<>(List.of(legado), pageable, 1));

        Page<OutboxRecordView> page = service.listQuarantined(pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        OutboxRecordView view = page.getContent().getFirst();
        assertThat(view.id()).isEqualTo(7L);
        assertThat(view.version()).isZero();
        assertThat(view.payload()).isEqualTo("{\"vendaId\":1}");
        assertThat(view.status()).isEqualTo(Outbox.STATUS_QUARANTINED);
        assertThat(view.lastErrorType()).isEqualTo("NO_ROUTE");
    }

    @Test
    @DisplayName("DEVE retornar registro por ID ou lançar ResourceNotFoundException quando ausente")
    void deveBuscarPorIdOuLancar404() {
        Outbox registro = criarQuarentena(10L, 1, "{\"vendaId\":99}");
        when(outboxRepository.findById(10L)).thenReturn(Optional.of(registro));
        when(outboxRepository.findById(999L)).thenReturn(Optional.empty());

        OutboxRecordView view = service.findById(10L);
        assertThat(view.id()).isEqualTo(10L);
        assertThat(view.version()).isEqualTo(1);

        assertThatThrownBy(() -> service.findById(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("DEVE executar replay atômico retornando 200 equivalente quando QUARANTINED")
    void deveReativarRegistroEmQuarentena() {
        LocalDateTime agora = LocalDateTime.now(clock);
        Outbox atualizado = criarQuarentena(15L, 0, "{\"vendaId\":55}");
        atualizado.setStatus(Outbox.STATUS_PENDING);
        atualizado.setAttempts(0);
        atualizado.setReplayedAt(agora);
        atualizado.setReplayCount(1);

        when(outboxRepository.replayQuarantined(15L, agora)).thenReturn(1);
        when(outboxRepository.findById(15L)).thenReturn(Optional.of(atualizado));

        OutboxReplayResponse response = service.replay(15L);

        assertThat(response.id()).isEqualTo(15L);
        assertThat(response.status()).isEqualTo(Outbox.STATUS_PENDING);
        assertThat(response.replayedAt()).isEqualTo(agora);
        assertThat(response.replayCount()).isEqualTo(1);
        verify(outboxRepository).replayQuarantined(15L, agora);
    }

    @Test
    @DisplayName("DEVE lançar ConflictException quando registro existir mas não estiver QUARANTINED")
    void deveLancar409QuandoRegistroNaoEstiverEmQuarentena() {
        LocalDateTime agora = LocalDateTime.now(clock);
        when(outboxRepository.replayQuarantined(20L, agora)).thenReturn(0);
        when(outboxRepository.existsById(20L)).thenReturn(true);

        assertThatThrownBy(() -> service.replay(20L))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("DEVE lançar ResourceNotFoundException no replay quando ID não existir")
    void deveLancar404NoReplayQuandoIdInexistente() {
        LocalDateTime agora = LocalDateTime.now(clock);
        when(outboxRepository.replayQuarantined(404L, agora)).thenReturn(0);
        when(outboxRepository.existsById(404L)).thenReturn(false);

        assertThatThrownBy(() -> service.replay(404L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static Outbox criarQuarentena(Long id, int version, String payload) {
        Outbox outbox = new Outbox();
        outbox.setId(id);
        outbox.setExchange("pdv.exchange");
        outbox.setRoutingKey("venda.pagamento.confirmado");
        outbox.setEventType("inv.checkout.infrastructure.messaging.PagamentoConfirmadoEvent");
        outbox.setVersion(version);
        outbox.setPayload(payload);
        outbox.setStatus(Outbox.STATUS_QUARANTINED);
        outbox.setAttempts(5);
        outbox.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        outbox.setLastAttemptAt(LocalDateTime.now().minusMinutes(1));
        outbox.setLastErrorType("NO_ROUTE");
        outbox.setLastError("Mensagem sem rota");
        outbox.setQuarantinedAt(LocalDateTime.now().minusMinutes(1));
        return outbox;
    }
}
