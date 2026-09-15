package inv.service;

import inv.inventory.infrastructure.messaging.EstoqueBaixoMessage;
import inv.inventory.infrastructure.messaging.EstoqueAtingiuMinimoEvent;
import inv.inventory.domain.model.MovimentacaoEstoque;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.TipoMovimentacao;
import inv.inventory.usecase.EstoqueService;
import inv.inventory.infrastructure.persistence.MovimentacaoRepository;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
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

        Produto produtoSpy = spy(new Produto());
        produtoSpy.setId(1L);
        produtoSpy.setNome("Ração Premium");
        produtoSpy.setEstoqueDisponivel(new BigDecimal("11"));
        produtoSpy.setEstoqueMinimo(new BigDecimal("10"));

        when(produtoRepository.reservarEstoqueAtomo(1L, qtdReserva)).thenReturn(new BigDecimal("9"));

        // AÇÃO
        estoqueService.reservarEstoqueParaVenda(produtoSpy, qtdReserva, 100L);

        // VERIFICAÇÃO 1: Contrato de Domínio e Persistência
        verify(produtoSpy, never()).reservar(any());
        verify(produtoRepository, times(1)).reservarEstoqueAtomo(1L, qtdReserva);
        verify(produtoRepository, never()).save(produtoSpy);

        // VERIFICAÇÃO 2: Histórico de Movimentação (Auditoria)
        ArgumentCaptor<MovimentacaoEstoque> movCaptor = ArgumentCaptor.forClass(MovimentacaoEstoque.class);
        verify(movimentacaoRepository, times(1)).save(movCaptor.capture());

        MovimentacaoEstoque movimentacaoSalva = movCaptor.getValue();
        assertAll("Validação da Movimentação de Reserva",
                () -> assertEquals(TipoMovimentacao.RESERVA, movimentacaoSalva.getTipo()),
                () -> assertEquals(qtdReserva, movimentacaoSalva.getQuantidade()),
                () -> assertEquals(produtoSpy, movimentacaoSalva.getProduto()),
                () -> assertEquals(100L, movimentacaoSalva.getVendaId()),
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

        Produto produtoSpy = spy(new Produto());
        produtoSpy.setId(2L);
        produtoSpy.setEstoqueDisponivel(new BigDecimal("9"));
        produtoSpy.setEstoqueMinimo(new BigDecimal("10"));

        when(produtoRepository.reservarEstoqueAtomo(2L, qtdReserva)).thenReturn(new BigDecimal("7"));

        // AÇÃO
        estoqueService.reservarEstoqueParaVenda(produtoSpy, qtdReserva, 100L);

        // VERIFICAÇÃO
        verify(produtoRepository, times(1)).reservarEstoqueAtomo(2L, qtdReserva);
        verify(produtoRepository, never()).save(produtoSpy);
        verify(movimentacaoRepository).save(any(MovimentacaoEstoque.class));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("DEVE confirmar baixa definitiva gerando histórico de SAIDA sem disparar eventos adicionais")
    void deveConfirmarBaixaDefinitivaDoEstoque() {
        // CENÁRIO
        BigDecimal qtdBaixa = new BigDecimal("5");
        Produto produtoSpy = spy(new Produto());
        produtoSpy.setId(3L);

        when(produtoRepository.confirmarBaixaEstoqueAtomo(3L, qtdBaixa)).thenReturn(new BigDecimal("0"));

        // AÇÃO
        estoqueService.confirmarBaixaEstoque(produtoSpy, qtdBaixa, 100L);

        // VERIFICAÇÃO
        verify(produtoSpy, never()).confirmarReserva(any());
        verify(produtoRepository, times(1)).confirmarBaixaEstoqueAtomo(3L, qtdBaixa);
        verify(produtoRepository, never()).save(produtoSpy);

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
        Produto produtoSpy = spy(new Produto());
        produtoSpy.setId(4L);

        when(produtoRepository.estornarReservaEstoqueAtomo(4L, qtdEstorno)).thenReturn(new BigDecimal("5"));

        // AÇÃO
        estoqueService.estornarReservaEstoque(produtoSpy, qtdEstorno, 100L);

        // VERIFICAÇÃO
        verify(produtoSpy, never()).cancelarReserva(any());
        verify(produtoRepository, times(1)).estornarReservaEstoqueAtomo(4L, qtdEstorno);
        verify(produtoRepository, never()).save(produtoSpy);

        // Valida se a movimentação correta foi registrada
        ArgumentCaptor<MovimentacaoEstoque> movCaptor = ArgumentCaptor.forClass(MovimentacaoEstoque.class);
        verify(movimentacaoRepository).save(movCaptor.capture());
        assertEquals(TipoMovimentacao.CANCELAMENTO_RESERVA, movCaptor.getValue().getTipo());

        verifyNoInteractions(eventPublisher);
    }
}