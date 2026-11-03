package inv.checkout.domain.model;

import common.exception.BusinessException;
import inv.shared.domain.MetodoPagamento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Venda — Testes Unitários de Domínio e Máquina de Estados (F6, Q2)")
class VendaTest {

    @Nested
    @DisplayName("cancelar()")
    class CancelarTests {

        @Test
        @DisplayName("Deve cancelar venda em status ABERTA com sucesso")
        void deveCancelarVendaEmStatusAbertaComSucesso() {
            Venda venda = new Venda();
            assertThat(venda.getStatus()).isEqualTo(StatusVenda.ABERTA);

            venda.cancelar();

            assertThat(venda.getStatus()).isEqualTo(StatusVenda.CANCELADA);
        }

        @Test
        @DisplayName("Deve cancelar venda em status AGUARDANDO_PAGAMENTO com sucesso")
        void deveCancelarVendaEmStatusAguardandoPagamentoComSucesso() {
            Venda venda = new Venda();
            venda.adicionarItem(1L, "Produto Teste", new BigDecimal("50.00"), BigDecimal.ONE);
            venda.irParaPagamento();
            assertThat(venda.getStatus()).isEqualTo(StatusVenda.AGUARDANDO_PAGAMENTO);

            venda.cancelar();

            assertThat(venda.getStatus()).isEqualTo(StatusVenda.CANCELADA);
        }

        @ParameterizedTest(name = "status que não pode ser cancelado via cancelar(): {0}")
        @EnumSource(value = StatusVenda.class, names = {"CONCLUIDA", "CANCELADA", "CANCELAMENTO_SOLICITADO"})
        @DisplayName("Deve lançar BusinessException ao tentar cancelar venda em status que não pode ser cancelada")
        void deveLancarExcecaoAoTentarCancelarVendaEmStatusInvalido(StatusVenda statusInvalido) {
            Venda venda = new Venda();
            ReflectionTestUtils.setField(venda, "status", statusInvalido);

            assertThatThrownBy(venda::cancelar)
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Venda no status " + statusInvalido + " não pode ser cancelada.");
        }

        @Test
        @DisplayName("Deve lançar BusinessException de forma null-safe ao tentar cancelar venda com status nulo")
        void deveLancarExcecaoAoTentarCancelarVendaComStatusNulo() {
            Venda venda = new Venda();
            ReflectionTestUtils.setField(venda, "status", null);

            assertThatThrownBy(venda::cancelar)
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Venda no status null não pode ser cancelada.");
        }
    }

    @Nested
    @DisplayName("cancelarVendaConcluida()")
    class CancelarVendaConcluidaTests {

        @Test
        @DisplayName("Cenário 1 (Runbook E3 / F6, Q2): Deve cancelar venda concluída, estornar apenas pagamentos elegíveis e isolar pagamentos em trânsito e excedentes")
        void deveCancelarVendaConcluidaEEstornarApenasPagamentosElegiveis() {
            // Condição: Venda CONCLUIDA com total = 100.00, valorPago = 120.00, valorTroco = 20.00
            Venda venda = new Venda();
            venda.adicionarItem(10L, "Ração Super Premium", new BigDecimal("100.00"), BigDecimal.ONE);
            venda.irParaPagamento();

            // 1. Pagamento elegível: CONFIRMADO && contabilizado && !excedente
            Pagamento pag1Elegivel = new Pagamento(venda, MetodoPagamento.DINHEIRO, new BigDecimal("120.00"), UUID.randomUUID());
            pag1Elegivel.confirmar(new BigDecimal("120.00"), "DIN-120");
            venda.registrarPagamentoConfirmado(pag1Elegivel);

            venda.concluir();
            assertThat(venda.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);
            assertThat(venda.getValorTotal()).isEqualByComparingTo("100.00");
            assertThat(venda.getValorPago()).isEqualByComparingTo("120.00");
            assertThat(venda.getValorTroco()).isEqualByComparingTo("20.00");

            // 2. Pagamento em trânsito: CONFIRMADO && !contabilizado && !excedente
            Pagamento pag2EmTransito = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("30.00"), UUID.randomUUID());
            pag2EmTransito.confirmar(new BigDecimal("30.00"), "PIX-TRANSITO");
            // Não registrado na venda -> contabilizado continua false
            venda.getPagamentos().add(pag2EmTransito);

            // 3. Pagamento excedente: CONFIRMADO && contabilizado && excedente
            Pagamento pag3Excedente = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("40.00"), UUID.randomUUID());
            pag3Excedente.confirmar(new BigDecimal("40.00"), "PIX-EXCEDENTE");
            pag3Excedente.marcarComoContabilizado();
            pag3Excedente.marcarComoExcedenteParaEstorno();
            venda.getPagamentos().add(pag3Excedente);

            // 4. Pagamento que falhou: FALHOU && !contabilizado && !excedente
            Pagamento pag4Falhou = new Pagamento(venda, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("50.00"), UUID.randomUUID());
            pag4Falhou.processar();
            pag4Falhou.falhar();
            venda.getPagamentos().add(pag4Falhou);

            // Ação: Cancelamento da venda concluída
            venda.cancelarVendaConcluida();

            // Asserções:
            // 1. Venda muda para CANCELADA
            assertThat(venda.getStatus()).isEqualTo(StatusVenda.CANCELADA);

            // 2. Invariante de Simetria Contábil: valorPago e valorTroco intactos
            assertThat(venda.getValorPago()).isEqualByComparingTo("120.00");
            assertThat(venda.getValorTroco()).isEqualByComparingTo("20.00");

            // 3. Pagamento 1: estornado
            assertThat(pag1Elegivel.getStatus()).isEqualTo(StatusPagamento.ESTORNADO);
            assertThat(pag1Elegivel.isContabilizado()).isTrue();
            assertThat(pag1Elegivel.isExcedente()).isFalse();

            // 4. Pagamento 2 (em trânsito): isolado, mantém estado original
            assertThat(pag2EmTransito.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pag2EmTransito.isContabilizado()).isFalse();
            assertThat(pag2EmTransito.isExcedente()).isFalse();

            // 5. Pagamento 3 (excedente): isolado, mantém estado original
            assertThat(pag3Excedente.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pag3Excedente.isContabilizado()).isTrue();
            assertThat(pag3Excedente.isExcedente()).isTrue();

            // 6. Pagamento 4 (falhou): isolado, mantém estado original
            assertThat(pag4Falhou.getStatus()).isEqualTo(StatusPagamento.FALHOU);
            assertThat(pag4Falhou.isContabilizado()).isFalse();
            assertThat(pag4Falhou.isExcedente()).isFalse();
        }

        @Test
        @DisplayName("Deve cancelar venda concluída com múltiplos pagamentos elegíveis estornando todos eles")
        void deveCancelarVendaConcluidaComMultiplosPagamentosElegiveis() {
            Venda venda = new Venda();
            venda.adicionarItem(1L, "Item 1", new BigDecimal("50.00"), BigDecimal.ONE);
            venda.adicionarItem(2L, "Item 2", new BigDecimal("50.00"), BigDecimal.ONE);
            venda.irParaPagamento();

            Pagamento pag1 = new Pagamento(venda, MetodoPagamento.DINHEIRO, new BigDecimal("50.00"), UUID.randomUUID());
            pag1.confirmar(new BigDecimal("50.00"), "DIN-50");
            venda.registrarPagamentoConfirmado(pag1);

            Pagamento pag2 = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("50.00"), UUID.randomUUID());
            pag2.confirmar(new BigDecimal("50.00"), "PIX-50");
            venda.registrarPagamentoConfirmado(pag2);

            venda.concluir();

            venda.cancelarVendaConcluida();

            assertThat(venda.getStatus()).isEqualTo(StatusVenda.CANCELADA);
            assertThat(pag1.getStatus()).isEqualTo(StatusPagamento.ESTORNADO);
            assertThat(pag2.getStatus()).isEqualTo(StatusPagamento.ESTORNADO);
            assertThat(venda.getValorPago()).isEqualByComparingTo("100.00");
            assertThat(venda.getValorTroco()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("Deve isolar pagamentos em trânsito nos status PENDENTE e PROCESSANDO sem estorná-los")
        void deveCancelarVendaConcluidaIsolandoPagamentosEmProcessamentoEPendentes() {
            Venda venda = new Venda();
            venda.adicionarItem(1L, "Item", new BigDecimal("100.00"), BigDecimal.ONE);
            venda.irParaPagamento();

            Pagamento pagConfirmado = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("100.00"), UUID.randomUUID());
            pagConfirmado.confirmar(new BigDecimal("100.00"), "PIX-OK");
            venda.registrarPagamentoConfirmado(pagConfirmado);
            venda.concluir();

            Pagamento pagPendente = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("20.00"), UUID.randomUUID());
            venda.getPagamentos().add(pagPendente);

            Pagamento pagProcessando = new Pagamento(venda, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("30.00"), UUID.randomUUID());
            pagProcessando.processar();
            venda.getPagamentos().add(pagProcessando);

            venda.cancelarVendaConcluida();

            assertThat(venda.getStatus()).isEqualTo(StatusVenda.CANCELADA);
            assertThat(pagConfirmado.getStatus()).isEqualTo(StatusPagamento.ESTORNADO);
            assertThat(pagPendente.getStatus()).isEqualTo(StatusPagamento.PENDENTE);
            assertThat(pagProcessando.getStatus()).isEqualTo(StatusPagamento.PROCESSANDO);
        }

        @Test
        @DisplayName("Deve cancelar venda concluída com lista de pagamentos vazia sem erro")
        void deveCancelarVendaConcluidaComListaDePagamentosVazia() {
            Venda venda = new Venda();
            ReflectionTestUtils.setField(venda, "status", StatusVenda.CONCLUIDA);

            venda.cancelarVendaConcluida();

            assertThat(venda.getStatus()).isEqualTo(StatusVenda.CANCELADA);
        }

        @Test
        @DisplayName("Deve ignorar elementos nulos na lista de pagamentos sem lançar NullPointerException")
        void deveIgnorarElementosNulosNaListaDePagamentos() {
            Venda venda = new Venda();
            venda.adicionarItem(1L, "Item", new BigDecimal("50.00"), BigDecimal.ONE);
            venda.irParaPagamento();

            Pagamento pag = new Pagamento(venda, MetodoPagamento.DINHEIRO, new BigDecimal("50.00"), UUID.randomUUID());
            pag.confirmar(new BigDecimal("50.00"), "DIN-50");
            venda.registrarPagamentoConfirmado(pag);
            venda.concluir();

            venda.getPagamentos().add(null);

            venda.cancelarVendaConcluida();

            assertThat(venda.getStatus()).isEqualTo(StatusVenda.CANCELADA);
            assertThat(pag.getStatus()).isEqualTo(StatusPagamento.ESTORNADO);
        }

        @ParameterizedTest(name = "status inválido para cancelarVendaConcluida: {0}")
        @EnumSource(value = StatusVenda.class, names = {"ABERTA", "AGUARDANDO_PAGAMENTO", "CANCELADA", "CANCELAMENTO_SOLICITADO"})
        @DisplayName("Deve lançar BusinessException ao tentar executar cancelarVendaConcluida() em vendas que não estão CONCLUIDA")
        void deveLancarExcecaoAoTentarCancelarVendaConcluidaEmStatusInvalido(StatusVenda statusNaoConcluida) {
            Venda venda = new Venda();
            ReflectionTestUtils.setField(venda, "status", statusNaoConcluida);

            assertThatThrownBy(venda::cancelarVendaConcluida)
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Apenas vendas no status CONCLUIDA podem ser canceladas via estorno. Status atual: " + statusNaoConcluida);
        }
    }
}
