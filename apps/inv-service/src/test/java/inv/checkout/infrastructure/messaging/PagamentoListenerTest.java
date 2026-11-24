/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.checkout.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import inv.checkout.infrastructure.messaging.event.PagamentoConfirmadoEvent;
import inv.shared.config.RabbitMQConfig;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PagamentoListenerTest {

    @Mock
    private OutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Outbox nova deve inicializar version = 1 por padrão")
    void outboxNovaDeveInicializarVersion1PorPadrao() {
        assertThat(new Outbox().getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("PagamentoListener deve gravar Outbox com version = 1, exchange PDV e payload JSON válido")
    void deveGravarOutboxComVersion1EPayloadJsonValido() throws Exception {
        PagamentoListener listener = new PagamentoListener(outboxRepository, objectMapper);
        PagamentoConfirmadoEvent event = new PagamentoConfirmadoEvent(10L, 20L);

        listener.onPagamentoConfirmado(event);

        ArgumentCaptor<Outbox> outboxCaptor = ArgumentCaptor.forClass(Outbox.class);
        verify(outboxRepository).save(outboxCaptor.capture());

        Outbox salvo = outboxCaptor.getValue();
        assertThat(salvo.getExchange()).isEqualTo(RabbitMQConfig.PDV_EXCHANGE);
        assertThat(salvo.getRoutingKey()).isEqualTo(RabbitMQConfig.PDV_ROUTING_KEY);
        assertThat(salvo.getEventType()).isEqualTo(PagamentoConfirmadoEvent.class.getName());
        assertThat(salvo.getVersion()).isEqualTo(1);
        assertThat(salvo.getCreatedAt()).isNotNull();
        assertThat(objectMapper.readValue(salvo.getPayload(), PagamentoConfirmadoEvent.class)).isEqualTo(event);
    }
}
