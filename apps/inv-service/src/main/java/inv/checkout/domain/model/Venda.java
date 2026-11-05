package inv.checkout.domain.model;

import common.exception.BusinessException;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "vendas")
@Getter
@NoArgsConstructor
public class Venda {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime dataHoraAbertura = LocalDateTime.now();

    @Column()
    private LocalDateTime dataHoraConclusao;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatusVenda status = StatusVenda.ABERTA;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal valorTotal = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal valorPago = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal valorTroco = BigDecimal.ZERO;

    @OneToMany(mappedBy = "venda", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ItemVenda> itens = new ArrayList<>();

    @OneToMany(mappedBy = "venda", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Pagamento> pagamentos = new ArrayList<>();

    @Version
    @Column(nullable = false)
    private long version;

    public void adicionarItem(
            Long produtoId,
            String nomeProduto,
            BigDecimal precoUnitario,
            BigDecimal quantidade) {
        if (this.status != StatusVenda.ABERTA) {
            throw new BusinessException("Não é possível adicionar itens. A venda não está ABERTA.");
        }
        this.itens.add(ItemVenda.criar(this, produtoId, nomeProduto, precoUnitario, quantidade));
        recalcularTotal();
    }

    public void irParaPagamento() {
        if (this.status != StatusVenda.ABERTA) {
            throw new BusinessException("Apenas vendas ABERTAS podem ir para pagamento.");
        }
        if (this.itens.isEmpty()) {
            throw new BusinessException("Não é possível pagar uma venda sem itens.");
        }
        this.status = StatusVenda.AGUARDANDO_PAGAMENTO;
    }

    public boolean estaPaga() {
        return this.valorPago.compareTo(this.valorTotal) >= 0;
    }

    public void registrarPagamentoConfirmado(Pagamento pagamento) {
        if (!this.status.aceitaPagamento()) {
            throw new BusinessException("A venda não aceita pagamentos no status: " + this.status);
        }

        Pagamento pagamentoCanonico = this.pagamentos.stream()
                .filter(pagamento::equals)
                .findFirst()
                .orElse(pagamento);

        if (pagamentoCanonico.isContabilizado()) {
            return;
        }

        if (!this.pagamentos.contains(pagamento)) {
            this.pagamentos.add(pagamento);
        }

        BigDecimal valor = pagamentoCanonico.getValorRecebido() != null ? pagamentoCanonico.getValorRecebido() : BigDecimal.ZERO;
        this.valorPago = this.valorPago.add(valor);

        if (this.valorPago.compareTo(this.valorTotal) >= 0) {
            this.valorTroco = this.valorPago.subtract(this.valorTotal);
        }

        pagamentoCanonico.marcarComoContabilizado();
    }

    public void concluir() {
        if (this.status != StatusVenda.AGUARDANDO_PAGAMENTO) {
            throw new BusinessException("Transição inválida.");
        }
        if (!estaPaga()) {
            throw new BusinessException("A venda ainda não foi totalmente paga.");
        }
        this.status = StatusVenda.CONCLUIDA;
        this.dataHoraConclusao = LocalDateTime.now();
    }

    public void cancelar() {
        if (this.status == null || !this.status.podeSerCancelada()) {
            throw new BusinessException("Venda no status " + this.status + " não pode ser cancelada.");
        }
        this.status = StatusVenda.CANCELADA;
    }

    public void cancelarVendaConcluida() {
        if (this.status != StatusVenda.CONCLUIDA) {
            throw new BusinessException("Apenas vendas no status CONCLUIDA podem ser canceladas via estorno. Status atual: " + this.status);
        }
        if (this.pagamentos != null) {
            for (Pagamento pagamento : this.pagamentos) {
                if (pagamento != null && pagamento.getStatus() == StatusPagamento.CONFIRMADO && pagamento.isContabilizado() && !pagamento.isExcedente()) {
                    pagamento.estornar();
                }
            }
        }
        this.status = StatusVenda.CANCELADA;
    }

    private void recalcularTotal() {
        this.valorTotal = itens.stream()
                .map(ItemVenda::getSubTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
