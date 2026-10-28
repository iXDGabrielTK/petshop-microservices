package inv.finance.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "financial_reconciliation_checkpoint")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliacaoCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // A constraint UNIQUE evita que o mesmo High Watermark seja salvo duas vezes
    @Column(nullable = false, unique = true, updatable = false)
    private Long ultimoLancamentoId;

    // O banco preenche automaticamente com CURRENT_TIMESTAMP
    @Column(insertable = false, updatable = false)
    private LocalDateTime verificadoEm;

    public ReconciliacaoCheckpoint(Long ultimoLancamentoId) {
        this.ultimoLancamentoId = ultimoLancamentoId;
    }
}