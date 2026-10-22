package inv.checkout.usecase;

import common.exception.BusinessException;
import common.exception.ResourceNotFoundException;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.checkout.infrastructure.web.dto.ItemVendaRequest;
import inv.checkout.infrastructure.web.dto.ReciboResponse;
import inv.checkout.infrastructure.web.dto.VendaRequest;
import inv.checkout.infrastructure.web.dto.VendaStatusResponse;
import inv.inventory.api.CatalogoQueryService;
import inv.inventory.api.ComandoEstoqueVenda;
import inv.inventory.api.EstoqueCommandService;
import inv.inventory.api.ItemComando;
import inv.inventory.api.ProdutoCheckoutView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VendaService — Testes Unitários de Caso de Uso")
class VendaServiceTest {

    @Mock
    private VendaRepository vendaRepository;

    @Mock
    private CatalogoQueryService catalogoQueryService;

    @Mock
    private EstoqueCommandService estoqueCommandService;

    @InjectMocks
    private VendaService vendaService;

    @Nested
    @DisplayName("iniciarVenda(VendaRequest)")
    class IniciarVenda {

        @Test
        @DisplayName("Deve iniciar venda com sucesso, reservar estoque e transicionar status para AGUARDANDO_PAGAMENTO")
        void deveIniciarVendaComSucesso() {
            // CENÁRIO
            Long produtoId1 = 10L;
            BigDecimal qtd1 = new BigDecimal("2.000");
            Long produtoId2 = 20L;
            BigDecimal qtd2 = new BigDecimal("3.000");

            VendaRequest pedido = new VendaRequest(List.of(
                    new ItemVendaRequest(produtoId1, qtd1),
                    new ItemVendaRequest(produtoId2, qtd2)
            ));

            ProdutoCheckoutView prod1 = new ProdutoCheckoutView(produtoId1, "Ração Cães 15kg", new BigDecimal("50.00"));
            ProdutoCheckoutView prod2 = new ProdutoCheckoutView(produtoId2, "Shampoo Pet 500ml", new BigDecimal("25.00"));

            when(catalogoQueryService.buscarParaCheckout(Set.of(produtoId1, produtoId2)))
                    .thenReturn(List.of(prod1, prod2));

            Long vendaId = 100L;
            when(vendaRepository.save(any(Venda.class))).thenAnswer(invocation -> {
                Venda v = invocation.getArgument(0);
                if (v.getId() == null) {
                    ReflectionTestUtils.setField(v, "id", vendaId);
                }
                return v;
            });

            // AÇÃO
            Venda vendaCriada = vendaService.iniciarVenda(pedido);

            // VERIFICAÇÕES
            assertThat(vendaCriada).isNotNull();
            assertThat(vendaCriada.getId()).isEqualTo(vendaId);
            assertThat(vendaCriada.getStatus()).isEqualTo(StatusVenda.AGUARDANDO_PAGAMENTO);
            assertThat(vendaCriada.getItens()).hasSize(2);
            // 2 * 50.00 + 3 * 25.00 = 100.00 + 75.00 = 175.00
            assertThat(vendaCriada.getValorTotal()).isEqualByComparingTo(new BigDecimal("175.00"));

            // Consulta de catálogo via Published Language
            verify(catalogoQueryService, times(1)).buscarParaCheckout(Set.of(produtoId1, produtoId2));

            // Reserva em lote via EstoqueCommandService
            ArgumentCaptor<ComandoEstoqueVenda> comandoCaptor = ArgumentCaptor.forClass(ComandoEstoqueVenda.class);
            verify(estoqueCommandService, times(1)).reservar(comandoCaptor.capture());

            ComandoEstoqueVenda comando = comandoCaptor.getValue();
            assertThat(comando.vendaId()).isEqualTo(vendaId);
            assertThat(comando.itens()).containsExactly(
                    new ItemComando(produtoId1, qtd1),
                    new ItemComando(produtoId2, qtd2)
            );

            // Venda salva 2 vezes: criação inicial e após adição de itens / transição de status
            verify(vendaRepository, times(2)).save(any(Venda.class));
        }

        @Test
        @DisplayName("Deve falhar com ResourceNotFoundException quando catálogo não encontrar nenhum produto")
        void deveFalharQuandoNenhumProdutoEncontrado() {
            // CENÁRIO
            Long produtoInexistente = 999L;
            VendaRequest pedido = new VendaRequest(List.of(
                    new ItemVendaRequest(produtoInexistente, new BigDecimal("1.000"))
            ));

            when(catalogoQueryService.buscarParaCheckout(Set.of(produtoInexistente)))
                    .thenReturn(List.of());

            // AÇÃO & ASSERÇÃO
            assertThatThrownBy(() -> vendaService.iniciarVenda(pedido))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Um ou mais produtos não foram encontrados.");

            verifyNoInteractions(estoqueCommandService);
            verifyNoInteractions(vendaRepository);
        }

        @Test
        @DisplayName("Deve falhar com ResourceNotFoundException quando catálogo encontrar apenas parte dos produtos solicitados")
        void deveFalharQuandoProdutoParcialmenteNaoEncontrado() {
            // CENÁRIO
            Long produtoExistente = 10L;
            Long produtoInexistente = 999L;
            VendaRequest pedido = new VendaRequest(List.of(
                    new ItemVendaRequest(produtoExistente, new BigDecimal("1.000")),
                    new ItemVendaRequest(produtoInexistente, new BigDecimal("2.000"))
            ));

            ProdutoCheckoutView prod1 = new ProdutoCheckoutView(produtoExistente, "Ração", new BigDecimal("50.00"));

            when(catalogoQueryService.buscarParaCheckout(Set.of(produtoExistente, produtoInexistente)))
                    .thenReturn(List.of(prod1));

            // AÇÃO & ASSERÇÃO
            assertThatThrownBy(() -> vendaService.iniciarVenda(pedido))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Um ou mais produtos não foram encontrados.");

            verifyNoInteractions(estoqueCommandService);
            verifyNoInteractions(vendaRepository);
        }

        @Test
        @DisplayName("Deve permitir itens com produtoId duplicado no pedido, deduplicando consulta ao catálogo e mantendo itens no comando")
        void deveIniciarVendaComItensDuplicadosNoPedido() {
            Long produtoId = 10L;
            BigDecimal qtd1 = new BigDecimal("2.000");
            BigDecimal qtd2 = new BigDecimal("3.000");

            VendaRequest pedido = new VendaRequest(List.of(
                    new ItemVendaRequest(produtoId, qtd1),
                    new ItemVendaRequest(produtoId, qtd2)
            ));

            ProdutoCheckoutView prod = new ProdutoCheckoutView(produtoId, "Ração Cães 15kg", new BigDecimal("50.00"));

            when(catalogoQueryService.buscarParaCheckout(Set.of(produtoId)))
                    .thenReturn(List.of(prod));

            Long vendaId = 200L;
            when(vendaRepository.save(any(Venda.class))).thenAnswer(invocation -> {
                Venda v = invocation.getArgument(0);
                if (v.getId() == null) {
                    ReflectionTestUtils.setField(v, "id", vendaId);
                }
                return v;
            });

            Venda vendaCriada = vendaService.iniciarVenda(pedido);

            assertThat(vendaCriada).isNotNull();
            assertThat(vendaCriada.getId()).isEqualTo(vendaId);
            assertThat(vendaCriada.getStatus()).isEqualTo(StatusVenda.AGUARDANDO_PAGAMENTO);
            assertThat(vendaCriada.getItens()).hasSize(2);
            // 2 * 50.00 + 3 * 50.00 = 100.00 + 150.00 = 250.00
            assertThat(vendaCriada.getValorTotal()).isEqualByComparingTo(new BigDecimal("250.00"));

            verify(catalogoQueryService, times(1)).buscarParaCheckout(Set.of(produtoId));

            ArgumentCaptor<ComandoEstoqueVenda> comandoCaptor = ArgumentCaptor.forClass(ComandoEstoqueVenda.class);
            verify(estoqueCommandService, times(1)).reservar(comandoCaptor.capture());

            ComandoEstoqueVenda comando = comandoCaptor.getValue();
            assertThat(comando.vendaId()).isEqualTo(vendaId);
            assertThat(comando.itens()).containsExactly(
                    new ItemComando(produtoId, qtd1),
                    new ItemComando(produtoId, qtd2)
            );

            verify(vendaRepository, times(2)).save(any(Venda.class));
        }

        @Test
        @DisplayName("Deve propagar exceção e não avançar status para AGUARDANDO_PAGAMENTO quando reserva falhar")
        void deveFalharQuandoReservaDeEstoqueFalhar() {
            Long produtoId = 10L;
            BigDecimal qtd = new BigDecimal("2.000");

            VendaRequest pedido = new VendaRequest(List.of(
                    new ItemVendaRequest(produtoId, qtd)
            ));

            ProdutoCheckoutView prod = new ProdutoCheckoutView(produtoId, "Ração Cães 15kg", new BigDecimal("50.00"));

            when(catalogoQueryService.buscarParaCheckout(Set.of(produtoId)))
                    .thenReturn(List.of(prod));

            Long vendaId = 300L;
            when(vendaRepository.save(any(Venda.class))).thenAnswer(invocation -> {
                Venda v = invocation.getArgument(0);
                if (v.getId() == null) {
                    ReflectionTestUtils.setField(v, "id", vendaId);
                }
                return v;
            });

            doThrow(new BusinessException("Estoque insuficiente para o produto: Ração Cães 15kg"))
                    .when(estoqueCommandService).reservar(any(ComandoEstoqueVenda.class));

            assertThatThrownBy(() -> vendaService.iniciarVenda(pedido))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Estoque insuficiente para o produto: Ração Cães 15kg");

            // A venda é salva apenas uma vez (na criação inicial com ID), nunca após a transição de status
            verify(vendaRepository, times(1)).save(any(Venda.class));
        }
    }

    @Nested
    @DisplayName("buscarStatusVenda(Long)")
    class BuscarStatusVenda {

        @Test
        @DisplayName("Deve retornar VendaStatusResponse quando a venda existir")
        void deveRetornarStatusQuandoVendaExistir() {
            Long vendaId = 1L;
            Venda venda = new Venda();
            ReflectionTestUtils.setField(venda, "id", vendaId);
            ReflectionTestUtils.setField(venda, "version", 2L);
            ReflectionTestUtils.setField(venda, "status", StatusVenda.AGUARDANDO_PAGAMENTO);
            ReflectionTestUtils.setField(venda, "valorTotal", new BigDecimal("150.00"));
            ReflectionTestUtils.setField(venda, "valorPago", new BigDecimal("100.00"));
            ReflectionTestUtils.setField(venda, "valorTroco", BigDecimal.ZERO);

            when(vendaRepository.findById(vendaId)).thenReturn(Optional.of(venda));

            VendaStatusResponse response = vendaService.buscarStatusVenda(vendaId);

            assertThat(response).isNotNull();
            assertThat(response.id()).isEqualTo(vendaId);
            assertThat(response.version()).isEqualTo(2L);
            assertThat(response.status()).isEqualTo(StatusVenda.AGUARDANDO_PAGAMENTO);
            assertThat(response.valorTotal()).isEqualByComparingTo(new BigDecimal("150.00"));
            assertThat(response.valorPago()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(response.valorTroco()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Deve lançar BusinessException quando a venda não existir")
        void deveFalharQuandoVendaNaoExistir() {
            Long vendaIdInexistente = 999L;
            when(vendaRepository.findById(vendaIdInexistente)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> vendaService.buscarStatusVenda(vendaIdInexistente))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Venda não encontrada para o ID: 999");
        }
    }

    @Nested
    @DisplayName("recuperarVendaAtiva()")
    class RecuperarVendaAtiva {

        @Test
        @DisplayName("Deve retornar ReciboResponse quando houver venda ativa")
        void deveRetornarReciboQuandoHouverVendaAtiva() {
            Venda venda = new Venda();
            ReflectionTestUtils.setField(venda, "id", 10L);
            venda.adicionarItem(1L, "Petisco Cão", new BigDecimal("15.00"), new BigDecimal("2"));

            when(vendaRepository.findFirstByStatusInOrderByIdDesc(List.of(StatusVenda.ABERTA, StatusVenda.AGUARDANDO_PAGAMENTO)))
                    .thenReturn(Optional.of(venda));

            ReciboResponse recibo = vendaService.recuperarVendaAtiva();

            assertThat(recibo).isNotNull();
            assertThat(recibo.id()).isEqualTo("10");
            assertThat(recibo.total()).isEqualByComparingTo(new BigDecimal("30.00"));
            assertThat(recibo.itens()).hasSize(1);
            assertThat(recibo.itens().get(0).nomeProduto()).isEqualTo("Petisco Cão");
            assertThat(recibo.itens().get(0).quantidade()).isEqualByComparingTo(new BigDecimal("2"));
            assertThat(recibo.itens().get(0).precoUnitario()).isEqualByComparingTo(new BigDecimal("15.00"));
            assertThat(recibo.itens().get(0).subtotal()).isEqualByComparingTo(new BigDecimal("30.00"));
        }

        @Test
        @DisplayName("Deve retornar null quando não houver venda ativa")
        void deveRetornarNullQuandoNaoHouverVendaAtiva() {
            when(vendaRepository.findFirstByStatusInOrderByIdDesc(List.of(StatusVenda.ABERTA, StatusVenda.AGUARDANDO_PAGAMENTO)))
                    .thenReturn(Optional.empty());

            ReciboResponse recibo = vendaService.recuperarVendaAtiva();

            assertThat(recibo).isNull();
        }
    }

    @Nested
    @DisplayName("Métodos de @Recover")
    class MetodosRecover {

        @Test
        @DisplayName("recuperarFalhaDeLock deve lançar BusinessException com mensagem explicativa ao usuário")
        void deveRecuperarFalhaDeLockComMensagemAmigavel() {
            OptimisticLockingFailureException ex = new OptimisticLockingFailureException("conflito de versão");
            VendaRequest pedido = new VendaRequest(List.of());

            assertThatThrownBy(() -> vendaService.recuperarFalhaDeLock(ex, pedido))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Alta concorrência nos itens selecionados. O estoque foi atualizado por outro caixa. Por favor, tente finalizar novamente.");
        }

        @Test
        @DisplayName("recuperarFalhaGeral deve relançar a RuntimeException recebida")
        void deveRecuperarFalhaGeralRelancandoExcecao() {
            IllegalStateException ex = new IllegalStateException("erro inesperado");
            VendaRequest pedido = new VendaRequest(List.of());

            assertThatThrownBy(() -> vendaService.recuperarFalhaGeral(ex, pedido))
                    .isSameAs(ex);
        }
    }
}
