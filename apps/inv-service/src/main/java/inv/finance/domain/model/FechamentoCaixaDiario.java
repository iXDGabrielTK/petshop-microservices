package inv.finance.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "fechamento_caixa_diario")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FechamentoCaixaDiario {

    @Id
    private LocalDate dataReferencia;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal saldoInicial;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalCreditos;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalDebitos;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal saldoFinal;

    @Column(nullable = false)
    private Long ultimoLancamentoId;

    @Column(insertable = false, updatable = false)
    private LocalDateTime processadoEm;

    public FechamentoCaixaDiario(LocalDate dataReferencia, BigDecimal saldoInicial,
                                 BigDecimal totalCreditos, BigDecimal totalDebitos,
                                 BigDecimal saldoFinal, Long ultimoLancamentoId) {
        this.dataReferencia = dataReferencia;
        this.saldoInicial = saldoInicial;
        this.totalCreditos = totalCreditos;
        this.totalDebitos = totalDebitos;
        this.saldoFinal = saldoFinal;
        this.ultimoLancamentoId = ultimoLancamentoId;
    }
}