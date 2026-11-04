package inv.service;

import common.exception.BusinessException;
import common.exception.ResourceNotFoundException;
import inv.checkout.domain.model.ItemVenda;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.messaging.event.VendaAtualizadaEvent;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.checkout.infrastructure.web.dto.VendaStatusResponse;
import inv.checkout.usecase.CancelamentoVendaConflitoException;
import inv.checkout.usecase.CancelamentoVendaDesabilitadoException;
import inv.checkout.usecase.VendaService;
import inv.inventory.api.ComandoEstoqueVenda;
import inv.inventory.api.EstoqueCommandService;
import inv.inventory.api.ItemComando;
import inv.shared.event.VendaEstornadaEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VendaService — Testes de Cancelamento (Expirada e Concluída)")
class VendaServiceCancelamentoTest {

    @Mock private VendaRepository vendaRepository;
    @Mock private EstoqueCommandService estoqueCommandService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private VendaService vendaService;

    @Nested
    @DisplayName("cancelarVendaExpirada(Long)")
    class CancelarVendaExpiradaTests {

        @Test
        @DisplayName("DEVE estornar reservas de estoque de todos os itens e cancelar a venda")
        void deveCancelarVendaEEstornarEstoque() {
            // CENÁRIO
            Long vendaId = 100L;

            // Venda (Spy)
            Venda vendaSpy = spy(new Venda());
            doReturn(vendaId).when(vendaSpy).getId();

            // Itens da Venda (Mocks)
            ItemVenda item1 = mock(ItemVenda.class);
            when(item1.getProdutoId()).thenReturn(1L);
            when(item1.getQuantidade()).thenReturn(new BigDecimal("5"));

            ItemVenda item2 = mock(ItemVenda.class);
            when(item2.getProdutoId()).thenReturn(2L);
            when(item2.getQuantidade()).thenReturn(new BigDecimal("3"));

            doReturn(List.of(item1, item2)).when(vendaSpy).getItens();

            when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(vendaSpy));

            // AÇÃO
            vendaService.cancelarVendaExpirada(vendaId);

            // VERIFICAÇÃO 1: Mudança de estado da entidade
            verify(vendaSpy, times(1)).cancelar();
            verify(vendaRepository, times(1)).save(vendaSpy);

            // VERIFICAÇÃO 2: Contrato de estorno de reservas em lote via EstoqueCommandService
            ArgumentCaptor<ComandoEstoqueVenda> comandoCaptor = ArgumentCaptor.forClass(ComandoEstoqueVenda.class);
            verify(estoqueCommandService, times(1)).estornarReserva(comandoCaptor.capture());

            ComandoEstoqueVenda comando = comandoCaptor.getValue();
            assertThat(comando.vendaId()).isEqualTo(vendaId);
            assertThat(comando.itens()).containsExactly(
                    new ItemComando(1L, new BigDecimal("5")),
                    new ItemComando(2L, new BigDecimal("3"))
            );
        }

        @Test
        @DisplayName("DEVE falhar ao tentar cancelar uma venda inexistente")
        void deveFalharAoCancelarVendaInexistente() {
            when(vendaRepository.findByIdWithLock(any())).thenReturn(Optional.empty());

            assertThrows(BusinessException.class, () -> vendaService.cancelarVendaExpirada(999L));

            verifyNoInteractions(estoqueCommandService);
            verify(vendaRepository, never()).save(any());
        }

        @Test
        @DisplayName("F2/Q10 — cancelarVendaExpirada em venda já CANCELADA deve ser idempotente e não estornar estoque")
        void cancelarVendaJaCanceladaNaoDeveEstornarEstoqueNovamente() {
            Long vendaId = 100L;
            Venda venda = new Venda();
            venda.cancelar();
            when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));

            vendaService.cancelarVendaExpirada(vendaId);

            verify(vendaRepository, never()).save(any());
            verifyNoInteractions(estoqueCommandService);
        }

        @Test
        @DisplayName("F2/Q10 — cancelarVendaExpirada deve rejeitar venda CONCLUIDA ou CANCELAMENTO_SOLICITADO sem tocar no estoque")
        void naoDeveCancelarVendaExpiradaSeJaEstiverConcluida() {
            Long vendaId = 100L;

            for (StatusVenda statusInelegivel : List.of(StatusVenda.CONCLUIDA, StatusVenda.CANCELAMENTO_SOLICITADO)) {
                Venda venda = new Venda();
                ReflectionTestUtils.setField(venda, "status", statusInelegivel);
                when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));

                assertThatThrownBy(() -> vendaService.cancelarVendaExpirada(vendaId))
                        .isInstanceOf(BusinessException.class)
                        .hasMessage("Venda #" + vendaId + " não é elegível para cancelamento por expiração no status: " + statusInelegivel);

                verifyNoInteractions(estoqueCommandService);
                verify(vendaRepository, never()).save(any());
            }
        }
    }

    @Nested
    @DisplayName("cancelarVendaConcluida(Long) — Cenário 2 (F6, Q2, Q6)")
    class CancelarVendaConcluidaTests {

        @Test
        @DisplayName("Cenário 2 — Deve cancelar venda CONCLUIDA com troco, devolver estoque e publicar eventos com chave determinística e valor líquido")
        void deveCancelarVendaConcluidaComTrocoEDevolverEstoque() {
            Long vendaId = 42L;

            Venda venda = spy(new Venda());
            ReflectionTestUtils.setField(venda, "id", vendaId);
            ReflectionTestUtils.setField(venda, "version", 1L);
            ReflectionTestUtils.setField(venda, "status", StatusVenda.CONCLUIDA);
            ReflectionTestUtils.setField(venda, "valorTotal", new BigDecimal("100.00"));
            ReflectionTestUtils.setField(venda, "valorPago", new BigDecimal("120.00"));
            ReflectionTestUtils.setField(venda, "valorTroco", new BigDecimal("20.00"));

            ItemVenda item1 = mock(ItemVenda.class);
            when(item1.getProdutoId()).thenReturn(10L);
            when(item1.getQuantidade()).thenReturn(new BigDecimal("2"));

            ItemVenda item2 = mock(ItemVenda.class);
            when(item2.getProdutoId()).thenReturn(20L);
            when(item2.getQuantidade()).thenReturn(new BigDecimal("1"));

            doReturn(List.of(item1, item2)).when(venda).getItens();

            when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));
            when(vendaRepository.save(venda)).thenReturn(venda);

            // AÇÃO
            VendaStatusResponse response = vendaService.cancelarVendaConcluida(vendaId);

            // VERIFICAÇÕES
            assertThat(response).isNotNull();
            assertThat(response.id()).isEqualTo(vendaId);
            assertThat(response.status()).isEqualTo(StatusVenda.CANCELADA);

            verify(venda).cancelarVendaConcluida();
            verify(vendaRepository).save(venda);

            // Devolução de estoque
            ArgumentCaptor<ComandoEstoqueVenda> comandoCaptor = ArgumentCaptor.forClass(ComandoEstoqueVenda.class);
            verify(estoqueCommandService).devolverEstoque(comandoCaptor.capture());
            ComandoEstoqueVenda comando = comandoCaptor.getValue();
            assertThat(comando.vendaId()).isEqualTo(vendaId);
            assertThat(comando.itens()).containsExactly(
                    new ItemComando(10L, new BigDecimal("2")),
                    new ItemComando(20L, new BigDecimal("1"))
            );

            // Evento VendaEstornadaEvent com chave determinística e valor líquido
            UUID uuidEsperado = UUID.nameUUIDFromBytes(("ESTORNO:" + vendaId).getBytes(StandardCharsets.UTF_8));
            ArgumentCaptor<VendaEstornadaEvent> estornadaCaptor = ArgumentCaptor.forClass(VendaEstornadaEvent.class);
            verify(eventPublisher).publishEvent(estornadaCaptor.capture());

            VendaEstornadaEvent eventoEstornado = estornadaCaptor.getValue();
            assertThat(eventoEstornado.vendaId()).isEqualTo(vendaId);
            assertThat(eventoEstornado.uuidReferenciaEstorno()).isEqualTo(uuidEsperado);
            assertThat(eventoEstornado.valorEstorno()).isEqualByComparingTo(new BigDecimal("100.00"));

            // Evento VendaAtualizadaEvent para notificação
            ArgumentCaptor<VendaAtualizadaEvent> atualizadaCaptor = ArgumentCaptor.forClass(VendaAtualizadaEvent.class);
            verify(eventPublisher).publishEvent(atualizadaCaptor.capture());

            VendaAtualizadaEvent eventoAtualizado = atualizadaCaptor.getValue();
            assertThat(eventoAtualizado.vendaId()).isEqualTo(vendaId);
            assertThat(eventoAtualizado.snapshot().status()).isEqualTo(StatusVenda.CANCELADA);
        }

        @Test
        @DisplayName("Deve cancelar venda CONCLUIDA sem troco (troco null) calculando valorEstorno = valorPago")
        void deveCancelarVendaConcluidaSemTroco() {
            Long vendaId = 55L;

            Venda venda = spy(new Venda());
            ReflectionTestUtils.setField(venda, "id", vendaId);
            ReflectionTestUtils.setField(venda, "status", StatusVenda.CONCLUIDA);
            ReflectionTestUtils.setField(venda, "valorTotal", new BigDecimal("50.00"));
            ReflectionTestUtils.setField(venda, "valorPago", new BigDecimal("50.00"));
            ReflectionTestUtils.setField(venda, "valorTroco", null);
            doReturn(List.of()).when(venda).getItens();

            when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));
            when(vendaRepository.save(venda)).thenReturn(venda);

            VendaStatusResponse response = vendaService.cancelarVendaConcluida(vendaId);

            assertThat(response.status()).isEqualTo(StatusVenda.CANCELADA);

            ArgumentCaptor<VendaEstornadaEvent> estornadaCaptor = ArgumentCaptor.forClass(VendaEstornadaEvent.class);
            verify(eventPublisher).publishEvent(estornadaCaptor.capture());
            assertThat(estornadaCaptor.getValue().valorEstorno()).isEqualByComparingTo(new BigDecimal("50.00"));
        }

        @ParameterizedTest(name = "Status inválido: {0}")
        @EnumSource(value = StatusVenda.class, names = {"ABERTA", "AGUARDANDO_PAGAMENTO", "CANCELADA", "CANCELAMENTO_SOLICITADO"})
        @DisplayName("Cenário 2 — Deve rejeitar cancelamento com 409 quando status for diferente de CONCLUIDA sem tocar em estoque ou eventos")
        void deveLancarCancelamentoVendaConflitoExceptionParaStatusNaoConcluida(StatusVenda statusInvalido) {
            Long vendaId = 77L;

            Venda venda = new Venda();
            ReflectionTestUtils.setField(venda, "id", vendaId);
            ReflectionTestUtils.setField(venda, "status", statusInvalido);

            when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));

            assertThatThrownBy(() -> vendaService.cancelarVendaConcluida(vendaId))
                    .isInstanceOf(CancelamentoVendaConflitoException.class)
                    .hasMessage("Venda #" + vendaId + " não pode ser cancelada pois está no status: " + statusInvalido);

            verifyNoInteractions(estoqueCommandService, eventPublisher);
            verify(vendaRepository, never()).save(any());
        }

        @Test
        @DisplayName("Cenário 2 — Deve lançar ResourceNotFoundException quando venda não for encontrada sem tocar em estoque ou eventos")
        void deveLancarResourceNotFoundExceptionQuandoVendaNaoEncontrada() {
            Long vendaIdInexistente = 999L;
            when(vendaRepository.findByIdWithLock(vendaIdInexistente)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> vendaService.cancelarVendaConcluida(vendaIdInexistente))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Venda não encontrada: " + vendaIdInexistente);

            verifyNoInteractions(estoqueCommandService, eventPublisher);
            verify(vendaRepository, never()).save(any());
        }

        @Test
        @DisplayName("Cenário 2 — Deve lançar CancelamentoVendaDesabilitadoException (503) quando feature flag estiver desabilitada sem consultar banco")
        void deveLancarCancelamentoVendaDesabilitadoExceptionQuandoFeatureFlagDesabilitada() {
            Long vendaId = 42L;
            vendaService.setCancelamentoConcluidoHabilitado(false);

            assertThatThrownBy(() -> vendaService.cancelarVendaConcluida(vendaId))
                    .isInstanceOf(CancelamentoVendaDesabilitadoException.class)
                    .hasMessage("Cancelamento de venda concluída temporariamente desabilitado");

            verifyNoInteractions(vendaRepository, estoqueCommandService, eventPublisher);
        }

        @Test
        @DisplayName("Deve propagar exceção e não salvar venda nem publicar eventos quando devolução de estoque falhar")
        void devePropagarExcecaoQuandoDevolucaoEstoqueFalhar() {
            Long vendaId = 88L;
            Venda venda = spy(new Venda());
            ReflectionTestUtils.setField(venda, "id", vendaId);
            ReflectionTestUtils.setField(venda, "status", StatusVenda.CONCLUIDA);
            ReflectionTestUtils.setField(venda, "valorTotal", new BigDecimal("100.00"));
            ReflectionTestUtils.setField(venda, "valorPago", new BigDecimal("100.00"));
            doReturn(List.of()).when(venda).getItens();

            when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));
            doThrow(new BusinessException("Falha na devolução física"))
                    .when(estoqueCommandService).devolverEstoque(any());

            assertThatThrownBy(() -> vendaService.cancelarVendaConcluida(vendaId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Falha na devolução física");

            verify(vendaRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Deve propagar exceção e não publicar eventos quando save da venda falhar")
        void devePropagarExcecaoQuandoSaveFalhar() {
            Long vendaId = 89L;
            Venda venda = spy(new Venda());
            ReflectionTestUtils.setField(venda, "id", vendaId);
            ReflectionTestUtils.setField(venda, "status", StatusVenda.CONCLUIDA);
            ReflectionTestUtils.setField(venda, "valorTotal", new BigDecimal("100.00"));
            ReflectionTestUtils.setField(venda, "valorPago", new BigDecimal("100.00"));
            doReturn(List.of()).when(venda).getItens();

            when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));
            when(vendaRepository.save(venda)).thenThrow(new RuntimeException("Erro de I/O no banco"));

            assertThatThrownBy(() -> vendaService.cancelarVendaConcluida(vendaId))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Erro de I/O no banco");

            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("Deve cancelar venda defensivamente quando getItens() for null sem lançar NPE")
        void deveCancelarVendaQuandoItensForNull() {
            Long vendaId = 90L;
            Venda venda = spy(new Venda());
            ReflectionTestUtils.setField(venda, "id", vendaId);
            ReflectionTestUtils.setField(venda, "status", StatusVenda.CONCLUIDA);
            ReflectionTestUtils.setField(venda, "valorTotal", new BigDecimal("100.00"));
            ReflectionTestUtils.setField(venda, "valorPago", new BigDecimal("100.00"));
            doReturn(null).when(venda).getItens();

            when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));
            when(vendaRepository.save(venda)).thenReturn(venda);

            VendaStatusResponse response = vendaService.cancelarVendaConcluida(vendaId);

            assertThat(response.status()).isEqualTo(StatusVenda.CANCELADA);
            ArgumentCaptor<ComandoEstoqueVenda> captor = ArgumentCaptor.forClass(ComandoEstoqueVenda.class);
            verify(estoqueCommandService).devolverEstoque(captor.capture());
            assertThat(captor.getValue().itens()).isEmpty();
        }

        @Test
        @DisplayName("Deve calcular valorEstorno zero quando valorPago for igual ao valorTroco")
        void deveCalcularValorEstornoZeroQuandoPagoIgualTroco() {
            Long vendaId = 91L;
            Venda venda = spy(new Venda());
            ReflectionTestUtils.setField(venda, "id", vendaId);
            ReflectionTestUtils.setField(venda, "status", StatusVenda.CONCLUIDA);
            ReflectionTestUtils.setField(venda, "valorTotal", BigDecimal.ZERO);
            ReflectionTestUtils.setField(venda, "valorPago", new BigDecimal("20.00"));
            ReflectionTestUtils.setField(venda, "valorTroco", new BigDecimal("20.00"));
            doReturn(List.of()).when(venda).getItens();

            when(vendaRepository.findByIdWithLock(vendaId)).thenReturn(Optional.of(venda));
            when(vendaRepository.save(venda)).thenReturn(venda);

            VendaStatusResponse response = vendaService.cancelarVendaConcluida(vendaId);

            assertThat(response.status()).isEqualTo(StatusVenda.CANCELADA);

            ArgumentCaptor<VendaEstornadaEvent> estornadaCaptor = ArgumentCaptor.forClass(VendaEstornadaEvent.class);
            verify(eventPublisher).publishEvent(estornadaCaptor.capture());
            assertThat(estornadaCaptor.getValue().valorEstorno()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }
}
