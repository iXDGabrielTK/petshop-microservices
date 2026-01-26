package inv.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "produtos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Produto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String codigoBarras;

    @Column(nullable = false)
    private String nome;

    @Column(precision = 10, scale = 3)
    private BigDecimal estoqueMinimo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UnidadeMedida unidadeMedida;

    @Column(precision = 10, scale = 3, nullable = false)
    private BigDecimal quantidadeEstoque;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal precoVenda;

    @Version
    private Long version;
}