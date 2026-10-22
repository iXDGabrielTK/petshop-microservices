package inv.checkout.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import common.exception.BusinessException;
import inv.checkout.domain.model.Pagamento;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.messaging.event.VendaAtualizadaEvent;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.inventory.api.ComandoEstoqueVenda;
import inv.inventory.api.EstoqueCommandService;
import inv.inventory.api.ItemComando;
import inv.shared.event.VendaPagaEvent;
import jakarta.persistence.EntityManager;
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
    private EntityManager entityManager;

    @Mock
    private EstoqueCommandService estoqueCommandService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private VendaEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new VendaEventHandler(
                vendaRepository, entityManager, estoqueCommandService, new ObjectMapper(), eventPublisher);
    }

    @Test
    @DisplayName("Q7 — Venda já CONCLUIDA deve lançar exceção (causa BusinessException) sem tocar no estoque")
    void vendaJaConcluidaDeveLancarExcecaoSemTocarNoEstoque() {
        Venda venda = criarVendaConcluidaComUmItem(10L, 100L, new BigDecimal("2.000"));
        Pagamento pagamentoProxy = mock(Pagamento.class);
        when(vendaRepository.findById(10L)).thenReturn(Optional.of(venda));
        when(entityManager.getReference(Pagamento.class, 50L)).thenReturn(pagamentoProxy);

        String payload = """
                {"vendaId":10,"pagamentoId":50}
                """;

        assertThatThrownBy(() -> handler.handlePagamentoConfirmado(payload))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(BusinessException.class);
        verifyNoInteractions(estoqueCommandService);
        verify(vendaRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("DEVE concluir venda, baixar estoque definitivo e publicar VendaPagaEvent líquido quando pagamento quitar a venda")
    void deveConcluirVendaEBaixarEstoqueQuandoPagamentoForIntegral() {
        Long vendaId = 10L;
        Long pagamentoId = 50L;
        UUID uuidRef = UUID.randomUUID();

        Venda venda = criarVendaAguardandoPagamento(
                vendaId, 100L, "Ração Super Premium", new BigDecimal("50.00"), new BigDecimal("2.000")); // total = 100.00

        Pagamento pagamentoProxy = mock(Pagamento.class);
        when(pagamentoProxy.getValorRecebido()).thenReturn(new BigDecimal("120.00")); // troco = 20.00, líquido = 100.00
        when(pagamentoProxy.getUuidReferencia()).thenReturn(uuidRef);

        when(vendaRepository.findById(vendaId)).thenReturn(Optional.of(venda));
        when(entityManager.getReference(Pagamento.class, pagamentoId)).thenReturn(pagamentoProxy);

        String payload = """
                {"vendaId":10,"pagamentoId":50}
                """;

        handler.handlePagamentoConfirmado(payload);

        assertThat(venda.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);
        assertThat(venda.getValorPago()).isEqualByComparingTo("120.00");
        assertThat(venda.getValorTroco()).isEqualByComparingTo("20.00");

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
    }

    @Test
    @DisplayName("DEVE registrar pagamento parcial mantendo AGUARDANDO_PAGAMENTO sem baixar estoque")
    void deveRegistrarPagamentoParcialSemConcluirVendaNemBaixarEstoque() {
        Long vendaId = 10L;
        Long pagamentoId = 50L;
        UUID uuidRef = UUID.randomUUID();

        Venda venda = criarVendaAguardandoPagamento(
                vendaId, 100L, "Ração Super Premium", new BigDecimal("50.00"), new BigDecimal("2.000")); // total = 100.00

        Pagamento pagamentoProxy = mock(Pagamento.class);
        when(pagamentoProxy.getValorRecebido()).thenReturn(new BigDecimal("40.00"));
        when(pagamentoProxy.getUuidReferencia()).thenReturn(uuidRef);

        when(vendaRepository.findById(vendaId)).thenReturn(Optional.of(venda));
        when(entityManager.getReference(Pagamento.class, pagamentoId)).thenReturn(pagamentoProxy);

        String payload = """
                {"vendaId":10,"pagamentoId":50}
                """;

        handler.handlePagamentoConfirmado(payload);

        assertThat(venda.getStatus()).isEqualTo(StatusVenda.AGUARDANDO_PAGAMENTO);
        assertThat(venda.getValorPago()).isEqualByComparingTo("40.00");
        assertThat(venda.getValorTroco()).isEqualByComparingTo("0.00");

        verifyNoInteractions(estoqueCommandService);
        verify(vendaRepository, times(1)).save(venda);
        verify(eventPublisher).publishEvent(new VendaPagaEvent(vendaId, uuidRef, new BigDecimal("40.00")));
        verify(eventPublisher).publishEvent(any(VendaAtualizadaEvent.class));
    }

    @Test
    @DisplayName("DEVE lançar exceção quando venda não existir sem tocar no estoque")
    void deveFalharQuandoVendaNaoEncontrada() {
        when(vendaRepository.findById(999L)).thenReturn(Optional.empty());

        String payload = """
                {"vendaId":999,"pagamentoId":50}
                """;

        assertThatThrownBy(() -> handler.handlePagamentoConfirmado(payload))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(BusinessException.class);

        verifyNoInteractions(entityManager, estoqueCommandService, eventPublisher);
        verify(vendaRepository, never()).save(any());
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
        Pagamento pagamento = mock(Pagamento.class);
        when(pagamento.getValorRecebido()).thenReturn(venda.getValorTotal());
        venda.registrarPagamentoConfirmado(pagamento);
        venda.concluir();
        return venda;
    }
}
