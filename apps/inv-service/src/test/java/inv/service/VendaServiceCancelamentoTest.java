package inv.service;

import common.exception.BusinessException;
import inv.checkout.usecase.VendaService;
import inv.inventory.usecase.EstoqueService;
import inv.checkout.domain.model.ItemVenda;
import inv.inventory.domain.model.Produto;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.persistence.VendaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VendaServiceCancelamentoTest {

    @Mock private VendaRepository vendaRepository;
    @Mock private EstoqueService estoqueService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private VendaService vendaService;

    @Test
    @DisplayName("DEVE estornar reservas de estoque de todos os itens e cancelar a venda")
    void deveCancelarVendaEEstornarEstoque() {
        // CENÁRIO
        Long vendaId = 100L;

        // Venda (Spy)
        Venda vendaSpy = spy(new Venda());
        doReturn(vendaId).when(vendaSpy).getId();

        // Produtos (Mocks)
        Produto produto1 = mock(Produto.class);
        Produto produto2 = mock(Produto.class);

        // Itens da Venda (Mocks)
        ItemVenda item1 = mock(ItemVenda.class);
        when(item1.getProduto()).thenReturn(produto1);
        when(item1.getQuantidade()).thenReturn(new BigDecimal("5"));

        ItemVenda item2 = mock(ItemVenda.class);
        when(item2.getProduto()).thenReturn(produto2);
        when(item2.getQuantidade()).thenReturn(new BigDecimal("3"));

        doReturn(List.of(item1, item2)).when(vendaSpy).getItens();

        when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(vendaSpy));

        // AÇÃO
        vendaService.cancelarVendaExpirada(vendaId);

        // VERIFICAÇÃO 1: Mudança de estado da entidade
        verify(vendaSpy, times(1)).cancelar();
        verify(vendaRepository, times(1)).save(vendaSpy);

        // VERIFICAÇÃO 2: Contrato do loop de itens (Estorno de reservas)
        verify(estoqueService, times(1)).estornarReservaEstoque(eq(produto1), eq(new BigDecimal("5")), eq(vendaId));
        verify(estoqueService, times(1)).estornarReservaEstoque(eq(produto2), eq(new BigDecimal("3")), eq(vendaId));

        // VERIFICAÇÃO 3: Nenhuma ação paralela indevida
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("DEVE falhar ao tentar cancelar uma venda inexistente")
    void deveFalharAoCancelarVendaInexistente() {
        when(vendaRepository.findByIdWithLock(any())).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> vendaService.cancelarVendaExpirada(999L));

        verifyNoInteractions(estoqueService, eventPublisher);
        verify(vendaRepository, never()).save(any());
    }
}