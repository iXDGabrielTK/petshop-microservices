package inv.checkout.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import common.exception.BusinessException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import inv.checkout.domain.model.Pagamento;
import inv.checkout.domain.model.StatusPagamento;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.messaging.event.VendaAtualizadaEvent;
import inv.checkout.infrastructure.persistence.PagamentoRepository;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.inventory.api.ComandoEstoqueVenda;
import inv.inventory.api.EstoqueCommandService;
import inv.inventory.api.ItemComando;
import inv.shared.domain.MetodoPagamento;
import inv.shared.event.VendaPagaEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VendaEventHandlerTest {

    @Mock
    private VendaRepository vendaRepository;

    @Mock
    private PagamentoRepository pagamentoRepository;

    @Mock
    private EstoqueCommandService estoqueCommandService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private SimpleMeterRegistry meterRegistry;
    private VendaEventHandler handler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        handler = new VendaEventHandler(
                vendaRepository, pagamentoRepository, estoqueCommandService, new ObjectMapper(), eventPublisher, meterRegistry);
    }

    @Test
    @DisplayName("Condição 0 — DEVE lançar exceção quando pagamento não pertencer à venda informada")
    void condicao0_deveLancarExcecaoQuandoPagamentoNaoPertencerAVenda() {
        Long vendaId = 10L;
        Long outroVendaId = 99L;
        Long pagamentoId = 50L;

        Venda venda = criarVendaAguardandoPagamento(vendaId, 100L, "Produto Teste", new BigDecimal("50.00"), new BigDecimal("1.000"));
        Venda outraVenda = criarVendaAguardandoPagamento(outroVendaId, 100L, "Produto Teste", new BigDecimal("50.00"), new BigDecimal("1.000"));

        Pagamento pagamento = new Pagamento(outraVenda, MetodoPagamento.PIX, new BigDecimal("50.00"), UUID.randomUUID());
        ReflectionTestUtils.setField(pagamento, "id", pagamentoId);

        when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));
        when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

        String payload = """
                {"vendaId":10,"pagamentoId":50}
                """;

        assertThatThrownBy(() -> handler.handlePagamentoConfirmado(payload))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasCauseInstanceOf(BusinessException.class)
                .hasRootCauseMessage("Pagamento não pertence à venda informada.");

        verifyNoInteractions(estoqueCommandService, eventPublisher);
        verify(pagamentoRepository, never()).save(any());
        verify(vendaRepository, never()).save(any());
        assertThat(meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count()).isZero();
    }

    @Test
    @DisplayName("Cenário 3 (a) / Condição 1 — Reentrega idempotente: contabilizado=true deve retornar ACK sem efeitos colaterais")
    void cenario3a_reentregaIdempotenteContabilizadoDeveRetornarAckSemEfeitosColaterais() {
        Long vendaId = 10L;
        Long pagamentoId = 50L;

        Venda venda = criarVendaConcluidaComUmItem(vendaId, 100L, new BigDecimal("1.000"));
        Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("50.00"), UUID.randomUUID());
        ReflectionTestUtils.setField(pagamento, "id", pagamentoId);
        pagamento.marcarComoContabilizado();

        when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));
        when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

        String payload = """
                {"vendaId":10,"pagamentoId":50}
                """;

        handler.handlePagamentoConfirmado(payload);

        verifyNoInteractions(estoqueCommandService, eventPublisher);
        verify(pagamentoRepository, never()).save(any());
        verify(vendaRepository, never()).save(any());
        assertThat(meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count()).isZero();
    }

    @Test
    @DisplayName("Cenário 3 (a) / Condição 1 — Reentrega idempotente: excedente=true deve retornar ACK sem re-incrementar métrica")
    void cenario3a_reentregaIdempotenteExcedenteDeveRetornarAckSemIncrementarMetrica() {
        Long vendaId = 10L;
        Long pagamentoId = 50L;

        Venda venda = criarVendaConcluidaComUmItem(vendaId, 100L, new BigDecimal("1.000"));
        Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("50.00"), UUID.randomUUID());
        ReflectionTestUtils.setField(pagamento, "id", pagamentoId);
        pagamento.marcarComoExcedenteParaEstorno();

        when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));
        when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

        String payload = """
                {"vendaId":10,"pagamentoId":50}
                """;

        handler.handlePagamentoConfirmado(payload);

        verifyNoInteractions(estoqueCommandService, eventPublisher);
        verify(pagamentoRepository, never()).save(any());
        verify(vendaRepository, never()).save(any());
        assertThat(meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count()).isZero();
    }

    @Test
    @DisplayName("Cenário 3 (a) / Condição 1 — Reentrega idempotente: status=ESTORNADO deve retornar ACK sem efeitos colaterais")
    void cenario3a_reentregaIdempotenteEstornadoDeveRetornarAckSemEfeitosColaterais() {
        Long vendaId = 10L;
        Long pagamentoId = 50L;

        Venda venda = criarVendaConcluidaComUmItem(vendaId, 100L, new BigDecimal("1.000"));
        Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("50.00"), UUID.randomUUID());
        ReflectionTestUtils.setField(pagamento, "id", pagamentoId);
        ReflectionTestUtils.setField(pagamento, "status", StatusPagamento.ESTORNADO);

        when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));
        when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

        String payload = """
                {"vendaId":10,"pagamentoId":50}
                """;

        handler.handlePagamentoConfirmado(payload);

        verifyNoInteractions(estoqueCommandService, eventPublisher);
        verify(pagamentoRepository, never()).save(any());
        verify(vendaRepository, never()).save(any());
        assertThat(meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count()).isZero();
    }

    @Test
    @DisplayName("Cenário 3 (a) / Condição 1 — Pagamento status=ESTORNADO em venda AGUARDANDO_PAGAMENTO deve retornar ACK sem processar venda")
    void cenario3a_pagamentoEstornadoEmVendaAguardandoPagamentoDeveRetornarAckSemProcessarVenda() {
        Long vendaId = 10L;
        Long pagamentoId = 50L;

        Venda venda = criarVendaAguardandoPagamento(vendaId, 100L, "Produto Teste", new BigDecimal("50.00"), new BigDecimal("1.000"));
        Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("50.00"), UUID.randomUUID());
        ReflectionTestUtils.setField(pagamento, "id", pagamentoId);
        ReflectionTestUtils.setField(pagamento, "status", StatusPagamento.ESTORNADO);

        when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));
        when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

        String payload = """
                {"vendaId":10,"pagamentoId":50}
                """;

        handler.handlePagamentoConfirmado(payload);

        verifyNoInteractions(estoqueCommandService, eventPublisher);
        verify(pagamentoRepository, never()).save(any());
        verify(vendaRepository, never()).save(any());
        assertThat(venda.getStatus()).isEqualTo(StatusVenda.AGUARDANDO_PAGAMENTO);
        assertThat(venda.getValorPago()).isEqualByComparingTo("0.00");
        assertThat(meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count()).isZero();
    }

    @Test
    @DisplayName("Cenário 3 (b) / Condição 2 (Q7) — Pagamento tardio em venda CONCLUIDA: 1ª entrega marca excedente e incrementa métrica, 2ª entrega é no-op idempotente")
    void cenario3b_pagamentoTardioEmVendaConcluidaDeveMarcarExcedenteEIncrementarMetrica() {
        Long vendaId = 10L;
        Long pagamentoId = 50L;

        Venda venda = criarVendaConcluidaComUmItem(vendaId, 100L, new BigDecimal("1.000"));
        Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("50.00"), UUID.randomUUID());
        ReflectionTestUtils.setField(pagamento, "id", pagamentoId);

        when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));
        when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

        String payload = """
                {"vendaId":10,"pagamentoId":50}
                """;

        // 1ª entrega: pagamento inédito em venda CONCLUIDA -> Condição 2
        handler.handlePagamentoConfirmado(payload);

        assertThat(pagamento.isExcedente()).isTrue();
        verify(pagamentoRepository, times(1)).save(pagamento);
        assertThat(meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count()).isEqualTo(1.0);
        verifyNoInteractions(estoqueCommandService, eventPublisher);
        verify(vendaRepository, never()).save(any());

        // 2ª entrega (reentrega do mesmo pagamento agora marcado como excedente) -> Condição 1
        handler.handlePagamentoConfirmado(payload);

        // Pagamento não deve ser salvo novamente e métrica não deve ser re-incrementada
        verify(pagamentoRepository, times(1)).save(pagamento);
        assertThat(meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count()).isEqualTo(1.0);
        verifyNoInteractions(estoqueCommandService, eventPublisher);
        verify(vendaRepository, never()).save(any());
    }

    @Test
    @DisplayName("Cenário 3 (b) / Condição 2 (Q7) — Pagamento órfão em venda CANCELADA: marca excedente e incrementa métrica")
    void cenario3b_pagamentoOrfaoEmVendaCanceladaDeveMarcarExcedenteEIncrementarMetrica() {
        Long vendaId = 10L;
        Long pagamentoId = 50L;

        Venda venda = criarVendaCanceladaComUmItem(vendaId, 100L, new BigDecimal("1.000"));
        Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("50.00"), UUID.randomUUID());
        ReflectionTestUtils.setField(pagamento, "id", pagamentoId);

        when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));
        when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

        String payload = """
                {"vendaId":10,"pagamentoId":50}
                """;

        // 1ª entrega: pagamento inédito em venda CANCELADA -> Condição 2
        handler.handlePagamentoConfirmado(payload);

        assertThat(pagamento.isExcedente()).isTrue();
        verify(pagamentoRepository, times(1)).save(pagamento);
        assertThat(meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count()).isEqualTo(1.0);
        verifyNoInteractions(estoqueCommandService, eventPublisher);
        verify(vendaRepository, never()).save(any());

        // 2ª entrega (reentrega do mesmo pagamento agora marcado como excedente) -> Condição 1
        handler.handlePagamentoConfirmado(payload);

        // Pagamento não deve ser salvo novamente e métrica não deve ser re-incrementada
        verify(pagamentoRepository, times(1)).save(pagamento);
        assertThat(meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count()).isEqualTo(1.0);
        verifyNoInteractions(estoqueCommandService, eventPublisher);
        verify(vendaRepository, never()).save(any());
    }

    @Test
    @DisplayName("Condição 3 — DEVE concluir venda, baixar estoque definitivo e publicar VendaPagaEvent líquido quando pagamento quitar a venda")
    void condicao3_deveConcluirVendaEBaixarEstoqueQuandoPagamentoForIntegral() {
        Long vendaId = 10L;
        Long pagamentoId = 50L;
        UUID uuidRef = UUID.randomUUID();

        Venda venda = criarVendaAguardandoPagamento(
                vendaId, 100L, "Ração Super Premium", new BigDecimal("50.00"), new BigDecimal("2.000")); // total = 100.00

        Pagamento pagamento = new Pagamento(venda, MetodoPagamento.DINHEIRO, new BigDecimal("120.00"), uuidRef);
        ReflectionTestUtils.setField(pagamento, "id", pagamentoId);
        pagamento.confirmar(new BigDecimal("120.00"), "NSU-123");

        when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));
        when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

        String payload = """
                {"vendaId":10,"pagamentoId":50}
                """;

        handler.handlePagamentoConfirmado(payload);

        assertThat(venda.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);
        assertThat(venda.getValorPago()).isEqualByComparingTo("120.00");
        assertThat(venda.getValorTroco()).isEqualByComparingTo("20.00");
        assertThat(pagamento.isContabilizado()).isTrue();

        ArgumentCaptor<ComandoEstoqueVenda> comandoCaptor = ArgumentCaptor.forClass(ComandoEstoqueVenda.class);
        verify(estoqueCommandService, times(1)).confirmarBaixa(comandoCaptor.capture());
        assertThat(comandoCaptor.getValue()).isEqualTo(new ComandoEstoqueVenda(
                vendaId,
                List.of(new ItemComando(100L, new BigDecimal("2.000")))
        ));
        verify(vendaRepository, times(1)).save(venda);

        ArgumentCaptor<VendaPagaEvent> vendaPagaCaptor = ArgumentCaptor.forClass(VendaPagaEvent.class);
        verify(eventPublisher).publishEvent(vendaPagaCaptor.capture());
        VendaPagaEvent eventoPublicado = vendaPagaCaptor.getValue();
        assertThat(eventoPublicado.vendaId()).isEqualTo(vendaId);
        assertThat(eventoPublicado.uuidReferencia()).isEqualTo(uuidRef);
        assertThat(eventoPublicado.valorRecebido()).isEqualByComparingTo("100.00");

        verify(eventPublisher).publishEvent(any(VendaAtualizadaEvent.class));
        assertThat(meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count()).isZero();
    }

    @Test
    @DisplayName("Condição 3 — DEVE registrar pagamento parcial mantendo AGUARDANDO_PAGAMENTO sem baixar estoque")
    void condicao3_deveRegistrarPagamentoParcialSemConcluirVendaNemBaixarEstoque() {
        Long vendaId = 10L;
        Long pagamentoId = 50L;
        UUID uuidRef = UUID.randomUUID();

        Venda venda = criarVendaAguardandoPagamento(
                vendaId, 100L, "Ração Super Premium", new BigDecimal("50.00"), new BigDecimal("2.000")); // total = 100.00

        Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("40.00"), uuidRef);
        ReflectionTestUtils.setField(pagamento, "id", pagamentoId);
        pagamento.confirmar(new BigDecimal("40.00"), "NSU-PARCIAL");

        when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));
        when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

        String payload = """
                {"vendaId":10,"pagamentoId":50}
                """;

        handler.handlePagamentoConfirmado(payload);

        assertThat(venda.getStatus()).isEqualTo(StatusVenda.AGUARDANDO_PAGAMENTO);
        assertThat(venda.getValorPago()).isEqualByComparingTo("40.00");
        assertThat(venda.getValorTroco()).isEqualByComparingTo("0.00");
        assertThat(pagamento.isContabilizado()).isTrue();

        verifyNoInteractions(estoqueCommandService);
        verify(vendaRepository, times(1)).save(venda);
        verify(eventPublisher).publishEvent(new VendaPagaEvent(vendaId, uuidRef, new BigDecimal("40.00")));
        verify(eventPublisher).publishEvent(any(VendaAtualizadaEvent.class));
        assertThat(meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count()).isZero();
    }

    @Test
    @DisplayName("Condição 4 — DEVE lançar exceção quando venda estiver em status incompatível (ex: ABERTA)")
    void condicao4_deveLancarExcecaoQuandoVendaEmStatusInvalido() {
        Long vendaId = 10L;
        Long pagamentoId = 50L;

        Venda venda = new Venda();
        ReflectionTestUtils.setField(venda, "id", vendaId);
        venda.adicionarItem(100L, "Produto Teste", new BigDecimal("50.00"), new BigDecimal("1.000"));
        // status permanece ABERTA

        Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("50.00"), UUID.randomUUID());
        ReflectionTestUtils.setField(pagamento, "id", pagamentoId);

        when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));
        when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

        String payload = """
                {"vendaId":10,"pagamentoId":50}
                """;

        assertThatThrownBy(() -> handler.handlePagamentoConfirmado(payload))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasCauseInstanceOf(BusinessException.class)
                .hasRootCauseMessage("Venda em status inválido para confirmação de pagamento: ABERTA");

        verifyNoInteractions(estoqueCommandService, eventPublisher);
        verify(pagamentoRepository, never()).save(any());
        verify(vendaRepository, never()).save(any());
        assertThat(meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count()).isZero();
    }

    @Test
    @DisplayName("Condição 4 — DEVE lançar exceção quando venda estiver em CANCELAMENTO_SOLICITADO")
    void condicao4_deveLancarExcecaoQuandoVendaEmCancelamentoSolicitado() {
        Long vendaId = 10L;
        Long pagamentoId = 50L;

        Venda venda = criarVendaAguardandoPagamento(vendaId, 100L, "Produto Teste", new BigDecimal("50.00"), new BigDecimal("1.000"));
        ReflectionTestUtils.setField(venda, "status", StatusVenda.CANCELAMENTO_SOLICITADO);

        Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("50.00"), UUID.randomUUID());
        ReflectionTestUtils.setField(pagamento, "id", pagamentoId);

        when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));
        when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));

        String payload = """
                {"vendaId":10,"pagamentoId":50}
                """;

        assertThatThrownBy(() -> handler.handlePagamentoConfirmado(payload))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasCauseInstanceOf(BusinessException.class)
                .hasRootCauseMessage("Venda em status inválido para confirmação de pagamento: CANCELAMENTO_SOLICITADO");

        verifyNoInteractions(estoqueCommandService, eventPublisher);
        verify(pagamentoRepository, never()).save(any());
        verify(vendaRepository, never()).save(any());
        assertThat(meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count()).isZero();
    }

    @Test
    @DisplayName("DEVE lançar exceção quando venda não existir sem tocar no estoque")
    void deveFalharQuandoVendaNaoEncontrada() {
        when(vendaRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

        String payload = """
                {"vendaId":999,"pagamentoId":50}
                """;

        assertThatThrownBy(() -> handler.handlePagamentoConfirmado(payload))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasCauseInstanceOf(BusinessException.class)
                .hasRootCauseMessage("Venda não encontrada");

        verifyNoInteractions(pagamentoRepository, estoqueCommandService, eventPublisher);
        verify(vendaRepository, never()).save(any());
    }

    @Test
    @DisplayName("DEVE lançar exceção quando pagamento não existir")
    void deveFalharQuandoPagamentoNaoEncontrado() {
        Long vendaId = 10L;
        Venda venda = criarVendaAguardandoPagamento(vendaId, 100L, "Produto", new BigDecimal("50.00"), new BigDecimal("1.000"));

        when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));
        when(pagamentoRepository.findById(50L)).thenReturn(Optional.empty());

        String payload = """
                {"vendaId":10,"pagamentoId":50}
                """;

        assertThatThrownBy(() -> handler.handlePagamentoConfirmado(payload))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasCauseInstanceOf(BusinessException.class)
                .hasRootCauseMessage("Pagamento não encontrado");

        verifyNoInteractions(estoqueCommandService, eventPublisher);
        verify(vendaRepository, never()).save(any());
    }

    @Test
    @DisplayName("DLQ — DEVE lançar AmqpRejectAndDontRequeueException quando payload for JSON inválido")
    void deveLancarAmqpRejectAndDontRequeueExceptionQuandoPayloadForJsonInvalido() {
        String payloadInvalido = "{invalido: json}";

        assertThatThrownBy(() -> handler.handlePagamentoConfirmado(payloadInvalido))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasCauseInstanceOf(JsonProcessingException.class);

        verifyNoInteractions(vendaRepository, pagamentoRepository, estoqueCommandService, eventPublisher);
    }

    @Test
    @DisplayName("DLQ — DEVE lançar AmqpRejectAndDontRequeueException quando estoqueCommandService lançar BusinessException")
    void deveLancarAmqpRejectAndDontRequeueExceptionQuandoEstoqueLancarBusinessException() {
        Long vendaId = 10L;
        Long pagamentoId = 50L;

        Venda venda = criarVendaAguardandoPagamento(vendaId, 100L, "Produto", new BigDecimal("50.00"), new BigDecimal("1.000"));
        Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("50.00"), UUID.randomUUID());
        ReflectionTestUtils.setField(pagamento, "id", pagamentoId);
        pagamento.confirmar(new BigDecimal("50.00"), "NSU-100");

        when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));
        when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamento));
        doThrow(new BusinessException("Inconsistência de estoque"))
                .when(estoqueCommandService).confirmarBaixa(any());

        String payload = """
                {"vendaId":10,"pagamentoId":50}
                """;

        assertThatThrownBy(() -> handler.handlePagamentoConfirmado(payload))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasCauseInstanceOf(BusinessException.class)
                .hasRootCauseMessage("Inconsistência de estoque");

        verify(eventPublisher, never()).publishEvent(any(VendaPagaEvent.class));
        verify(vendaRepository, never()).save(any());
    }

    @Test
    @DisplayName("Retry — DEVE relançar RuntimeException genérica para falhas transitórias de infraestrutura")
    void deveRelancarRuntimeExceptionQuandoFalhaTransitoria() {
        when(vendaRepository.findByIdWithLock(10L)).thenThrow(new IllegalStateException("Conexão com PostgreSQL recusada"));

        String payload = """
                {"vendaId":10,"pagamentoId":50}
                """;

        assertThatThrownBy(() -> handler.handlePagamentoConfirmado(payload))
                .isExactlyInstanceOf(RuntimeException.class)
                .isNotInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Conexão com PostgreSQL recusada");

        verifyNoInteractions(pagamentoRepository, estoqueCommandService, eventPublisher);
    }

    private static Venda criarVendaAguardandoPagamento(
            Long vendaId, Long produtoId, String nomeProduto, BigDecimal precoVenda, BigDecimal quantidade) {
        Venda venda = new Venda();
        ReflectionTestUtils.setField(venda, "id", vendaId);
        venda.adicionarItem(produtoId, nomeProduto, precoVenda, quantidade);
        venda.irParaPagamento();
        return venda;
    }

    private static Venda criarVendaConcluidaComUmItem(Long vendaId, Long produtoId, BigDecimal quantidade) {
        Venda venda = criarVendaAguardandoPagamento(vendaId, produtoId, "Produto Teste", new BigDecimal("50.00"), quantidade);
        Pagamento pagamento = new Pagamento(venda, MetodoPagamento.DINHEIRO, venda.getValorTotal(), UUID.randomUUID());
        pagamento.confirmar(venda.getValorTotal(), "NSU-INIT");
        venda.registrarPagamentoConfirmado(pagamento);
        venda.concluir();
        return venda;
    }

    private static Venda criarVendaCanceladaComUmItem(Long vendaId, Long produtoId, BigDecimal quantidade) {
        Venda venda = criarVendaAguardandoPagamento(vendaId, produtoId, "Produto Teste", new BigDecimal("50.00"), quantidade);
        venda.cancelar();
        return venda;
    }
}
