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
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Pagamento — Testes Unitários de Domínio e Máquina de Estados (F3)")
class PagamentoTest {

    @Nested
    @DisplayName("confirmar(BigDecimal, String) : boolean")
    class ConfirmarTests {

        @Test
        @DisplayName("Deve confirmar com sucesso (retornar true) quando CARTAO_CREDITO estiver em PROCESSANDO")
        void deveConfirmarCartaoCreditoEmProcessando() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("100.00"), UUID.randomUUID());
            pagamento.processar();

            boolean resultado = pagamento.confirmar(new BigDecimal("100.00"), "NSU-CC-1");

            assertThat(resultado).isTrue();
            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo("100.00");
            assertThat(pagamento.getNsuTransacao()).isEqualTo("NSU-CC-1");
            assertThat(pagamento.getDataHoraPagamento()).isNotNull();
        }

        @Test
        @DisplayName("Deve retornar false e não alterar dados quando pagamento já estiver CONFIRMADO (idempotência)")
        void deveRetornarFalseQuandoJaConfirmado() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("100.00"), UUID.randomUUID());
            pagamento.processar();
            pagamento.confirmar(new BigDecimal("100.00"), "NSU-CC-1");

            LocalDateTime primeiraData = pagamento.getDataHoraPagamento();

            boolean resultado = pagamento.confirmar(new BigDecimal("200.00"), "NSU-NOVO-IGNORADO");

            assertThat(resultado).isFalse();
            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo("100.00");
            assertThat(pagamento.getNsuTransacao()).isEqualTo("NSU-CC-1");
            assertThat(pagamento.getDataHoraPagamento()).isEqualTo(primeiraData);
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar confirmar CARTAO_CREDITO em status PENDENTE")
        void deveLancarExcecaoAoConfirmarCartaoCreditoEmPendente() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("100.00"), UUID.randomUUID());

            assertThatThrownBy(() -> pagamento.confirmar(new BigDecimal("100.00"), "NSU-CC-1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento exige processamento prévio.");
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar confirmar CARTAO_CREDITO em status FALHOU")
        void deveLancarExcecaoAoConfirmarCartaoCreditoEmFalhou() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("100.00"), UUID.randomUUID());
            ReflectionTestUtils.setField(pagamento, "status", StatusPagamento.FALHOU);

            assertThatThrownBy(() -> pagamento.confirmar(new BigDecimal("100.00"), "NSU-CC-1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento exige processamento prévio.");
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar confirmar CARTAO_CREDITO em status ESTORNADO")
        void deveLancarExcecaoAoConfirmarCartaoCreditoEmEstornado() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("100.00"), UUID.randomUUID());
            ReflectionTestUtils.setField(pagamento, "status", StatusPagamento.ESTORNADO);

            assertThatThrownBy(() -> pagamento.confirmar(new BigDecimal("100.00"), "NSU-CC-1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento exige processamento prévio.");
        }

        @Test
        @DisplayName("Deve confirmar com sucesso (retornar true) quando PIX estiver em PENDENTE")
        void deveConfirmarPixEmPendente() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("50.00"), UUID.randomUUID());

            boolean resultado = pagamento.confirmar(new BigDecimal("50.00"), "PIX-123");

            assertThat(resultado).isTrue();
            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo("50.00");
            assertThat(pagamento.getNsuTransacao()).isEqualTo("PIX-123");
            assertThat(pagamento.getDataHoraPagamento()).isNotNull();
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar confirmar PIX em status FALHOU")
        void deveLancarExcecaoAoConfirmarPixEmFalhou() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("50.00"), UUID.randomUUID());
            ReflectionTestUtils.setField(pagamento, "status", StatusPagamento.FALHOU);

            assertThatThrownBy(() -> pagamento.confirmar(new BigDecimal("50.00"), "PIX-123"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento com status FALHOU não pode ser confirmado.");
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar confirmar PIX em status ESTORNADO")
        void deveLancarExcecaoAoConfirmarPixEmEstornado() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("50.00"), UUID.randomUUID());
            ReflectionTestUtils.setField(pagamento, "status", StatusPagamento.ESTORNADO);

            assertThatThrownBy(() -> pagamento.confirmar(new BigDecimal("50.00"), "PIX-123"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento com status ESTORNADO não pode ser confirmado.");
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar confirmar DINHEIRO em status FALHOU")
        void deveLancarExcecaoAoConfirmarDinheiroEmFalhou() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.DINHEIRO, new BigDecimal("80.00"), UUID.randomUUID());
            ReflectionTestUtils.setField(pagamento, "status", StatusPagamento.FALHOU);

            assertThatThrownBy(() -> pagamento.confirmar(new BigDecimal("80.00"), "DIN-123"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento com status FALHOU não pode ser confirmado.");
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar confirmar DINHEIRO em status ESTORNADO")
        void deveLancarExcecaoAoConfirmarDinheiroEmEstornado() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.DINHEIRO, new BigDecimal("80.00"), UUID.randomUUID());
            ReflectionTestUtils.setField(pagamento, "status", StatusPagamento.ESTORNADO);

            assertThatThrownBy(() -> pagamento.confirmar(new BigDecimal("80.00"), "DIN-123"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento com status ESTORNADO não pode ser confirmado.");
        }

        @Test
        @DisplayName("Deve confirmar com sucesso (retornar true) quando CARTAO_DEBITO estiver em PROCESSANDO")
        void deveConfirmarCartaoDebitoEmProcessando() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.CARTAO_DEBITO, new BigDecimal("60.00"), UUID.randomUUID());
            pagamento.processar();

            boolean resultado = pagamento.confirmar(new BigDecimal("60.00"), "NSU-DEB-1");

            assertThat(resultado).isTrue();
            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo("60.00");
            assertThat(pagamento.getNsuTransacao()).isEqualTo("NSU-DEB-1");
            assertThat(pagamento.getDataHoraPagamento()).isNotNull();
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar confirmar CARTAO_DEBITO em status PENDENTE")
        void deveLancarExcecaoAoConfirmarCartaoDebitoEmPendente() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.CARTAO_DEBITO, new BigDecimal("60.00"), UUID.randomUUID());

            assertThatThrownBy(() -> pagamento.confirmar(new BigDecimal("60.00"), "NSU-DEB-1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento exige processamento prévio.");
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar confirmar CARTAO_DEBITO em status FALHOU")
        void deveLancarExcecaoAoConfirmarCartaoDebitoEmFalhou() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.CARTAO_DEBITO, new BigDecimal("60.00"), UUID.randomUUID());
            ReflectionTestUtils.setField(pagamento, "status", StatusPagamento.FALHOU);

            assertThatThrownBy(() -> pagamento.confirmar(new BigDecimal("60.00"), "NSU-DEB-1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento exige processamento prévio.");
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar confirmar CARTAO_DEBITO em status ESTORNADO")
        void deveLancarExcecaoAoConfirmarCartaoDebitoEmEstornado() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.CARTAO_DEBITO, new BigDecimal("60.00"), UUID.randomUUID());
            ReflectionTestUtils.setField(pagamento, "status", StatusPagamento.ESTORNADO);

            assertThatThrownBy(() -> pagamento.confirmar(new BigDecimal("60.00"), "NSU-DEB-1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento exige processamento prévio.");
        }

        @Test
        @DisplayName("Deve confirmar com sucesso (retornar true) quando DINHEIRO estiver em PENDENTE")
        void deveConfirmarDinheiroEmPendente() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.DINHEIRO, new BigDecimal("80.00"), UUID.randomUUID());

            boolean resultado = pagamento.confirmar(new BigDecimal("80.00"), "DIN-123");

            assertThat(resultado).isTrue();
            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo("80.00");
            assertThat(pagamento.getNsuTransacao()).isEqualTo("DIN-123");
            assertThat(pagamento.getDataHoraPagamento()).isNotNull();
        }

        @Test
        @DisplayName("Deve retornar false e não alterar dados quando DINHEIRO já estiver CONFIRMADO (idempotência)")
        void deveRetornarFalseQuandoDinheiroJaConfirmado() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.DINHEIRO, new BigDecimal("80.00"), UUID.randomUUID());
            pagamento.confirmar(new BigDecimal("80.00"), "DIN-123");

            LocalDateTime primeiraData = pagamento.getDataHoraPagamento();

            boolean resultado = pagamento.confirmar(new BigDecimal("90.00"), "DIN-NOVO-IGNORADO");

            assertThat(resultado).isFalse();
            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo("80.00");
            assertThat(pagamento.getNsuTransacao()).isEqualTo("DIN-123");
            assertThat(pagamento.getDataHoraPagamento()).isEqualTo(primeiraData);
        }

        @Test
        @DisplayName("Deve retornar false e não alterar dados quando PIX já estiver CONFIRMADO (idempotência)")
        void deveRetornarFalseQuandoPixJaConfirmado() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("50.00"), UUID.randomUUID());
            pagamento.confirmar(new BigDecimal("50.00"), "PIX-123");

            LocalDateTime primeiraData = pagamento.getDataHoraPagamento();

            boolean resultado = pagamento.confirmar(new BigDecimal("100.00"), "PIX-NOVO-IGNORADO");

            assertThat(resultado).isFalse();
            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo("50.00");
            assertThat(pagamento.getNsuTransacao()).isEqualTo("PIX-123");
            assertThat(pagamento.getDataHoraPagamento()).isEqualTo(primeiraData);
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar confirmar PIX em status PROCESSANDO")
        void deveLancarExcecaoAoConfirmarPixEmProcessando() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("50.00"), UUID.randomUUID());
            ReflectionTestUtils.setField(pagamento, "status", StatusPagamento.PROCESSANDO);

            assertThatThrownBy(() -> pagamento.confirmar(new BigDecimal("50.00"), "PIX-123"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento com status PROCESSANDO não pode ser confirmado.");
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar confirmar DINHEIRO em status PROCESSANDO")
        void deveLancarExcecaoAoConfirmarDinheiroEmProcessando() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.DINHEIRO, new BigDecimal("80.00"), UUID.randomUUID());
            ReflectionTestUtils.setField(pagamento, "status", StatusPagamento.PROCESSANDO);

            assertThatThrownBy(() -> pagamento.confirmar(new BigDecimal("80.00"), "DIN-123"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Pagamento com status PROCESSANDO não pode ser confirmado.");
        }
    }

    @Nested
    @DisplayName("processar() e falhar()")
    class ProcessarEFalharTests {

        @Test
        @DisplayName("Deve processar pagamento pendente com sucesso")
        void deveProcessarPagamentoPendente() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("100.00"), UUID.randomUUID());

            pagamento.processar();

            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.PROCESSANDO);
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar processar pagamento não pendente")
        void deveLancarExcecaoAoProcessarNaoPendente() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("100.00"), UUID.randomUUID());
            pagamento.processar();

            assertThatThrownBy(pagamento::processar)
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Apenas pagamentos pendentes podem ser processados.");
        }

        @Test
        @DisplayName("Deve falhar pagamento em processamento com sucesso")
        void deveFalharPagamentoEmProcessamento() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("100.00"), UUID.randomUUID());
            pagamento.processar();

            pagamento.falhar();

            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.FALHOU);
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar falhar pagamento que não está em processamento")
        void deveLancarExcecaoAoFalharNaoProcessando() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("100.00"), UUID.randomUUID());

            assertThatThrownBy(pagamento::falhar)
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida para falha de pagamento.");
        }
    }

    @Nested
    @DisplayName("estornar()")
    class EstornarTests {

        @Test
        @DisplayName("Deve estornar com sucesso pagamento CONFIRMADO, contabilizado e não excedente")
        void deveEstornarPagamentoConfirmadoEContabilizadoComSucesso() {
            Venda venda = new Venda();
            UUID uuid = UUID.randomUUID();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("100.00"), uuid);
            pagamento.confirmar(new BigDecimal("100.00"), "PIX-1");
            pagamento.marcarComoContabilizado();

            pagamento.estornar();

            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.ESTORNADO);
            assertThat(pagamento.getValorEsperado()).isEqualByComparingTo("100.00");
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo("100.00");
            assertThat(pagamento.getNsuTransacao()).isEqualTo("PIX-1");
            assertThat(pagamento.getUuidReferencia()).isEqualTo(uuid);
            assertThat(pagamento.getVenda()).isSameAs(venda);
        }

        @ParameterizedTest(name = "status inválido: {0}")
        @EnumSource(value = StatusPagamento.class, names = {"PENDENTE", "PROCESSANDO", "FALHOU", "ESTORNADO"})
        @DisplayName("Deve lançar BusinessException ao tentar estornar pagamento que não está CONFIRMADO")
        void deveLancarExcecaoAoTentarEstornarPagamentoNaoConfirmado(StatusPagamento statusNaoConfirmado) {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("100.00"), UUID.randomUUID());
            ReflectionTestUtils.setField(pagamento, "status", statusNaoConfirmado);
            pagamento.marcarComoContabilizado();

            assertThatThrownBy(pagamento::estornar)
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Apenas pagamentos confirmados, contabilizados e não excedentes podem ser estornados.");
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar estornar pagamento CONFIRMADO mas não contabilizado")
        void deveLancarExcecaoAoTentarEstornarPagamentoNaoContabilizado() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("100.00"), UUID.randomUUID());
            pagamento.confirmar(new BigDecimal("100.00"), "PIX-1");
            // contabilizado continua false por padrão

            assertThatThrownBy(pagamento::estornar)
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Apenas pagamentos confirmados, contabilizados e não excedentes podem ser estornados.");
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar estornar pagamento CONFIRMADO e contabilizado porém excedente")
        void deveLancarExcecaoAoTentarEstornarPagamentoExcedente() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("100.00"), UUID.randomUUID());
            pagamento.confirmar(new BigDecimal("100.00"), "PIX-1");
            pagamento.marcarComoContabilizado();
            pagamento.marcarComoExcedenteParaEstorno();

            assertThatThrownBy(pagamento::estornar)
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Apenas pagamentos confirmados, contabilizados e não excedentes podem ser estornados.");
        }

        @Test
        @DisplayName("Deve lançar BusinessException ao tentar estornar pagamento CONFIRMADO porém não contabilizado e excedente")
        void deveLancarExcecaoAoTentarEstornarPagamentoNaoContabilizadoEExcedente() {
            Venda venda = new Venda();
            Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("100.00"), UUID.randomUUID());
            pagamento.confirmar(new BigDecimal("100.00"), "PIX-1");
            pagamento.marcarComoExcedenteParaEstorno();

            assertThatThrownBy(pagamento::estornar)
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Transição inválida: Apenas pagamentos confirmados, contabilizados e não excedentes podem ser estornados.");
        }
    }
}
