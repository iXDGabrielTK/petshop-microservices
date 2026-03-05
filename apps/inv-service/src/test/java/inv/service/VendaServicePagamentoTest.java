package inv.service;

import common.exception.BusinessException;
import inv.event.VendaConcluidaEvent;
import inv.model.ItemVenda;
import inv.model.Pagamento;
import inv.model.Produto;
import inv.model.Venda;
import inv.repository.PagamentoRepository;
import inv.repository.VendaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VendaServicePagamentoTest {

    @Mock private PagamentoRepository pagamentoRepository;
    @Mock private VendaRepository vendaRepository;
    @Mock private EstoqueService estoqueService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private VendaService vendaService;

    @Test
    @DisplayName("DEVE confirmar pagamento, baixar estoque definitivo e publicar evento quando venda for quitada")
    void deveConcluirVendaQuandoPagamentoForIntegral() {
        // CENÁRIO
        Long vendaId = 100L;
        Long pagamentoId = 50L;

        // Venda (Spy)
        Venda vendaSpy = spy(new Venda());
        doReturn(vendaId).when(vendaSpy).getId();
        doReturn(true).when(vendaSpy).estaPaga();

        // Produto (Mock)
        Produto produtoMock = mock(Produto.class);

        // ItemVenda (Mock)
        ItemVenda itemMock = mock(ItemVenda.class);
        when(itemMock.getProduto()).thenReturn(produtoMock);
        when(itemMock.getQuantidade()).thenReturn(new BigDecimal("2"));

        doReturn(List.of(itemMock)).when(vendaSpy).getItens();

        // Pagamento (Mock)
        Pagamento pagamentoMock = mock(Pagamento.class);

        // Repositórios
        when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(vendaSpy));
        when(pagamentoRepository.findById(pagamentoId)).thenReturn(Optional.of(pagamentoMock));

        // AÇÃO
        vendaService.processarPagamento(vendaId, pagamentoId);

        // VERIFICAÇÃO 1: Interações com a entidade e repositório
        verify(vendaSpy).registrarPagamentoConfirmado(pagamentoMock);
        verify(vendaSpy).concluir();
        verify(vendaRepository, times(1)).save(vendaSpy);

        // VERIFICAÇÃO 2: Contrato com EstoqueService (baixa definitiva)
        verify(estoqueService, times(1)).confirmarBaixaEstoque(eq(produtoMock), eq(new BigDecimal("2")), eq(vendaSpy));

        // VERIFICAÇÃO 3: Publicação de eventos (Side-effects controlados)
        ArgumentCaptor<VendaConcluidaEvent> eventCaptor = ArgumentCaptor.forClass(VendaConcluidaEvent.class);
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());

        assertEquals(vendaId, eventCaptor.getValue().id(), "O evento deve carregar o ID da venda correta");
    }

    @Test
    @DisplayName("DEVE apenas registrar pagamento parcial sem concluir venda e sem baixar estoque")
    void deveRegistrarPagamentoParcialSemConcluir() {
        // CENÁRIO
        Venda vendaSpy = spy(new Venda());
        doReturn(100L).when(vendaSpy).getId();
        doReturn(false).when(vendaSpy).estaPaga();

        Pagamento pagamentoMock = mock(Pagamento.class);

        when(vendaRepository.findByIdWithLock(100L)).thenReturn(Optional.of(vendaSpy));
        when(pagamentoRepository.findById(50L)).thenReturn(Optional.of(pagamentoMock));

        // AÇÃO
        vendaService.processarPagamento(100L, 50L);

        // VERIFICAÇÃO
        verify(vendaSpy).registrarPagamentoConfirmado(pagamentoMock);
        verify(vendaSpy, never()).concluir();
        verify(vendaRepository, times(1)).save(vendaSpy);

        verifyNoInteractions(estoqueService);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("DEVE falhar se venda não existir e abortar processo")
    void deveFalharSeVendaNaoEncontrada() {
        when(vendaRepository.findByIdWithLock(any())).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> vendaService.processarPagamento(100L, 50L));

        verifyNoInteractions(pagamentoRepository, estoqueService, eventPublisher);
        verify(vendaRepository, never()).save(any());
    }
}