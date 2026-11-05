package inv.finance.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import inv.finance.domain.model.TipoLancamento;
import inv.finance.infrastructure.persistence.LancamentoFinanceiroRepository;
import inv.shared.event.VendaEstornadaEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VendaEstornadaEventListenerTest {

    @Mock
    private LancamentoFinanceiroRepository lancamentoRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private RegistroPendenciaProjecao registroPendencia;

    private ObjectMapper objectMapper;

    private VendaEstornadaEventListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        listener = new VendaEstornadaEventListener(lancamentoRepository, eventPublisher, objectMapper, registroPendencia);
    }

    @Test
    @DisplayName("DEVE inserir lançamento a débito/estorno e publicar LancamentoRegistradoEvent quando referência for inédita")
    void deveInserirLancamentoEstornoEPublicarEventoQuandoReferenciaInedita() {
        Long vendaId = 15L;
        UUID uuidRef = UUID.randomUUID();
        BigDecimal valorEstorno = new BigDecimal("100.00");
        BigDecimal valorEstornoNegativo = new BigDecimal("-100.00");
        VendaEstornadaEvent event = new VendaEstornadaEvent(vendaId, uuidRef, valorEstorno);

        when(lancamentoRepository.existsByReferencia(uuidRef)).thenReturn(false);
        when(lancamentoRepository.inserirIdempotente(vendaId, uuidRef, TipoLancamento.ESTORNO.name(), valorEstornoNegativo))
                .thenReturn(Optional.of(99L));

        listener.handleVendaEstornada(event);

        verify(lancamentoRepository).existsByReferencia(uuidRef);
        verify(lancamentoRepository).inserirIdempotente(vendaId, uuidRef, TipoLancamento.ESTORNO.name(), valorEstornoNegativo);

        ArgumentCaptor<LancamentoRegistradoEvent> captor = ArgumentCaptor.forClass(LancamentoRegistradoEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        LancamentoRegistradoEvent publicado = captor.getValue();
        assertThat(publicado.lancamentoId()).isEqualTo(99L);
        assertThat(publicado.vendaId()).isEqualTo(vendaId);
        assertThat(publicado.valorAssinado()).isEqualByComparingTo(valorEstornoNegativo);
        assertThat(publicado.tipo()).isEqualTo(TipoLancamento.ESTORNO);

        // Pendência de projeção persistida na transação do ledger, antes da publicação do evento
        InOrder ordem = inOrder(registroPendencia, eventPublisher);
        ordem.verify(registroPendencia).registrarNaTransacaoDoLedger(publicado);
        ordem.verify(eventPublisher).publishEvent(publicado);
    }

    @Test
    @DisplayName("DEVE garantir valor assinado negativo mesmo quando valorEstorno no evento já for negativo")
    void deveGarantirValorAssinadoNegativoMesmoSeValorInformadoForNegativo() {
        Long vendaId = 20L;
        UUID uuidRef = UUID.randomUUID();
        BigDecimal valorNegativo = new BigDecimal("-50.25");
        BigDecimal valorEsperado = new BigDecimal("-50.25");
        VendaEstornadaEvent event = new VendaEstornadaEvent(vendaId, uuidRef, valorNegativo);

        when(lancamentoRepository.existsByReferencia(uuidRef)).thenReturn(false);
        when(lancamentoRepository.inserirIdempotente(vendaId, uuidRef, TipoLancamento.ESTORNO.name(), valorEsperado))
                .thenReturn(Optional.of(101L));

        listener.handleVendaEstornada(event);

        verify(lancamentoRepository).inserirIdempotente(vendaId, uuidRef, TipoLancamento.ESTORNO.name(), valorEsperado);

        ArgumentCaptor<LancamentoRegistradoEvent> captor = ArgumentCaptor.forClass(LancamentoRegistradoEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().valorAssinado()).isEqualByComparingTo(valorEsperado);
    }

    @Test
    @DisplayName("DEVE ignorar processamento (no-op idempotente) quando existsByReferencia retornar true")
    void deveIgnorarProcessamentoQuandoReferenciaJaExistirPreviamente() {
        Long vendaId = 15L;
        UUID uuidRef = UUID.randomUUID();
        BigDecimal valorEstorno = new BigDecimal("100.00");
        VendaEstornadaEvent event = new VendaEstornadaEvent(vendaId, uuidRef, valorEstorno);

        when(lancamentoRepository.existsByReferencia(uuidRef)).thenReturn(true);

        listener.handleVendaEstornada(event);

        verify(lancamentoRepository).existsByReferencia(uuidRef);
        verify(lancamentoRepository, never()).inserirIdempotente(any(), any(), any(), any());
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(registroPendencia);
    }

    @Test
    @DisplayName("DEVE não publicar evento quando inserção concorrente sofrer ON CONFLICT (retorno Optional vazio)")
    void deveNaoPublicarEventoQuandoInsercaoConcorrenteRetornarVazio() {
        Long vendaId = 15L;
        UUID uuidRef = UUID.randomUUID();
        BigDecimal valorEstorno = new BigDecimal("100.00");
        BigDecimal valorEstornoNegativo = new BigDecimal("-100.00");
        VendaEstornadaEvent event = new VendaEstornadaEvent(vendaId, uuidRef, valorEstorno);

        when(lancamentoRepository.existsByReferencia(uuidRef)).thenReturn(false);
        when(lancamentoRepository.inserirIdempotente(vendaId, uuidRef, TipoLancamento.ESTORNO.name(), valorEstornoNegativo))
                .thenReturn(Optional.empty());

        listener.handleVendaEstornada(event);

        verify(lancamentoRepository).existsByReferencia(uuidRef);
        verify(lancamentoRepository).inserirIdempotente(vendaId, uuidRef, TipoLancamento.ESTORNO.name(), valorEstornoNegativo);
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(registroPendencia);
    }

    @Test
    @DisplayName("DEVE desserializar payload JSON em String e processar estorno com sucesso")
    void deveDesserializarPayloadJsonEmStringEProcessarEstorno() throws Exception {
        Long vendaId = 30L;
        UUID uuidRef = UUID.randomUUID();
        BigDecimal valorEstorno = new BigDecimal("85.50");
        BigDecimal valorEstornoNegativo = new BigDecimal("-85.50");
        VendaEstornadaEvent event = new VendaEstornadaEvent(vendaId, uuidRef, valorEstorno);
        String json = objectMapper.writeValueAsString(event);

        when(lancamentoRepository.existsByReferencia(uuidRef)).thenReturn(false);
        when(lancamentoRepository.inserirIdempotente(vendaId, uuidRef, TipoLancamento.ESTORNO.name(), valorEstornoNegativo))
                .thenReturn(Optional.of(202L));

        listener.handleVendaEstornada(json);

        verify(lancamentoRepository).inserirIdempotente(vendaId, uuidRef, TipoLancamento.ESTORNO.name(), valorEstornoNegativo);
        verify(eventPublisher).publishEvent(any(LancamentoRegistradoEvent.class));
    }

    @Test
    @DisplayName("DEVE lançar AmqpRejectAndDontRequeueException quando payload JSON for malformado")
    void deveLancarAmqpRejectAndDontRequeueExceptionQuandoPayloadJsonForMalformado() {
        String jsonInvalido = "{vendaId: nao-fechou-aspas";

        assertThatThrownBy(() -> listener.handleVendaEstornada(jsonInvalido))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verifyNoInteractions(lancamentoRepository, eventPublisher, registroPendencia);
    }

    @Test
    @DisplayName("DEVE desserializar payload byte[] e processar estorno com sucesso")
    void deveDesserializarPayloadBytesEProcessarEstorno() throws Exception {
        Long vendaId = 40L;
        UUID uuidRef = UUID.randomUUID();
        BigDecimal valorEstorno = new BigDecimal("45.00");
        BigDecimal valorEstornoNegativo = new BigDecimal("-45.00");
        VendaEstornadaEvent event = new VendaEstornadaEvent(vendaId, uuidRef, valorEstorno);
        byte[] bytes = objectMapper.writeValueAsBytes(event);

        when(lancamentoRepository.existsByReferencia(uuidRef)).thenReturn(false);
        when(lancamentoRepository.inserirIdempotente(vendaId, uuidRef, TipoLancamento.ESTORNO.name(), valorEstornoNegativo))
                .thenReturn(Optional.of(303L));

        listener.handleVendaEstornada(bytes);

        verify(lancamentoRepository).inserirIdempotente(vendaId, uuidRef, TipoLancamento.ESTORNO.name(), valorEstornoNegativo);
        verify(eventPublisher).publishEvent(any(LancamentoRegistradoEvent.class));
    }

    @Test
    @DisplayName("DEVE lançar AmqpRejectAndDontRequeueException quando payload byte[] for inválido")
    void deveLancarAmqpRejectAndDontRequeueExceptionQuandoPayloadBytesForInvalido() {
        byte[] bytesInvalidos = "dados-corrompidos".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> listener.handleVendaEstornada(bytesInvalidos))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verifyNoInteractions(lancamentoRepository, eventPublisher, registroPendencia);
    }

    @Test
    @DisplayName("DEVE desserializar org.springframework.amqp.core.Message e processar estorno com sucesso")
    void deveDesserializarSpringAmqpMessageEProcessarEstorno() throws Exception {
        Long vendaId = 50L;
        UUID uuidRef = UUID.randomUUID();
        BigDecimal valorEstorno = new BigDecimal("75.00");
        BigDecimal valorEstornoNegativo = new BigDecimal("-75.00");
        VendaEstornadaEvent event = new VendaEstornadaEvent(vendaId, uuidRef, valorEstorno);
        byte[] bytes = objectMapper.writeValueAsBytes(event);
        Message amqpMessage = new Message(bytes);

        when(lancamentoRepository.existsByReferencia(uuidRef)).thenReturn(false);
        when(lancamentoRepository.inserirIdempotente(vendaId, uuidRef, TipoLancamento.ESTORNO.name(), valorEstornoNegativo))
                .thenReturn(Optional.of(404L));

        listener.handleVendaEstornada(amqpMessage);

        verify(lancamentoRepository).inserirIdempotente(vendaId, uuidRef, TipoLancamento.ESTORNO.name(), valorEstornoNegativo);
        verify(eventPublisher).publishEvent(any(LancamentoRegistradoEvent.class));
    }

    @Test
    @DisplayName("DEVE lançar AmqpRejectAndDontRequeueException quando org.springframework.amqp.core.Message contiver payload corrompido")
    void deveLancarAmqpRejectAndDontRequeueExceptionQuandoSpringAmqpMessageForInvalida() {
        Message amqpMessage = new Message("invalido".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> listener.handleVendaEstornada(amqpMessage))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verifyNoInteractions(lancamentoRepository, eventPublisher, registroPendencia);
    }

    @Test
    @DisplayName("DEVE ignorar com segurança (no-op) quando mensagem ou campos obrigatórios forem nulos")
    void deveIgnorarQuandoMensagemOuCamposObrigatoriosForemNulos() {
        listener.handleVendaEstornada(null);
        listener.handleVendaEstornada(new VendaEstornadaEvent(null, UUID.randomUUID(), new BigDecimal("10.00")));
        listener.handleVendaEstornada(new VendaEstornadaEvent(10L, null, new BigDecimal("10.00")));
        listener.handleVendaEstornada(new VendaEstornadaEvent(10L, UUID.randomUUID(), null));

        verifyNoInteractions(lancamentoRepository, eventPublisher, registroPendencia);
    }
}
