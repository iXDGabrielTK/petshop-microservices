package inv.finance.infrastructure.query;

import inv.finance.api.*;
import inv.finance.infrastructure.persistence.FechamentoCaixaRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
@Transactional(readOnly = true)
class ProjecaoReceitaQueryService implements ReceitaQueryService {

    private final JdbcClient jdbc;
    private final FechamentoCaixaRepository fechamentoRepository;

    ProjecaoReceitaQueryService(JdbcClient jdbc, FechamentoCaixaRepository fechamentoRepository) {
        this.jdbc = jdbc;
        this.fechamentoRepository = fechamentoRepository;
    }

    @Override
    public ResumoReceita resumir(PeriodoContabil p) {
        Objects.requireNonNull(p, "periodo");
        return jdbc.sql("""
                SELECT COALESCE(SUM(total_creditos), 0) AS creditos,
                       COALESCE(SUM(total_debitos), 0)  AS debitos
                  FROM financial_projection_diaria
                 WHERE data_referencia BETWEEN :inicio AND :fim
                """)
                .param("inicio", p.inicio())
                .param("fim", p.fim())
                .query(ResumoReceita.class)
                .single();
    }

    @Override
    public List<ReceitaDiariaView> serieDiaria(PeriodoContabil p) {
        Objects.requireNonNull(p, "periodo");
        return jdbc.sql("""
                SELECT g.dia::date AS data,
                       COALESCE(d.total_creditos, 0) AS creditos,
                       COALESCE(d.total_debitos, 0)  AS debitos
                  FROM generate_series(CAST(:inicio AS date), CAST(:fim AS date), INTERVAL '1 day') AS g(dia)
                  LEFT JOIN financial_projection_diaria d ON d.data_referencia = g.dia::date
                 ORDER BY g.dia ASC
                """)
                .param("inicio", p.inicio())
                .param("fim", p.fim())
                .query(ReceitaDiariaView.class)
                .list();
    }

    @Override
    public Optional<FechamentoCaixaView> ultimoFechamento() {
        return fechamentoRepository.findTopByOrderByDataReferenciaDesc()
                .map(f -> new FechamentoCaixaView(
                        f.getDataReferencia(),
                        f.getSaldoFinal(),
                        f.getUltimoLancamentoId(),
                        f.getProcessadoEm()
                ));
    }

    @Override
    public FrescorProjecao frescor() {
        return jdbc.sql("""
                SELECT (SELECT COALESCE(MAX(id), 0) FROM lancamentos_financeiros)                     AS ultimoLancamentoLedger,
                       (SELECT COALESCE(MAX(lancamento_id), 0) FROM financial_projection_diaria_checkpoint) AS ultimoLancamentoProjetado,
                       (SELECT COUNT(*) FROM projection_retry_queue WHERE status = 'PENDENTE')       AS pendentesRetry
                """)
                .query(FrescorProjecao.class)
                .single();
    }
}
