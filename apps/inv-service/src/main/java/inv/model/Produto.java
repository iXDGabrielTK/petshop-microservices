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

    // Código de barras deve ser único para busca rápida no PDV
    @Column(unique = true)
    private String codigoBarras;

    @Column(nullable = false)
    private String nome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UnidadeMedida unidadeMedida;

    // Scale 3 permite até 1.500 kg ou 0.250 kg
    @Column(precision = 10, scale = 3, nullable = false)
    private BigDecimal quantidadeEstoque;

    // Scale 2 para dinheiro
    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal precoVenda;
}