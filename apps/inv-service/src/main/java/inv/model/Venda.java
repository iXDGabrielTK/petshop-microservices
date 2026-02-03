package inv.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "vendas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Venda {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime dataHora;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal valorTotal = BigDecimal.ZERO;

    @OneToMany(mappedBy = "venda", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ItemVenda> itens = new ArrayList<>();

    @OneToMany(mappedBy = "venda", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MovimentacaoEstoque> movimentacoes = new ArrayList<>();

    public void adicionarItem(Produto produto, BigDecimal quantidade) {
        ItemVenda item = new ItemVenda();
        item.setVenda(this);
        item.setProduto(produto);
        item.setQuantidade(quantidade);

        item.setNomeProdutoSnapshot(produto.getNome());
        item.setPrecoUnitarioSnapshot(produto.getPrecoVenda());

        this.itens.add(item);
    }

    public void adicionarMovimentacao(MovimentacaoEstoque movimentacao) {
        movimentacao.setVenda(this);
        this.movimentacoes.add(movimentacao);
    }

    @PrePersist
    @PreUpdate
    public void calcularTotal() {
        this.valorTotal = itens.stream()
                .map(ItemVenda::getSubTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}