package inv.inventory.usecase;

import common.exception.BusinessException;
import common.exception.ResourceNotFoundException;
import inv.inventory.api.ComandoEstoqueVenda;
import inv.inventory.api.ItemComando;
import inv.inventory.api.ProdutoCheckoutView;
import inv.inventory.domain.model.MovimentacaoEstoque;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.TipoMovimentacao;
import inv.inventory.infrastructure.messaging.EstoqueAtingiuMinimoEvent;
import inv.inventory.infrastructure.messaging.EstoqueBaixoMessage;
import inv.inventory.infrastructure.persistence.MovimentacaoRepository;
import inv.inventory.infrastructure.persistence.ProdutoMetadadosEstoque;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EstoqueServiceLoteTest {

    @Mock
    private ProdutoRepository produtoRepository;

    @Mock
    private MovimentacaoRepository movimentacaoRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private EstoqueService estoqueService;

    @Nested
    @DisplayName("Ordenação determinística por produtoId crescente (Q3 / F5)")
    class OrdenacaoDeLocks {

        @Test
        @DisplayName("reservar com comando [3, 1, 2] deve executar reservarEstoqueAtomo na ordem estrita (1), (2), (3)")
        void reservarDeveOrdenarPorProdutoIdCrescente() {
            ComandoEstoqueVenda comando = new ComandoEstoqueVenda(100L, List.of(
                    new ItemComando(3L, new BigDecimal("3.000")),
                    new ItemComando(1L, new BigDecimal("1.000")),
                    new ItemComando(2L, new BigDecimal("2.000"))
            ));

            when(produtoRepository.buscarMetadadosEstoque(Set.of(1L, 2L, 3L))).thenReturn(List.of(
                    new ProdutoMetadadosEstoque(1L, "Produto 1", new BigDecimal("2.000")),
                    new ProdutoMetadadosEstoque(2L, "Produto 2", new BigDecimal("2.000")),
                    new ProdutoMetadadosEstoque(3L, "Produto 3", new BigDecimal("2.000"))
            ));
            when(produtoRepository.reservarEstoqueAtomo(1L, new BigDecimal("1.000"))).thenReturn(new BigDecimal("10.000"));
            when(produtoRepository.reservarEstoqueAtomo(2L, new BigDecimal("2.000"))).thenReturn(new BigDecimal("10.000"));
            when(produtoRepository.reservarEstoqueAtomo(3L, new BigDecimal("3.000"))).thenReturn(new BigDecimal("10.000"));
            when(produtoRepository.getReferenceById(anyLong())).thenAnswer(inv -> {
                Produto p = new Produto();
                p.setId(inv.getArgument(0));
                return p;
            });

            estoqueService.reservar(comando);

            InOrder inOrder = inOrder(produtoRepository);
            inOrder.verify(produtoRepository).buscarMetadadosEstoque(Set.of(1L, 2L, 3L));
            inOrder.verify(produtoRepository).reservarEstoqueAtomo(1L, new BigDecimal("1.000"));
            inOrder.verify(produtoRepository).getReferenceById(1L);
            inOrder.verify(produtoRepository).reservarEstoqueAtomo(2L, new BigDecimal("2.000"));
            inOrder.verify(produtoRepository).getReferenceById(2L);
            inOrder.verify(produtoRepository).reservarEstoqueAtomo(3L, new BigDecimal("3.000"));
            inOrder.verify(produtoRepository).getReferenceById(3L);

            verify(produtoRepository, never()).findById(anyLong());
            verify(produtoRepository, never()).findAllById(any());
            verify(produtoRepository, never()).save(any());
        }

        @Test
        @DisplayName("confirmarBaixa com comando [3, 1, 2] deve executar confirmarBaixaEstoqueAtomo na ordem estrita (1), (2), (3)")
        void confirmarBaixaDeveOrdenarPorProdutoIdCrescente() {
            ComandoEstoqueVenda comando = new ComandoEstoqueVenda(101L, List.of(
                    new ItemComando(3L, new BigDecimal("3.000")),
                    new ItemComando(1L, new BigDecimal("1.000")),
                    new ItemComando(2L, new BigDecimal("2.000"))
            ));

            when(produtoRepository.confirmarBaixaEstoqueAtomo(1L, new BigDecimal("1.000"))).thenReturn(BigDecimal.ZERO);
            when(produtoRepository.confirmarBaixaEstoqueAtomo(2L, new BigDecimal("2.000"))).thenReturn(BigDecimal.ZERO);
            when(produtoRepository.confirmarBaixaEstoqueAtomo(3L, new BigDecimal("3.000"))).thenReturn(BigDecimal.ZERO);
            when(produtoRepository.getReferenceById(anyLong())).thenReturn(new Produto());

            estoqueService.confirmarBaixa(comando);

            InOrder inOrder = inOrder(produtoRepository);
            inOrder.verify(produtoRepository).confirmarBaixaEstoqueAtomo(1L, new BigDecimal("1.000"));
            inOrder.verify(produtoRepository).getReferenceById(1L);
            inOrder.verify(produtoRepository).confirmarBaixaEstoqueAtomo(2L, new BigDecimal("2.000"));
            inOrder.verify(produtoRepository).getReferenceById(2L);
            inOrder.verify(produtoRepository).confirmarBaixaEstoqueAtomo(3L, new BigDecimal("3.000"));
            inOrder.verify(produtoRepository).getReferenceById(3L);

            verify(produtoRepository, never()).buscarMetadadosEstoque(any());
            verify(produtoRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("estornarReserva com comando [3, 1, 2] deve executar estornarReservaEstoqueAtomo na ordem estrita (1), (2), (3)")
        void estornarReservaDeveOrdenarPorProdutoIdCrescente() {
            ComandoEstoqueVenda comando = new ComandoEstoqueVenda(102L, List.of(
                    new ItemComando(3L, new BigDecimal("3.000")),
                    new ItemComando(1L, new BigDecimal("1.000")),
                    new ItemComando(2L, new BigDecimal("2.000"))
            ));

            when(produtoRepository.estornarReservaEstoqueAtomo(1L, new BigDecimal("1.000"))).thenReturn(new BigDecimal("5.000"));
            when(produtoRepository.estornarReservaEstoqueAtomo(2L, new BigDecimal("2.000"))).thenReturn(new BigDecimal("5.000"));
            when(produtoRepository.estornarReservaEstoqueAtomo(3L, new BigDecimal("3.000"))).thenReturn(new BigDecimal("5.000"));
            when(produtoRepository.getReferenceById(anyLong())).thenReturn(new Produto());

            estoqueService.estornarReserva(comando);

            InOrder inOrder = inOrder(produtoRepository);
            inOrder.verify(produtoRepository).estornarReservaEstoqueAtomo(1L, new BigDecimal("1.000"));
            inOrder.verify(produtoRepository).getReferenceById(1L);
            inOrder.verify(produtoRepository).estornarReservaEstoqueAtomo(2L, new BigDecimal("2.000"));
            inOrder.verify(produtoRepository).getReferenceById(2L);
            inOrder.verify(produtoRepository).estornarReservaEstoqueAtomo(3L, new BigDecimal("3.000"));
            inOrder.verify(produtoRepository).getReferenceById(3L);

            verify(produtoRepository, never()).buscarMetadadosEstoque(any());
            verify(produtoRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("Itens duplicados do mesmo produtoId devem preservar a ordem relativa (ordenação estável)")
        void duplicadosDevemPreservarOrdemRelativa() {
            ComandoEstoqueVenda comando = new ComandoEstoqueVenda(103L, List.of(
                    new ItemComando(2L, new BigDecimal("1.000")),
                    new ItemComando(1L, new BigDecimal("2.000")),
                    new ItemComando(1L, new BigDecimal("5.000"))
            ));

            when(produtoRepository.buscarMetadadosEstoque(Set.of(1L, 2L))).thenReturn(List.of(
                    new ProdutoMetadadosEstoque(1L, "Produto 1", new BigDecimal("1.000")),
                    new ProdutoMetadadosEstoque(2L, "Produto 2", new BigDecimal("1.000"))
            ));
            when(produtoRepository.reservarEstoqueAtomo(1L, new BigDecimal("2.000"))).thenReturn(new BigDecimal("18.000"));
            when(produtoRepository.reservarEstoqueAtomo(1L, new BigDecimal("5.000"))).thenReturn(new BigDecimal("13.000"));
            when(produtoRepository.reservarEstoqueAtomo(2L, new BigDecimal("1.000"))).thenReturn(new BigDecimal("9.000"));
            when(produtoRepository.getReferenceById(anyLong())).thenReturn(new Produto());

            estoqueService.reservar(comando);

            InOrder inOrder = inOrder(produtoRepository);
            inOrder.verify(produtoRepository).reservarEstoqueAtomo(1L, new BigDecimal("2.000"));
            inOrder.verify(produtoRepository).reservarEstoqueAtomo(1L, new BigDecimal("5.000"));
            inOrder.verify(produtoRepository).reservarEstoqueAtomo(2L, new BigDecimal("1.000"));
        }
    }

    @Nested
    @DisplayName("Alerta de estoque mínimo com pré-imagem sob lock (F6)")
    class AlertaEstoqueMinimo {

        @Test
        @DisplayName("Deve publicar EstoqueAtingiuMinimoEvent quando saldoAnterior (novoSaldo + qtd) > minimo e novoSaldo <= minimo")
        void devePublicarEventoNaTransicaoDeAcimaParaAbaixoOuIgualAoMinimo() {
            ComandoEstoqueVenda comando = new ComandoEstoqueVenda(200L, List.of(
                    new ItemComando(1L, new BigDecimal("2.000"))
            ));

            when(produtoRepository.buscarMetadadosEstoque(Set.of(1L))).thenReturn(List.of(
                    new ProdutoMetadadosEstoque(1L, "Ração Super Premium", new BigDecimal("10.000"))
            ));
            // novoSaldo = 9.000, qtd = 2.000 -> saldoAnterior = 11.000 (> 10.000) e novoSaldo <= 10.000
            when(produtoRepository.reservarEstoqueAtomo(1L, new BigDecimal("2.000"))).thenReturn(new BigDecimal("9.000"));
            when(produtoRepository.getReferenceById(1L)).thenReturn(new Produto());

            estoqueService.reservar(comando);

            ArgumentCaptor<EstoqueAtingiuMinimoEvent> captor = ArgumentCaptor.forClass(EstoqueAtingiuMinimoEvent.class);
            verify(eventPublisher, times(1)).publishEvent(captor.capture());

            EstoqueBaixoMessage msg = captor.getValue().payload();
            assertThat(msg.nomeProduto()).isEqualTo("Ração Super Premium");
            assertThat(msg.estoqueAtual()).isEqualByComparingTo("9.000");
            assertThat(msg.estoqueMinimo()).isEqualByComparingTo("10.000");
        }

        @Test
        @DisplayName("Deve publicar EstoqueAtingiuMinimoEvent quando novoSaldo empata exatamente com estoqueMinimo")
        void devePublicarEventoQuandoNovoSaldoIgualaMinimo() {
            ComandoEstoqueVenda comando = new ComandoEstoqueVenda(201L, List.of(
                    new ItemComando(1L, new BigDecimal("1.000"))
            ));

            when(produtoRepository.buscarMetadadosEstoque(Set.of(1L))).thenReturn(List.of(
                    new ProdutoMetadadosEstoque(1L, "Shampoo Neutro", new BigDecimal("5.000"))
            ));
            // novoSaldo = 5.000, qtd = 1.000 -> saldoAnterior = 6.000 (> 5.000) e novoSaldo == 5.000
            when(produtoRepository.reservarEstoqueAtomo(1L, new BigDecimal("1.000"))).thenReturn(new BigDecimal("5.000"));
            when(produtoRepository.getReferenceById(1L)).thenReturn(new Produto());

            estoqueService.reservar(comando);

            verify(eventPublisher, times(1)).publishEvent(any(EstoqueAtingiuMinimoEvent.class));
        }

        @Test
        @DisplayName("NÃO deve publicar evento quando saldoAnterior já era <= estoqueMinimo (anti-spam)")
        void naoDevePublicarEventoQuandoSaldoAnteriorJaEstavaNoOuAbaixoDoMinimo() {
            ComandoEstoqueVenda comando = new ComandoEstoqueVenda(202L, List.of(
                    new ItemComando(1L, new BigDecimal("2.000"))
            ));

            when(produtoRepository.buscarMetadadosEstoque(Set.of(1L))).thenReturn(List.of(
                    new ProdutoMetadadosEstoque(1L, "Ração Super Premium", new BigDecimal("10.000"))
            ));
            // novoSaldo = 8.000, qtd = 2.000 -> saldoAnterior = 10.000 (não é > 10.000)
            when(produtoRepository.reservarEstoqueAtomo(1L, new BigDecimal("2.000"))).thenReturn(new BigDecimal("8.000"));
            when(produtoRepository.getReferenceById(1L)).thenReturn(new Produto());

            estoqueService.reservar(comando);

            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("NÃO deve publicar evento quando novoSaldo permanece > estoqueMinimo")
        void naoDevePublicarEventoQuandoNovoSaldoPermaneceAcimaDoMinimo() {
            ComandoEstoqueVenda comando = new ComandoEstoqueVenda(203L, List.of(
                    new ItemComando(1L, new BigDecimal("2.000"))
            ));

            when(produtoRepository.buscarMetadadosEstoque(Set.of(1L))).thenReturn(List.of(
                    new ProdutoMetadadosEstoque(1L, "Ração Super Premium", new BigDecimal("10.000"))
            ));
            when(produtoRepository.reservarEstoqueAtomo(1L, new BigDecimal("2.000"))).thenReturn(new BigDecimal("11.000"));
            when(produtoRepository.getReferenceById(1L)).thenReturn(new Produto());

            estoqueService.reservar(comando);

            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("NÃO deve publicar evento quando estoqueMinimo é null")
        void naoDevePublicarEventoQuandoEstoqueMinimoNulo() {
            ComandoEstoqueVenda comando = new ComandoEstoqueVenda(204L, List.of(
                    new ItemComando(1L, new BigDecimal("5.000"))
            ));

            when(produtoRepository.buscarMetadadosEstoque(Set.of(1L))).thenReturn(List.of(
                    new ProdutoMetadadosEstoque(1L, "Produto Sem Mínimo", null)
            ));
            when(produtoRepository.reservarEstoqueAtomo(1L, new BigDecimal("5.000"))).thenReturn(BigDecimal.ZERO);
            when(produtoRepository.getReferenceById(1L)).thenReturn(new Produto());

            estoqueService.reservar(comando);

            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    @DisplayName("Paridade de mensagens de erro, MovimentacaoEstoque e casos de borda (Q9)")
    class ParidadeFuncionalECasosDeBorda {

        @Test
        @DisplayName("reservar: registra MovimentacaoEstoque com observação e tipo idênticos ao legado usando getReferenceById")
        void reservarRegistraMovimentacaoIdenticaAoLegado() {
            Produto proxy = new Produto();
            proxy.setId(7L);

            when(produtoRepository.buscarMetadadosEstoque(Set.of(7L))).thenReturn(List.of(
                    new ProdutoMetadadosEstoque(7L, "Biscoito Canino", new BigDecimal("2.000"))
            ));
            when(produtoRepository.reservarEstoqueAtomo(7L, new BigDecimal("3.000"))).thenReturn(new BigDecimal("5.000"));
            when(produtoRepository.getReferenceById(7L)).thenReturn(proxy);

            estoqueService.reservar(new ComandoEstoqueVenda(300L, List.of(
                    new ItemComando(7L, new BigDecimal("3.000"))
            )));

            ArgumentCaptor<MovimentacaoEstoque> movCaptor = ArgumentCaptor.forClass(MovimentacaoEstoque.class);
            verify(movimentacaoRepository).save(movCaptor.capture());

            MovimentacaoEstoque mov = movCaptor.getValue();
            assertThat(mov.getProduto()).isSameAs(proxy);
            assertThat(mov.getQuantidade()).isEqualByComparingTo("3.000");
            assertThat(mov.getTipo()).isEqualTo(TipoMovimentacao.RESERVA);
            assertThat(mov.getVendaId()).isEqualTo(300L);
            assertThat(mov.getObservacao()).isEqualTo("Reserva de estoque para Venda #300");
            assertThat(mov.getDataHora()).isNotNull();
        }

        @Test
        @DisplayName("confirmarBaixa: registra MovimentacaoEstoque SAIDA com observação idêntica ao legado")
        void confirmarBaixaRegistraMovimentacaoIdenticaAoLegado() {
            Produto proxy = new Produto();
            proxy.setId(8L);

            when(produtoRepository.confirmarBaixaEstoqueAtomo(8L, new BigDecimal("4.000"))).thenReturn(BigDecimal.ZERO);
            when(produtoRepository.getReferenceById(8L)).thenReturn(proxy);

            estoqueService.confirmarBaixa(new ComandoEstoqueVenda(301L, List.of(
                    new ItemComando(8L, new BigDecimal("4.000"))
            )));

            ArgumentCaptor<MovimentacaoEstoque> movCaptor = ArgumentCaptor.forClass(MovimentacaoEstoque.class);
            verify(movimentacaoRepository).save(movCaptor.capture());

            MovimentacaoEstoque mov = movCaptor.getValue();
            assertThat(mov.getProduto()).isSameAs(proxy);
            assertThat(mov.getQuantidade()).isEqualByComparingTo("4.000");
            assertThat(mov.getTipo()).isEqualTo(TipoMovimentacao.SAIDA);
            assertThat(mov.getVendaId()).isEqualTo(301L);
            assertThat(mov.getObservacao()).isEqualTo("Baixa definitiva da Venda #301");
            assertThat(mov.getDataHora()).isNotNull();
        }

        @Test
        @DisplayName("estornarReserva: registra MovimentacaoEstoque CANCELAMENTO_RESERVA com observação idêntica ao legado")
        void estornarReservaRegistraMovimentacaoIdenticaAoLegado() {
            Produto proxy = new Produto();
            proxy.setId(9L);

            when(produtoRepository.estornarReservaEstoqueAtomo(9L, new BigDecimal("2.000"))).thenReturn(new BigDecimal("10.000"));
            when(produtoRepository.getReferenceById(9L)).thenReturn(proxy);

            estoqueService.estornarReserva(new ComandoEstoqueVenda(302L, List.of(
                    new ItemComando(9L, new BigDecimal("2.000"))
            )));

            ArgumentCaptor<MovimentacaoEstoque> movCaptor = ArgumentCaptor.forClass(MovimentacaoEstoque.class);
            verify(movimentacaoRepository).save(movCaptor.capture());

            MovimentacaoEstoque mov = movCaptor.getValue();
            assertThat(mov.getProduto()).isSameAs(proxy);
            assertThat(mov.getQuantidade()).isEqualByComparingTo("2.000");
            assertThat(mov.getTipo()).isEqualTo(TipoMovimentacao.CANCELAMENTO_RESERVA);
            assertThat(mov.getVendaId()).isEqualTo(302L);
            assertThat(mov.getObservacao()).isEqualTo("Estorno de reserva. Venda #302");
            assertThat(mov.getDataHora()).isNotNull();
        }

        @Test
        @DisplayName("reservar sem saldo: reporta mensagem exata do primeiro produto faltante em ordem de produtoId")
        void reservarSemSaldoLancaBusinessExceptionComMensagemExataEmOrdemDeProdutoId() {
            ComandoEstoqueVenda comando = new ComandoEstoqueVenda(303L, List.of(
                    new ItemComando(5L, new BigDecimal("10.000")),
                    new ItemComando(2L, new BigDecimal("10.000"))
            ));

            when(produtoRepository.buscarMetadadosEstoque(Set.of(2L, 5L))).thenReturn(List.of(
                    new ProdutoMetadadosEstoque(2L, "Areia Sílica", new BigDecimal("1.000")),
                    new ProdutoMetadadosEstoque(5L, "Casinha Plástica", new BigDecimal("1.000"))
            ));
            when(produtoRepository.reservarEstoqueAtomo(2L, new BigDecimal("10.000"))).thenReturn(null);

            assertThatThrownBy(() -> estoqueService.reservar(comando))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Estoque insuficiente para o produto: Areia Sílica");

            verify(produtoRepository, never()).reservarEstoqueAtomo(eq(5L), any());
            verifyNoInteractions(movimentacaoRepository, eventPublisher);
        }

        @Test
        @DisplayName("reservar com produto inexistente nos metadados lança ResourceNotFoundException defensiva")
        void reservarProdutoInexistenteLancaResourceNotFoundException() {
            ComandoEstoqueVenda comando = new ComandoEstoqueVenda(304L, List.of(
                    new ItemComando(99L, new BigDecimal("1.000"))
            ));

            when(produtoRepository.buscarMetadadosEstoque(Set.of(99L))).thenReturn(List.of());

            assertThatThrownBy(() -> estoqueService.reservar(comando))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Produto não encontrado: 99");

            verify(produtoRepository, never()).reservarEstoqueAtomo(anyLong(), any());
        }

        @Test
        @DisplayName("confirmarBaixa sem saldo reservado: busca nome apenas no caminho de erro e lança BusinessException idêntica")
        void confirmarBaixaSemReservaLancaBusinessExceptionIdentica() {
            ComandoEstoqueVenda comando = new ComandoEstoqueVenda(305L, List.of(
                    new ItemComando(4L, new BigDecimal("2.000"))
            ));

            when(produtoRepository.confirmarBaixaEstoqueAtomo(4L, new BigDecimal("2.000"))).thenReturn(null);
            when(produtoRepository.buscarMetadadosEstoque(List.of(4L))).thenReturn(List.of(
                    new ProdutoMetadadosEstoque(4L, "Coleira Peitoral", new BigDecimal("1.000"))
            ));

            assertThatThrownBy(() -> estoqueService.confirmarBaixa(comando))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Inconsistência: Tentativa de baixar mais estoque reservado do que o existente para: Coleira Peitoral");

            verifyNoInteractions(movimentacaoRepository);
        }

        @Test
        @DisplayName("estornarReserva sem saldo reservado: busca nome apenas no caminho de erro e lança BusinessException idêntica")
        void estornarReservaSemReservaLancaBusinessExceptionIdentica() {
            ComandoEstoqueVenda comando = new ComandoEstoqueVenda(306L, List.of(
                    new ItemComando(6L, new BigDecimal("2.000"))
            ));

            when(produtoRepository.estornarReservaEstoqueAtomo(6L, new BigDecimal("2.000"))).thenReturn(null);
            when(produtoRepository.buscarMetadadosEstoque(List.of(6L))).thenReturn(List.of(
                    new ProdutoMetadadosEstoque(6L, "Arranhador Gato", new BigDecimal("1.000"))
            ));

            assertThatThrownBy(() -> estoqueService.estornarReserva(comando))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Inconsistência: Tentativa de cancelar reserva inexistente para: Arranhador Gato");

            verifyNoInteractions(movimentacaoRepository);
        }

        @Test
        @DisplayName("Lista vazia resulta em no-op sem nenhuma query para reservar, confirmarBaixa e estornarReserva")
        void listaVaziaResultaEmNoOpSemQuery() {
            ComandoEstoqueVenda comandoVazio = new ComandoEstoqueVenda(307L, List.of());

            estoqueService.reservar(comandoVazio);
            estoqueService.confirmarBaixa(comandoVazio);
            estoqueService.estornarReserva(comandoVazio);

            verifyNoInteractions(produtoRepository, movimentacaoRepository, eventPublisher);
        }
    }

    @Nested
    @DisplayName("Invariantes dos records da Published Language (inv.inventory.api)")
    class InvariantesDosRecords {

        @Test
        @DisplayName("ComandoEstoqueVenda deve rejeitar vendaId <= 0")
        void comandoEstoqueVendaRejeitaVendaIdNaoPositivo() {
            assertThatThrownBy(() -> new ComandoEstoqueVenda(0L, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("vendaId");

            assertThatThrownBy(() -> new ComandoEstoqueVenda(-5L, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("vendaId");
        }

        @Test
        @DisplayName("ComandoEstoqueVenda deve rejeitar lista nula ou contendo elemento nulo e realizar cópia defensiva imutável")
        void comandoEstoqueVendaGaranteImutabilidadeENaoNulidade() {
            assertThatThrownBy(() -> new ComandoEstoqueVenda(1L, null))
                    .isInstanceOf(NullPointerException.class);

            List<ItemComando> comNulo = Arrays.asList(new ItemComando(1L, BigDecimal.ONE), null);
            assertThatThrownBy(() -> new ComandoEstoqueVenda(1L, comNulo))
                    .isInstanceOf(NullPointerException.class);

            List<ItemComando> mutavel = new ArrayList<>();
            mutavel.add(new ItemComando(1L, BigDecimal.ONE));
            ComandoEstoqueVenda comando = new ComandoEstoqueVenda(1L, mutavel);

            mutavel.clear();
            assertThat(comando.itens()).hasSize(1);
            assertThatThrownBy(() -> comando.itens().add(new ItemComando(2L, BigDecimal.ONE)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("ItemComando deve rejeitar produtoId <= 0, quantidade nula e quantidade <= 0")
        void itemComandoValidaInvariantes() {
            assertThatThrownBy(() -> new ItemComando(0L, BigDecimal.ONE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("produtoId");

            assertThatThrownBy(() -> new ItemComando(-1L, BigDecimal.ONE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("produtoId");

            assertThatThrownBy(() -> new ItemComando(1L, null))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new ItemComando(1L, BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("quantidade");

            assertThatThrownBy(() -> new ItemComando(1L, new BigDecimal("-0.001")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("quantidade");
        }

        @Test
        @DisplayName("ProdutoCheckoutView deve rejeitar id, nome ou precoVenda nulos")
        void produtoCheckoutViewValidaNaoNulidade() {
            assertThatThrownBy(() -> new ProdutoCheckoutView(null, "Nome", BigDecimal.TEN))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ProdutoCheckoutView(1L, null, BigDecimal.TEN))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ProdutoCheckoutView(1L, "Nome", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
