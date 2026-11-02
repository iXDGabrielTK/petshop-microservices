package inv.checkout.domain.model;

import common.exception.BusinessException;
import inv.shared.domain.MetodoPagamento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Venda / Pagamento — Testes de Deduplicação e Invariantes JPA-Safe (DEBT-ARCH-003 / E1 - F4, Q8)")
class VendaPagamentoDeduplicacaoTest {

    @Test
    @DisplayName("Cenário 1 (E1/F4) — Deduplicação sob @OneToMany: 1ª chamada soma e marca contabilizado, 2ª chamada é no-op, 2º pagamento soma")
    void deveDeduplicarPagamentoRepetidoEProcessarMultiplosPagamentosDistintos() {
        // CONDIÇÃO: Venda com total 100.00 em AGUARDANDO_PAGAMENTO
        Venda venda = criarVendaAguardandoPagamento(1L, new BigDecimal("100.00"));

        UUID uuidRef1 = UUID.randomUUID();
        Pagamento pag1 = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("60.00"), uuidRef1);
        pag1.confirmar(new BigDecimal("60.00"), "NSU-PIX-1");

        // Simula hidratação JPA onde o pagamento já consta na coleção da venda antes da contabilização
        venda.getPagamentos().add(pag1);
        assertThat(pag1.isContabilizado()).isFalse();
        assertThat(venda.getValorPago()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(venda.getPagamentos()).hasSize(1);

        // AÇÃO 1: Primeira chamada com pagamento ainda não contabilizado
        venda.registrarPagamentoConfirmado(pag1);

        // ASSERÇÃO 1: Soma valor_pago, marca contabilizado = true, não duplica lista
        assertThat(pag1.isContabilizado()).isTrue();
        assertThat(venda.getValorPago()).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(venda.getValorTroco()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(venda.getPagamentos()).hasSize(1);
        assertThat(venda.estaPaga()).isFalse();

        // AÇÃO 2: Segunda chamada com o MESMO pagamento (retentativa / reentrega)
        venda.registrarPagamentoConfirmado(pag1);

        // ASSERÇÃO 2: É NO-OP estrito (valor_pago inalterado, lista inalterada)
        assertThat(pag1.isContabilizado()).isTrue();
        assertThat(venda.getValorPago()).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(venda.getValorTroco()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(venda.getPagamentos()).hasSize(1);

        // AÇÃO 3: Segundo pagamento distinto quitando a venda com excedente/troco (total = 100.00, pago = 60 + 50 = 110, troco = 10)
        UUID uuidRef2 = UUID.randomUUID();
        Pagamento pag2 = new Pagamento(venda, MetodoPagamento.DINHEIRO, new BigDecimal("50.00"), uuidRef2);
        pag2.confirmar(new BigDecimal("50.00"), "NSU-DIN-2");
        assertThat(pag2.isContabilizado()).isFalse();

        venda.registrarPagamentoConfirmado(pag2);

        // ASSERÇÃO 3: Segundo pagamento somado normalmente e troco calculado
        assertThat(pag2.isContabilizado()).isTrue();
        assertThat(venda.getValorPago()).isEqualByComparingTo(new BigDecimal("110.00"));
        assertThat(venda.getValorTroco()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(venda.getPagamentos()).hasSize(2);
        assertThat(venda.estaPaga()).isTrue();
    }

    @Test
    @DisplayName("Cenário 1 (E1/F4) — Simetria de equals/hashCode compatível com subclasses/proxies de Pagamento")
    void deveGarantirSimetriaDeEqualsEHashCodeParaProxies() {
        Venda venda = new Venda();
        UUID uuidRef = UUID.randomUUID();

        Pagamento pagReal = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("50.00"), uuidRef);

        // Subclasse simulando Hibernate Bytecode Proxy (onde getClass() difere de Pagamento.class)
        class PagamentoHibernateProxyFake extends Pagamento {
            public PagamentoHibernateProxyFake(Venda v, MetodoPagamento m, BigDecimal valor, UUID uuid) {
                super(v, m, valor, uuid);
            }
        }

        Pagamento pagProxy = new PagamentoHibernateProxyFake(venda, MetodoPagamento.PIX, new BigDecimal("50.00"), uuidRef);

        // Simetria de equals
        assertThat(pagReal.equals(pagProxy)).isTrue();
        assertThat(pagProxy.equals(pagReal)).isTrue();

        // Estabilidade e igualdade de hashCode (retorna constante de classe)
        assertThat(pagReal.hashCode()).isEqualTo(Pagamento.class.hashCode());
        assertThat(pagProxy.hashCode()).isEqualTo(Pagamento.class.hashCode());
        assertThat(pagReal.hashCode()).isEqualTo(pagProxy.hashCode());

        // Pagamento com UUID diferente deve ser diferente
        Pagamento pagOutro = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("50.00"), UUID.randomUUID());
        assertThat(pagReal.equals(pagOutro)).isFalse();
    }

    @Test
    @DisplayName("Deve rejeitar registrarPagamentoConfirmado quando status da venda não aceitar pagamento")
    void deveRejeitarQuandoStatusNaoAceitaPagamento() {
        Venda venda = new Venda();
        assertThat(venda.getStatus()).isEqualTo(StatusVenda.ABERTA);

        Pagamento pag = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("50.00"), UUID.randomUUID());

        assertThatThrownBy(() -> venda.registrarPagamentoConfirmado(pag))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("A venda não aceita pagamentos no status: ABERTA");
    }

    private static Venda criarVendaAguardandoPagamento(Long id, BigDecimal total) {
        Venda venda = new Venda();
        ReflectionTestUtils.setField(venda, "id", id);
        venda.adicionarItem(100L, "Produto Teste", total, BigDecimal.ONE);
        venda.irParaPagamento();
        return venda;
    }
}
