package inv.checkout.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

@Entity
@Table(name = "itens_venda")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)   // exigido pelo JPA
public class ItemVenda {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venda_id", nullable = false)
    private Venda venda;

    /**
     * ID por referência ao agregado Produto (Bounded Context Inventory).
     * A FK física fk_itens_venda_produto é mantida como "FK de fronteira modular" (ADR-0001, adendo DEBT-ARCH-002).
     */
    @Column(name = "produto_id", nullable = false, updatable = false)
    private Long produtoId;

    @Column(nullable = false)
    private String nomeProdutoSnapshot;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal precoUnitarioSnapshot;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal quantidade;

    static ItemVenda criar(
            Venda venda,
            Long produtoId,
            String nomeProdutoSnapshot,
            BigDecimal precoUnitarioSnapshot,
            BigDecimal quantidade) {
        Objects.requireNonNull(venda, "venda é obrigatória");
        if (produtoId == null || produtoId <= 0) {
            throw new IllegalArgumentException("produtoId deve ser positivo");
        }
        if (nomeProdutoSnapshot == null || nomeProdutoSnapshot.isBlank()) {
            throw new IllegalArgumentException("nomeProdutoSnapshot é obrigatório");
        }
        if (precoUnitarioSnapshot == null || precoUnitarioSnapshot.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("precoUnitarioSnapshot não pode ser negativo");
        }
        if (quantidade == null || quantidade.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("quantidade deve ser positiva");
        }
        ItemVenda item = new ItemVenda();
        item.venda = venda;
        item.produtoId = produtoId;
        item.nomeProdutoSnapshot = nomeProdutoSnapshot;
        item.precoUnitarioSnapshot = precoUnitarioSnapshot;
        item.quantidade = quantidade;
        return item;
    }

    public BigDecimal getSubTotal() {
        if (quantidade == null || precoUnitarioSnapshot == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        }
        return quantidade.multiply(precoUnitarioSnapshot).setScale(2, RoundingMode.HALF_EVEN);
    }
}
