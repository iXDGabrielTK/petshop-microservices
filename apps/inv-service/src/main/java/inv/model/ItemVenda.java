package inv.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "itens_venda")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ItemVenda {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venda_id", nullable = false)
    private Venda venda;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "produto_id", nullable = false)
    private Produto produto;

    @Column(nullable = false)
    private String nomeProdutoSnapshot;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal precoUnitarioSnapshot;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal quantidade;

    public BigDecimal getSubTotal() {
        return precoUnitarioSnapshot.multiply(quantidade);
    }
}