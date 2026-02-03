package inv.service;

import common.exception.ResourceNotFoundException;
import inv.dto.ItemVendaRequest;
import inv.dto.VendaRequest;
import inv.model.Produto;
import inv.model.Venda;
import inv.repository.ProdutoRepository;
import inv.repository.VendaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VendaServiceTest {

    @Mock
    private ProdutoRepository produtoRepository;

    @Mock
    private VendaRepository vendaRepository;

    @Mock
    private EstoqueService estoqueService;

    @InjectMocks
    private VendaService vendaService;

    @Test
    @DisplayName("DEVE realizar venda com sucesso e delegar baixa de estoque")
    void deveRealizarVendaComSucesso() {
        // CENÁRIO
        Long produtoId = 1L;
        BigDecimal quantidade = new BigDecimal("2");

        Produto produto = new Produto();
        produto.setId(produtoId);
        produto.setNome("Ração");
        produto.setPrecoVenda(new BigDecimal("50.00"));

        ItemVendaRequest itemRequest = new ItemVendaRequest(produtoId, quantidade);
        VendaRequest request = new VendaRequest(List.of(itemRequest));

        // MOCKS
        // 1. O repositório deve encontrar o produto
        when(produtoRepository.findAllById(any())).thenReturn(List.of(produto));

        // 2. O repositório de venda salva a venda final
        when(vendaRepository.save(any(Venda.class))).thenAnswer(invocation -> {
            Venda vendaParaSalvar = invocation.getArgument(0);
            vendaParaSalvar.calcularTotal();
            return vendaParaSalvar;
        });

        // AÇÃO
        var recibo = vendaService.realizarVenda(request);

        // VERIFICAÇÃO
        assertNotNull(recibo);
        assertEquals(new BigDecimal("100.00"), recibo.valorTotal()); // 50 * 2

        // Verifica se o EstoqueService foi chamado para baixar o estoque
        verify(estoqueService, times(1)).baixarEstoquePorVenda(eq(produto), eq(quantidade), any(Venda.class));

        // Verifica se a venda foi persistida
        verify(vendaRepository, times(1)).save(any(Venda.class));
    }

    @Test
    @DisplayName("DEVE falhar se produto não for encontrado")
    void deveFalharSeProdutoNaoExiste() {
        VendaRequest request = new VendaRequest(List.of(new ItemVendaRequest(99L, BigDecimal.ONE)));

        when(produtoRepository.findAllById(any())).thenReturn(List.of());

        assertThrows(ResourceNotFoundException.class, () -> vendaService.realizarVenda(request));

        verifyNoInteractions(estoqueService);
        verifyNoInteractions(vendaRepository);
    }
}