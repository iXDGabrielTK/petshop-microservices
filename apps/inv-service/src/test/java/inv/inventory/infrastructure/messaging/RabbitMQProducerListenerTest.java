/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import common.exception.BusinessException;
import inv.shared.config.DateTimeConfig;
import inv.shared.config.RabbitMQConfig;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RabbitMQProducerListenerTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-11-24T15:00:00Z");

    @Mock
    private OutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(FIXED_INSTANT, DateTimeConfig.ZONE_SAO_PAULO);

    @Test
    @DisplayName("RabbitMQProducerListener deve persistir Outbox com createdAt via Clock e payload JSON válido")
    void devePersistirOutboxComCreatedAtViaClockEPayloadValido() throws Exception {
        RabbitMQProducerListener listener = new RabbitMQProducerListener(outboxRepository, objectMapper, clock);
        EstoqueBaixoMessage payload = new EstoqueBaixoMessage(
                1, "evt-low-1", "Ração Premium", new BigDecimal("2.000"), new BigDecimal("5.000"));

        listener.onEstoqueBaixo(new EstoqueAtingiuMinimoEvent(payload));

        ArgumentCaptor<Outbox> outboxCaptor = ArgumentCaptor.forClass(Outbox.class);
        verify(outboxRepository).save(outboxCaptor.capture());

        Outbox salvo = outboxCaptor.getValue();
        assertThat(salvo.getExchange()).isEqualTo(RabbitMQConfig.EXCHANGE_NAME);
        assertThat(salvo.getRoutingKey()).isEqualTo(RabbitMQConfig.ROUTING_KEY_LOW_STOCK);
        assertThat(salvo.getEventType()).isEqualTo(EstoqueBaixoMessage.class.getName());
        assertThat(salvo.getVersion()).isEqualTo(1);
        assertThat(salvo.getCreatedAt()).isEqualTo(LocalDateTime.now(clock));
        assertThat(objectMapper.readValue(salvo.getPayload(), EstoqueBaixoMessage.class)).isEqualTo(payload);
    }

    @Test
    @DisplayName("RabbitMQProducerListener deve lançar BusinessException quando serialização JSON falhar")
    void deveLancarBusinessExceptionQuandoSerializacaoFalhar() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        RabbitMQProducerListener listener = new RabbitMQProducerListener(outboxRepository, failingMapper, clock);
        EstoqueBaixoMessage payload = new EstoqueBaixoMessage(
                1, "evt-low-err", "Ração", BigDecimal.ONE, BigDecimal.TEN);
        when(failingMapper.writeValueAsString(payload))
                .thenThrow(new JsonProcessingException("Erro simulado") {});

        assertThatThrownBy(() -> listener.onEstoqueBaixo(new EstoqueAtingiuMinimoEvent(payload)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Falha interna ao processar alerta de estoque.");

        verify(outboxRepository, never()).save(any());
    }
}
