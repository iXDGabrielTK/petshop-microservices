package inv.service;

import inv.dto.EstoqueBaixoMessage;
import inv.event.EstoqueAtingiuMinimoEvent;
import inv.model.MovimentacaoEstoque;
import inv.model.Produto;
import inv.model.TipoMovimentacao;
import inv.model.Venda;
import inv.repository.MovimentacaoRepository;
import inv.repository.ProdutoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EstoqueServiceTest {

    @Mock private ProdutoRepository produtoRepository;
    @Mock private MovimentacaoRepository movimentacaoRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private EstoqueService estoqueService;

    @Test
    @DisplayName("DEVE reservar estoque, gerar histórico de RESERVA e disparar evento ao cruzar limite mínimo")
    void deveReservarEstoqueEDispararEventoAoCruzarLimite() {
        // CENÁRIO
        BigDecimal qtdReserva = new BigDecimal("2");
        Venda vendaMock = mock(Venda.class);
        when(vendaMock.getId()).thenReturn(100L);

        Produto produtoSpy = spy(new Produto());
        produtoSpy.setId(1L);
        produtoSpy.setNome("Ração Premium");
        produtoSpy.setEstoqueDisponivel(new BigDecimal("11"));
        produtoSpy.setEstoqueMinimo(new BigDecimal("10"));

        doAnswer(invocation -> {
            produtoSpy.setEstoqueDisponivel(new BigDecimal("9"));
            return null;
        }).when(produtoSpy).reservar(qtdReserva);

        // AÇÃO
        estoqueService.reservarEstoqueParaVenda(produtoSpy, qtdReserva, vendaMock);

        // VERIFICAÇÃO 1: Contrato de Domínio e Persistência
        verify(produtoSpy, times(1)).reservar(qtdReserva);
        verify(produtoRepository, times(1)).save(produtoSpy);

        // VERIFICAÇÃO 2: Histórico de Movimentação (Auditoria)
        ArgumentCaptor<MovimentacaoEstoque> movCaptor = ArgumentCaptor.forClass(MovimentacaoEstoque.class);
        verify(movimentacaoRepository, times(1)).save(movCaptor.capture());

        MovimentacaoEstoque movimentacaoSalva = movCaptor.getValue();
        assertAll("Validação da Movimentação de Reserva",
                () -> assertEquals(TipoMovimentacao.RESERVA, movimentacaoSalva.getTipo()),
                () -> assertEquals(qtdReserva, movimentacaoSalva.getQuantidade()),
                () -> assertEquals(produtoSpy, movimentacaoSalva.getProduto()),
                () -> assertEquals(vendaMock, movimentacaoSalva.getVenda()),
                () -> assertTrue(movimentacaoSalva.getObservacao().contains("#100"))
        );

        // VERIFICAÇÃO 3: Evento de Domínio
        ArgumentCaptor<EstoqueAtingiuMinimoEvent> eventCaptor = ArgumentCaptor.forClass(EstoqueAtingiuMinimoEvent.class);
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());

        EstoqueBaixoMessage mensagem = eventCaptor.getValue().payload();
        assertEquals("Ração Premium", mensagem.nomeProduto());
        assertEquals(new BigDecimal("9"), mensagem.estoqueAtual());
    }

    @Test
    @DisplayName("DEVE reservar estoque e gerar histórico, mas NÃO disparar evento se o estoque já estava abaixo do mínimo")
    void deveReservarEstoqueSemDispararEventoSeJaEstavaBaixo() {
        // CENÁRIO
        BigDecimal qtdReserva = new BigDecimal("2");
        Venda vendaMock = mock(Venda.class);

        Produto produtoSpy = spy(new Produto());
        produtoSpy.setEstoqueDisponivel(new BigDecimal("9"));
        produtoSpy.setEstoqueMinimo(new BigDecimal("10"));

        doAnswer(invocation -> {
            produtoSpy.setEstoqueDisponivel(new BigDecimal("7"));
            return null;
        }).when(produtoSpy).reservar(qtdReserva);

        // AÇÃO
        estoqueService.reservarEstoqueParaVenda(produtoSpy, qtdReserva, vendaMock);

        // VERIFICAÇÃO
        verify(produtoSpy).reservar(qtdReserva);
        verify(produtoRepository).save(produtoSpy);
        verify(movimentacaoRepository).save(any(MovimentacaoEstoque.class));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("DEVE confirmar baixa definitiva gerando histórico de SAIDA sem disparar eventos adicionais")
    void deveConfirmarBaixaDefinitivaDoEstoque() {
        // CENÁRIO
        BigDecimal qtdBaixa = new BigDecimal("5");
        Venda vendaMock = mock(Venda.class);
        Produto produtoSpy = spy(new Produto());

        // AÇÃO
        estoqueService.confirmarBaixaEstoque(produtoSpy, qtdBaixa, vendaMock);

        // VERIFICAÇÃO
        verify(produtoSpy, times(1)).confirmarReserva(qtdBaixa);
        verify(produtoRepository, times(1)).save(produtoSpy);

        // Valida se a movimentação correta foi registrada
        ArgumentCaptor<MovimentacaoEstoque> movCaptor = ArgumentCaptor.forClass(MovimentacaoEstoque.class);
        verify(movimentacaoRepository).save(movCaptor.capture());
        assertEquals(TipoMovimentacao.SAIDA, movCaptor.getValue().getTipo(), "A movimentação deve ser do tipo SAÍDA definitiva");

        // Evento de estoque baixo só roda na reserva
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("DEVE estornar reserva gerando histórico de CANCELAMENTO_RESERVA quando venda falhar")
    void deveEstornarReservaDeEstoque() {
        // CENÁRIO
        BigDecimal qtdEstorno = new BigDecimal("3");
        Venda vendaMock = mock(Venda.class);
        Produto produtoSpy = spy(new Produto());

        // AÇÃO
        estoqueService.estornarReservaEstoque(produtoSpy, qtdEstorno, vendaMock);

        // VERIFICAÇÃO
        verify(produtoSpy, times(1)).cancelarReserva(qtdEstorno);
        verify(produtoRepository, times(1)).save(produtoSpy);

        // Valida se a movimentação correta foi registrada
        ArgumentCaptor<MovimentacaoEstoque> movCaptor = ArgumentCaptor.forClass(MovimentacaoEstoque.class);
        verify(movimentacaoRepository).save(movCaptor.capture());
        assertEquals(TipoMovimentacao.CANCELAMENTO_RESERVA, movCaptor.getValue().getTipo());

        verifyNoInteractions(eventPublisher);
    }
}