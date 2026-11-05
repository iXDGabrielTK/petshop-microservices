package inv.checkout.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import common.exception.BusinessException;
import inv.shared.config.RabbitMQConfig;
import inv.shared.event.VendaEstornadaEvent;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VendaEstornadaOutboxListener — Testes Unitários de Persistência no Outbox")
class VendaEstornadaOutboxListenerTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private VendaEstornadaOutboxListener listener;

    @Test
    @DisplayName("DEVE persistir registro no Outbox com sucesso com exchange e routing key corretos")
    void devePersistirRegistroNoOutboxComSucesso() throws Exception {
        Long vendaId = 42L;
        UUID uuidRef = UUID.randomUUID();
        BigDecimal valorEstorno = new BigDecimal("85.50");
        VendaEstornadaEvent event = new VendaEstornadaEvent(vendaId, uuidRef, valorEstorno);
        String jsonPayload = "{\"vendaId\":42,\"uuidReferenciaEstorno\":\"" + uuidRef + "\",\"valorEstorno\":85.50}";

        when(objectMapper.writeValueAsString(event)).thenReturn(jsonPayload);

        listener.onVendaEstornada(event);

        ArgumentCaptor<Outbox> captor = ArgumentCaptor.forClass(Outbox.class);
        verify(outboxRepository).save(captor.capture());

        Outbox outbox = captor.getValue();
        assertThat(outbox.getExchange()).isEqualTo(RabbitMQConfig.PDV_EXCHANGE);
        assertThat(outbox.getRoutingKey()).isEqualTo(RabbitMQConfig.ROUTING_KEY_VENDA_ESTORNADA);
        assertThat(outbox.getEventType()).isEqualTo(VendaEstornadaEvent.class.getName());
        assertThat(outbox.getPayload()).isEqualTo(jsonPayload);
        assertThat(outbox.getVersion()).isEqualTo(1);
        assertThat(outbox.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("DEVE lançar BusinessException e não salvar Outbox quando falhar a serialização JSON")
    void deveLancarBusinessExceptionQuandoFalharSerializacaoJson() throws Exception {
        Long vendaId = 42L;
        UUID uuidRef = UUID.randomUUID();
        BigDecimal valorEstorno = new BigDecimal("85.50");
        VendaEstornadaEvent event = new VendaEstornadaEvent(vendaId, uuidRef, valorEstorno);

        when(objectMapper.writeValueAsString(event))
                .thenThrow(new JsonProcessingException("Erro simulado de serialização") {});

        assertThatThrownBy(() -> listener.onVendaEstornada(event))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Falha interna ao processar registro de estorno no Outbox.");

        verify(outboxRepository, never()).save(any());
    }

    @Test
    @DisplayName("DEVE lançar BusinessException quando falhar a persistência no banco de dados")
    void deveLancarBusinessExceptionQuandoFalharPersistenciaNoBanco() throws Exception {
        Long vendaId = 42L;
        UUID uuidRef = UUID.randomUUID();
        BigDecimal valorEstorno = new BigDecimal("85.50");
        VendaEstornadaEvent event = new VendaEstornadaEvent(vendaId, uuidRef, valorEstorno);

        when(objectMapper.writeValueAsString(event)).thenReturn("{}");
        when(outboxRepository.save(any(Outbox.class))).thenThrow(new RuntimeException("DB Connection timeout"));

        assertThatThrownBy(() -> listener.onVendaEstornada(event))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Falha interna ao processar registro de estorno no Outbox.");
    }

    @Test
    @DisplayName("DEVE lançar BusinessException quando vendaId for nulo")
    void deveLancarBusinessExceptionQuandoVendaIdForNulo() {
        VendaEstornadaEvent event = new VendaEstornadaEvent(null, UUID.randomUUID(), new BigDecimal("50.00"));

        assertThatThrownBy(() -> listener.onVendaEstornada(event))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Falha interna: evento de estorno possui campos obrigatórios nulos ou valor inválido.");

        verifyNoInteractions(outboxRepository, objectMapper);
    }

    @Test
    @DisplayName("DEVE lançar BusinessException quando uuidReferenciaEstorno for nulo")
    void deveLancarBusinessExceptionQuandoUuidReferenciaEstornoForNulo() {
        VendaEstornadaEvent event = new VendaEstornadaEvent(10L, null, new BigDecimal("50.00"));

        assertThatThrownBy(() -> listener.onVendaEstornada(event))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Falha interna: evento de estorno possui campos obrigatórios nulos ou valor inválido.");

        verifyNoInteractions(outboxRepository, objectMapper);
    }

    @Test
    @DisplayName("DEVE lançar BusinessException quando valorEstorno for nulo")
    void deveLancarBusinessExceptionQuandoValorEstornoForNulo() {
        VendaEstornadaEvent event = new VendaEstornadaEvent(10L, UUID.randomUUID(), null);

        assertThatThrownBy(() -> listener.onVendaEstornada(event))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Falha interna: evento de estorno possui campos obrigatórios nulos ou valor inválido.");

        verifyNoInteractions(outboxRepository, objectMapper);
    }

    @Test
    @DisplayName("DEVE lançar BusinessException quando valorEstorno for negativo")
    void deveLancarBusinessExceptionQuandoValorEstornoForNegativo() {
        VendaEstornadaEvent event = new VendaEstornadaEvent(10L, UUID.randomUUID(), new BigDecimal("-10.00"));

        assertThatThrownBy(() -> listener.onVendaEstornada(event))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Falha interna: evento de estorno possui campos obrigatórios nulos ou valor inválido.");

        verifyNoInteractions(outboxRepository, objectMapper);
    }

    @Test
    @DisplayName("DEVE serializar e permitir desserialização idempotente de VendaEstornadaEvent com ObjectMapper real (OutboxProcessor)")
    void deveSerializarEPermitirDesserializacaoComObjectMapperReal() throws Exception {
        ObjectMapper realObjectMapper = new ObjectMapper().findAndRegisterModules();
        VendaEstornadaOutboxListener realListener = new VendaEstornadaOutboxListener(outboxRepository, realObjectMapper);

        Long vendaId = 42L;
        UUID uuidRef = UUID.nameUUIDFromBytes(("ESTORNO:" + vendaId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        BigDecimal valorEstorno = new BigDecimal("85.50");
        VendaEstornadaEvent event = new VendaEstornadaEvent(vendaId, uuidRef, valorEstorno);

        realListener.onVendaEstornada(event);

        ArgumentCaptor<Outbox> captor = ArgumentCaptor.forClass(Outbox.class);
        verify(outboxRepository).save(captor.capture());

        Outbox outbox = captor.getValue();
        assertThat(outbox.getExchange()).isEqualTo(RabbitMQConfig.PDV_EXCHANGE);
        assertThat(outbox.getRoutingKey()).isEqualTo(RabbitMQConfig.ROUTING_KEY_VENDA_ESTORNADA);
        assertThat(outbox.getEventType()).isEqualTo(VendaEstornadaEvent.class.getName());
        assertThat(outbox.getVersion()).isEqualTo(1);
        assertThat(outbox.getCreatedAt()).isNotNull();

        // Simula a exata desserialização do OutboxProcessor
        Class<?> clazz = Class.forName(outbox.getEventType());
        Object deserialized = realObjectMapper.readValue(outbox.getPayload(), clazz);

        assertThat(deserialized).isInstanceOf(VendaEstornadaEvent.class);
        VendaEstornadaEvent roundTripEvent = (VendaEstornadaEvent) deserialized;
        assertThat(roundTripEvent.vendaId()).isEqualTo(vendaId);
        assertThat(roundTripEvent.uuidReferenciaEstorno()).isEqualTo(uuidRef);
        assertThat(roundTripEvent.valorEstorno()).isEqualByComparingTo(valorEstorno);
    }

    @Test
    @DisplayName("DEVE ignorar processamento (no-op) com segurança quando evento for nulo")
    void deveIgnorarComSegurancaQuandoEventoForNulo() {
        listener.onVendaEstornada(null);

        verifyNoInteractions(outboxRepository, objectMapper);
    }
}
