package inv.checkout.domain.model;

import common.exception.BusinessException;
import inv.inventory.domain.model.Produto;
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

    public void adicionarItem(Produto produto, BigDecimal quantidade) {
        if (this.status != StatusVenda.ABERTA) {
            throw new BusinessException("Não é possível adicionar itens. A venda não está ABERTA.");
        }

        ItemVenda item = new ItemVenda();
        item.setVenda(this);
        item.setProduto(produto);
        item.setQuantidade(quantidade);
        item.setNomeProdutoSnapshot(produto.getNome());
        item.setPrecoUnitarioSnapshot(produto.getPrecoVenda());

        this.itens.add(item);
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

        this.pagamentos.add(pagamento);
        this.valorPago = this.valorPago.add(pagamento.getValorRecebido());

        if (this.valorPago.compareTo(this.valorTotal) >= 0) {
            this.valorTroco = this.valorPago.subtract(this.valorTotal);
        }
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
        if (this.status == StatusVenda.CONCLUIDA) {
            throw new BusinessException("Vendas concluídas não podem ser simplesmente canceladas. Requer fluxo de devolução.");
        }
        this.status = StatusVenda.CANCELADA;
    }

    private void recalcularTotal() {
        this.valorTotal = itens.stream()
                .map(ItemVenda::getSubTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}