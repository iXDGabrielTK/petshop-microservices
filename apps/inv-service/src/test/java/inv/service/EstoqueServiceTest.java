package inv.service;

import common.exception.BusinessException;
import inv.event.EstoqueAtingiuMinimoEvent;
import inv.model.Produto;
import inv.model.Venda;
import inv.repository.ProdutoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EstoqueServiceTest {

    @Mock
    private ProdutoRepository produtoRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private EstoqueService estoqueService;

    @Test
    @DisplayName("DEVE disparar evento quando cruzar o limite (11 -> 9, Min 10)")
    void deveDispararEventoAoCruzarLimite() {
        // CENÁRIO
        BigDecimal estoqueInicial = new BigDecimal("11");
        BigDecimal estoqueMinimo = new BigDecimal("10");
        BigDecimal qtdBaixa = new BigDecimal("2");
        BigDecimal estoqueFinal = new BigDecimal("9"); // Simula retorno do banco

        Produto produto = criarProduto(estoqueInicial, estoqueMinimo);
        Venda vendaMock = mock(Venda.class);

        // MOCK: Simula a query nativa 'decrementarEretornarSaldo'
        when(produtoRepository.decrementarEretornarSaldo(eq(1L), eq(qtdBaixa)))
                .thenReturn(estoqueFinal);

        // AÇÃO
        estoqueService.baixarEstoquePorVenda(produto, qtdBaixa, vendaMock);

        // VERIFICAÇÃO
        verify(eventPublisher, times(1)).publishEvent(any(EstoqueAtingiuMinimoEvent.class));

        // Verifica se adicionou movimentação na venda
        verify(vendaMock, times(1)).adicionarMovimentacao(any());
    }

    @Test
    @DisplayName("NÃO DEVE disparar evento se já estava baixo (9 -> 7, Min 10)")
    void naoDeveDispararEventoSeJaEstavaBaixo() {
        // Anti-Spam
        Produto produto = criarProduto(new BigDecimal("9"), new BigDecimal("10"));
        when(produtoRepository.decrementarEretornarSaldo(any(), any())).thenReturn(new BigDecimal("7"));

        estoqueService.baixarEstoquePorVenda(produto, new BigDecimal("2"), mock(Venda.class));

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("DEVE lançar erro se saldo insuficiente (concorrência)")
    void deveLancarErroSeUpdateRetornarNull() {
        Produto produto = criarProduto(BigDecimal.TEN, BigDecimal.ZERO);

        // Simula falha no update (banco retornou null pois a condição WHERE falhou)
        when(produtoRepository.decrementarEretornarSaldo(any(), any())).thenReturn(null);

        assertThrows(BusinessException.class, () ->
                estoqueService.baixarEstoquePorVenda(produto, BigDecimal.ONE, mock(Venda.class))
        );

        verify(eventPublisher, never()).publishEvent(any());
    }

    private Produto criarProduto(BigDecimal qtd, BigDecimal min) {
        Produto p = new Produto();
        p.setId(1L);
        p.setNome("Produto Teste");
        p.setQuantidadeEstoque(qtd);
        p.setEstoqueMinimo(min);
        return p;
    }
}