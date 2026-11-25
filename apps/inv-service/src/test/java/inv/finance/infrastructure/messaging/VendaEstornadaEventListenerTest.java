/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.finance.infrastructure.messaging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import inv.finance.domain.model.TipoLancamento;
import inv.finance.infrastructure.persistence.LancamentoFinanceiroRepository;
import inv.shared.event.VendaEstornadaEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void limitaPayloadNoLogDeErro(boolean cortaParSurrogado) {
        Logger logger = (Logger) LoggerFactory.getLogger(VendaEstornadaEventListener.class);
        ListAppender<ILoggingEvent> eventos = new ListAppender<>();
        eventos.start();
        logger.addAppender(eventos);
        try {
            String prefixo = "inicio\r\nlinha";
            int limite = cortaParSurrogado ? 255 : 256;
            String trecho = prefixo + "x".repeat(limite - prefixo.length());
            String payload = trecho + "🐾fim-do-payload";
            Message message = new Message(payload.getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> listener.handleVendaEstornada(message))
                    .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                    .hasNoCause()
                    .hasMessageNotContaining("inicio");
            assertThat(eventos.list).singleElement().satisfies(evento -> {
                assertThat(evento.getFormattedMessage()).contains("inicio  linha")
                        .doesNotContain("\r", "\n", "fim-do-payload");
                assertThat(evento.getArgumentArray()[1]).isEqualTo(trecho.replace('\r', ' ').replace('\n', ' '));
                assertThat(evento.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(eventos);
            eventos.stop();
        }
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        listener = new VendaEstornadaEventListener(lancamentoRepository, eventPublisher, objectMapper, registroPendencia);
    }

    @Test
    @DisplayName("DEVE inserir lançamento a débito/estorno e publicar LancamentoRegistradoEvent quando referência for inédita")
    void deveInserirLancamentoEstornoEPublicarEventoQuandoReferenciaInedita() throws Exception {
        Long vendaId = 15L;
        UUID uuidRef = UUID.randomUUID();
        BigDecimal valorEstorno = new BigDecimal("100.00");
        BigDecimal valorEstornoNegativo = new BigDecimal("-100.00");
        VendaEstornadaEvent event = new VendaEstornadaEvent(vendaId, uuidRef, valorEstorno);

        when(lancamentoRepository.existsByReferencia(uuidRef)).thenReturn(false);
        when(lancamentoRepository.inserirIdempotente(vendaId, uuidRef, TipoLancamento.ESTORNO.name(), valorEstornoNegativo))
                .thenReturn(Optional.of(99L));

        listener.handleVendaEstornada(msg(event));

        verify(lancamentoRepository).existsByReferencia(uuidRef);
        verify(lancamentoRepository).inserirIdempotente(vendaId, uuidRef, TipoLancamento.ESTORNO.name(), valorEstornoNegativo);

        ArgumentCaptor<LancamentoRegistradoEvent> captor = ArgumentCaptor.forClass(LancamentoRegistradoEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        LancamentoRegistradoEvent publicado = captor.getValue();
        assertThat(publicado.lancamentoId()).isEqualTo(99L);
        assertThat(publicado.vendaId()).isEqualTo(vendaId);
        assertThat(publicado.valorAssinado()).isEqualByComparingTo(valorEstornoNegativo);
        assertThat(publicado.tipo()).isEqualTo(TipoLancamento.ESTORNO);

        InOrder ordem = inOrder(registroPendencia, eventPublisher);
        ordem.verify(registroPendencia).registrarNaTransacaoDoLedger(publicado);
        ordem.verify(eventPublisher).publishEvent(publicado);
    }

    @Test
    @DisplayName("DEVE garantir valor assinado negativo mesmo quando valorEstorno no evento já for negativo")
    void deveGarantirValorAssinadoNegativoMesmoSeValorInformadoForNegativo() throws Exception {
        Long vendaId = 20L;
        UUID uuidRef = UUID.randomUUID();
        BigDecimal valorNegativo = new BigDecimal("-50.25");
        BigDecimal valorEsperado = new BigDecimal("-50.25");
        VendaEstornadaEvent event = new VendaEstornadaEvent(vendaId, uuidRef, valorNegativo);

        when(lancamentoRepository.existsByReferencia(uuidRef)).thenReturn(false);
        when(lancamentoRepository.inserirIdempotente(vendaId, uuidRef, TipoLancamento.ESTORNO.name(), valorEsperado))
                .thenReturn(Optional.of(101L));

        listener.handleVendaEstornada(msg(event));

        verify(lancamentoRepository).inserirIdempotente(vendaId, uuidRef, TipoLancamento.ESTORNO.name(), valorEsperado);

        ArgumentCaptor<LancamentoRegistradoEvent> captor = ArgumentCaptor.forClass(LancamentoRegistradoEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().valorAssinado()).isEqualByComparingTo(valorEsperado);
    }

    @Test
    @DisplayName("DEVE ignorar processamento (no-op idempotente) quando existsByReferencia retornar true")
    void deveIgnorarProcessamentoQuandoReferenciaJaExistirPreviamente() throws Exception {
        Long vendaId = 15L;
        UUID uuidRef = UUID.randomUUID();
        BigDecimal valorEstorno = new BigDecimal("100.00");
        VendaEstornadaEvent event = new VendaEstornadaEvent(vendaId, uuidRef, valorEstorno);

        when(lancamentoRepository.existsByReferencia(uuidRef)).thenReturn(true);

        listener.handleVendaEstornada(msg(event));

        verify(lancamentoRepository).existsByReferencia(uuidRef);
        verify(lancamentoRepository, never()).inserirIdempotente(any(), any(), any(), any());
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(registroPendencia);
    }

    @Test
    @DisplayName("DEVE não publicar evento quando inserção concorrente sofrer ON CONFLICT (retorno Optional vazio)")
    void deveNaoPublicarEventoQuandoInsercaoConcorrenteRetornarVazio() throws Exception {
        Long vendaId = 15L;
        UUID uuidRef = UUID.randomUUID();
        BigDecimal valorEstorno = new BigDecimal("100.00");
        BigDecimal valorEstornoNegativo = new BigDecimal("-100.00");
        VendaEstornadaEvent event = new VendaEstornadaEvent(vendaId, uuidRef, valorEstorno);

        when(lancamentoRepository.existsByReferencia(uuidRef)).thenReturn(false);
        when(lancamentoRepository.inserirIdempotente(vendaId, uuidRef, TipoLancamento.ESTORNO.name(), valorEstornoNegativo))
                .thenReturn(Optional.empty());

        listener.handleVendaEstornada(msg(event));

        verify(lancamentoRepository).existsByReferencia(uuidRef);
        verify(lancamentoRepository).inserirIdempotente(vendaId, uuidRef, TipoLancamento.ESTORNO.name(), valorEstornoNegativo);
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(registroPendencia);
    }

    @Test
    @DisplayName("DEVE desserializar org.springframework.amqp.core.Message ignorando __TypeId__ arbitrário")
    void deveDesserializarSpringAmqpMessageIgnorandoTypeId() throws Exception {
        Long vendaId = 50L;
        UUID uuidRef = UUID.randomUUID();
        BigDecimal valorEstorno = new BigDecimal("75.00");
        BigDecimal valorEstornoNegativo = new BigDecimal("-75.00");
        VendaEstornadaEvent event = new VendaEstornadaEvent(vendaId, uuidRef, valorEstorno);
        byte[] bytes = objectMapper.writeValueAsBytes(event);
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader("__TypeId__", "java.lang.ProcessBuilder");
        Message amqpMessage = new Message(bytes, props);

        when(lancamentoRepository.existsByReferencia(uuidRef)).thenReturn(false);
        when(lancamentoRepository.inserirIdempotente(vendaId, uuidRef, TipoLancamento.ESTORNO.name(), valorEstornoNegativo))
                .thenReturn(Optional.of(404L));

        listener.handleVendaEstornada(amqpMessage);

        verify(lancamentoRepository).inserirIdempotente(vendaId, uuidRef, TipoLancamento.ESTORNO.name(), valorEstornoNegativo);
        verify(eventPublisher).publishEvent(any(LancamentoRegistradoEvent.class));
    }

    @Test
    @DisplayName("DEVE lançar AmqpRejectAndDontRequeueException quando org.springframework.amqp.core.Message contiver payload corrompido ou vazio")
    void deveLancarAmqpRejectAndDontRequeueExceptionQuandoSpringAmqpMessageForInvalida() {
        Message amqpMessage = new Message("invalido".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> listener.handleVendaEstornada(amqpMessage))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        Message emptyMessage = new Message(new byte[0]);
        assertThatThrownBy(() -> listener.handleVendaEstornada(emptyMessage))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verifyNoInteractions(lancamentoRepository, eventPublisher, registroPendencia);
    }

    @Test
    @DisplayName("DEVE ignorar com segurança (no-op) quando mensagem ou campos obrigatórios forem nulos")
    void deveIgnorarQuandoMensagemOuCamposObrigatoriosForemNulos() throws Exception {
        listener.handleVendaEstornada(null);
        listener.handleVendaEstornada(msg(new VendaEstornadaEvent(null, UUID.randomUUID(), new BigDecimal("10.00"))));
        listener.handleVendaEstornada(msg(new VendaEstornadaEvent(10L, null, new BigDecimal("10.00"))));
        listener.handleVendaEstornada(msg(new VendaEstornadaEvent(10L, UUID.randomUUID(), null)));

        verifyNoInteractions(lancamentoRepository, eventPublisher, registroPendencia);
    }

    private Message msg(VendaEstornadaEvent event) throws Exception {
        return new Message(objectMapper.writeValueAsBytes(event));
    }
}
