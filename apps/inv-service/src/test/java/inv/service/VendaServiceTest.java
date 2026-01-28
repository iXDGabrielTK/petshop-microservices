package inv.service;

import common.exception.BusinessException;
import inv.dto.ItemVendaRequest;
import inv.dto.VendaRequest;
import inv.event.EstoqueAtingiuMinimoEvent;
import inv.model.Produto;
import inv.repository.MovimentacaoRepository;
import inv.repository.ProdutoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.ArgumentCaptor;


import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VendaServiceTest {

    @Mock
    private ProdutoRepository produtoRepository;

    @Mock
    private MovimentacaoRepository movimentacaoRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private VendaService vendaService;

    @Test
    @DisplayName("DEVE disparar evento quando cruzar o limite (11 -> 9, Min 10)")
    void deveDispararEventoAoCruzarLimite() {
        // CENÁRIO
        BigDecimal estoqueInicial = new BigDecimal("11");
        BigDecimal estoqueMinimo = new BigDecimal("10");
        BigDecimal qtdVenda = new BigDecimal("2");
        BigDecimal estoqueFinalSimulado = new BigDecimal("9"); // O banco retornará isso

        Produto produto = criarProduto(1L, estoqueInicial, estoqueMinimo);
        VendaRequest request = criarRequest(qtdVenda);

        // MOCKS
        when(produtoRepository.findAllById(any())).thenReturn(List.of(produto));
        // Simulamos o RETURNING do banco devolvendo 9
        when(produtoRepository.decrementarEretornarSaldo(eq(1L), eq(qtdVenda)))
                .thenReturn(estoqueFinalSimulado);

        // AÇÃO
        vendaService.realizarVenda(request);

        // VERIFICAÇÃO
        // Verifica se o publishEvent foi chamado exatamente 1 vez
        verify(eventPublisher, times(1)).publishEvent(any(EstoqueAtingiuMinimoEvent.class));
        // Verifica se salvou a movimentação
        verify(movimentacaoRepository, times(1)).saveAll(any());
    }

    @Test
    @DisplayName("NÃO DEVE disparar evento se já estava baixo (9 -> 7, Min 10)")
    void naoDeveDispararEventoSeJaEstavaBaixo() {
        // CENÁRIO (Anti-Spam)
        BigDecimal estoqueInicial = new BigDecimal("9"); // Já está abaixo do min
        BigDecimal estoqueMinimo = new BigDecimal("10");
        BigDecimal qtdVenda = new BigDecimal("2");
        BigDecimal estoqueFinalSimulado = new BigDecimal("7");

        Produto produto = criarProduto(1L, estoqueInicial, estoqueMinimo);
        VendaRequest request = criarRequest(qtdVenda);

        // MOCKS
        when(produtoRepository.findAllById(any())).thenReturn(List.of(produto));
        when(produtoRepository.decrementarEretornarSaldo(eq(1L), eq(qtdVenda)))
                .thenReturn(estoqueFinalSimulado);

        // AÇÃO
        vendaService.realizarVenda(request);

        // VERIFICAÇÃO
        // O ponto crucial: NÃO deve chamar o evento, pois 9 não é > 10.
        verify(eventPublisher, never()).publishEvent(any());

        // Mas a venda deve ocorrer normalmente
        verify(movimentacaoRepository, times(1)).saveAll(any());
    }

    @Test
    @DisplayName("DEVE lançar erro se o banco retornar null (Concorrência)")
    void deveLancarErroSeUpdateFalhar() {
        Produto produto = criarProduto(1L, new BigDecimal("10"), new BigDecimal("5"));
        VendaRequest request = criarRequest(new BigDecimal("15")); // Tentando vender mais que tem

        when(produtoRepository.findAllById(any())).thenReturn(List.of(produto));
        // Banco retorna null porque o WHERE estoque >= qtd falhou
        when(produtoRepository.decrementarEretornarSaldo(anyLong(), any())).thenReturn(null);

        assertThrows(BusinessException.class, () -> vendaService.realizarVenda(request));

        // Garante que nada foi salvo nem publicado
        verify(movimentacaoRepository, never()).saveAll(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("NÃO DEVE disparar evento se já estava EXATAMENTE no limite (10 -> 9, Min 10)")
    void naoDeveDispararSeEstavaNoLimiteExato() {
        // CENÁRIO
        // Regra: Só dispara se antes > min.
        // Aqui 10 não é maior que 10, então entende-se que já estava em estado de alerta.
        BigDecimal estoqueInicial = new BigDecimal("10");
        BigDecimal estoqueMinimo = new BigDecimal("10");
        BigDecimal qtdVenda = new BigDecimal("1");
        BigDecimal estoqueFinalSimulado = new BigDecimal("9");

        Produto produto = criarProduto(1L, estoqueInicial, estoqueMinimo);
        VendaRequest request = criarRequest(qtdVenda);

        when(produtoRepository.findAllById(any())).thenReturn(List.of(produto));
        when(produtoRepository.decrementarEretornarSaldo(eq(1L), eq(qtdVenda)))
                .thenReturn(estoqueFinalSimulado);

        // AÇÃO
        vendaService.realizarVenda(request);

        // VERIFICAÇÃO
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("DEVE disparar evento APENAS para o produto que cruzou o limite em pedido misto")
    void deveDispararEventoApenasParaProdutoCritico() {
        // CENÁRIO
        // Produto 1: 100 -> 90 (Min 10) -> NÃO DISPARA
        Produto p1 = criarProduto(1L, new BigDecimal("100"), new BigDecimal("10"));
        // Produto 2: 11 -> 9 (Min 10) -> DISPARA
        Produto p2 = criarProduto(2L, new BigDecimal("11"), new BigDecimal("10"));

        ItemVendaRequest item1 = new ItemVendaRequest(1L, new BigDecimal("10"));
        ItemVendaRequest item2 = new ItemVendaRequest(2L, new BigDecimal("2"));
        VendaRequest request = new VendaRequest(List.of(item1, item2));

        when(produtoRepository.findAllById(any())).thenReturn(List.of(p1, p2));

        // Mocks específicos para cada produto
        when(produtoRepository.decrementarEretornarSaldo(eq(1L), any())).thenReturn(new BigDecimal("90"));
        when(produtoRepository.decrementarEretornarSaldo(eq(2L), any())).thenReturn(new BigDecimal("9"));

        // AÇÃO
        vendaService.realizarVenda(request);

        // VERIFICAÇÃO
        // Deve chamar publishEvent exatamente 1 vez (só para o produto 2)
        verify(eventPublisher, times(1)).publishEvent(any(EstoqueAtingiuMinimoEvent.class));
    }

    @Test
    @DisplayName("DEVE gerar apenas UM alerta mesmo se o produto aparecer 2x na venda e cruzar o limite no meio")
    void deveEvitarDuplicidadeDeAlertaNoMesmoPedido() {
        // CENÁRIO: Produto começa com 12. Min 10.
        // Item 1: Compra 2 (Vai para 10) -> CRUZOU O LIMITE (12 > 10 e 10 <= 10) -> Gera Alerta
        // Item 2: Compra 1 (Vai para 9)  -> JÁ ESTAVA NO LIMITE (10 não é > 10) -> Não Gera

        Produto p1 = criarProduto(1L, new BigDecimal("12"), new BigDecimal("10"));

        ItemVendaRequest itemA = new ItemVendaRequest(1L, new BigDecimal("2"));
        ItemVendaRequest itemB = new ItemVendaRequest(1L, new BigDecimal("1"));
        VendaRequest request = new VendaRequest(List.of(itemA, itemB));

        when(produtoRepository.findAllById(any())).thenReturn(List.of(p1));

        // Mock das chamadas sequenciais
        when(produtoRepository.decrementarEretornarSaldo(eq(1L), eq(new BigDecimal("2"))))
                .thenReturn(new BigDecimal("10")); // Primeira baixa
        when(produtoRepository.decrementarEretornarSaldo(eq(1L), eq(new BigDecimal("1"))))
                .thenReturn(new BigDecimal("9"));  // Segunda baixa

        // AÇÃO
        vendaService.realizarVenda(request);

        // 1. Criamos um "Capturador" genérico para enganar a sobrecarga do metodo publishEvent
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);

        // 2. Verificamos se foi chamado 1 vez e "roubamos" o argumento que foi passado
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());

        // 3. Pegamos o valor capturado para inspecionar
        Object eventoCapturado = eventCaptor.getValue();

        // 4. Fazemos as asserções para garantir que é o evento certo
        assertInstanceOf(EstoqueAtingiuMinimoEvent.class, eventoCapturado, "O evento publicado deveria ser do tipo EstoqueAtingiuMinimoEvent");

        // Validação Extra: Confere se o payload do evento tem os dados certos
        EstoqueAtingiuMinimoEvent eventoReal = (EstoqueAtingiuMinimoEvent) eventoCapturado;
        assertEquals(new BigDecimal("10"), eventoReal.payload().estoqueAtual(),
                "O evento deveria reportar o saldo que cruzou a linha (10), não o final (9)");
    }

    // --- Métodos Auxiliares para limpar o código ---

    private Produto criarProduto(Long id, BigDecimal qtd, BigDecimal min) {
        Produto p = new Produto();
        p.setId(id);
        p.setNome("Produto Teste");
        p.setPrecoVenda(BigDecimal.TEN);
        p.setQuantidadeEstoque(qtd);
        p.setEstoqueMinimo(min);
        return p;
    }

    private VendaRequest criarRequest(BigDecimal qtd) {
        ItemVendaRequest item = new ItemVendaRequest(1L, qtd);
        return new VendaRequest(List.of(item));
    }
}