package inv.inventory.domain.model;

import common.exception.BusinessException;
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
    private BigDecimal estoqueDisponivel = BigDecimal.ZERO;

    @Column(precision = 10, scale = 3, nullable = false)
    private BigDecimal estoqueReservado = BigDecimal.ZERO;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal precoVenda;

    @Version
    @Column(nullable = false)
    private long version;

    public void reservar(BigDecimal quantidade) {
        if (this.estoqueDisponivel.compareTo(quantidade) < 0) {
            throw new BusinessException("Estoque insuficiente para o produto: " + this.nome);
        }
        this.estoqueDisponivel = this.estoqueDisponivel.subtract(quantidade);
        this.estoqueReservado = this.estoqueReservado.add(quantidade);
    }

    public void confirmarReserva(BigDecimal quantidade) {
        if (this.estoqueReservado.compareTo(quantidade) < 0) {
            throw new BusinessException("Inconsistência: Tentativa de baixar mais estoque reservado do que o existente para: " + this.nome);
        }
        this.estoqueReservado = this.estoqueReservado.subtract(quantidade);
    }

    public void cancelarReserva(BigDecimal quantidade) {
        if (this.estoqueReservado.compareTo(quantidade) < 0) {
            throw new BusinessException("Inconsistência: Tentativa de cancelar reserva inexistente para: " + this.nome);
        }
        this.estoqueReservado = this.estoqueReservado.subtract(quantidade);
        this.estoqueDisponivel = this.estoqueDisponivel.add(quantidade);
    }
}