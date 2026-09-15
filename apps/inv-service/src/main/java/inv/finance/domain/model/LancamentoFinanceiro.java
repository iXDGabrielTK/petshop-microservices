package inv.finance.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "lancamentos_financeiros")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LancamentoFinanceiro {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "venda_id", nullable = false, updatable = false)
    private Long vendaId;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID referencia;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TipoLancamento tipo;

    @Column(nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal valor;

    @Column(name = "data_hora", insertable = false, updatable = false)
    private LocalDateTime dataHora;
    
    public LancamentoFinanceiro(Long vendaId, UUID referencia, TipoLancamento tipo, BigDecimal valorAbsoluto) {
        this.vendaId = vendaId;
        this.referencia = referencia;
        this.tipo = tipo;

        this.valor = tipo == TipoLancamento.CREDITO
                ? valorAbsoluto.abs()
                : valorAbsoluto.abs().negate();
    }
}