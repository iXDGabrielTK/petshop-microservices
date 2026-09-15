package inv.finance.usecase;

import inv.finance.infrastructure.web.dto.SnapshotResult;
import inv.finance.domain.model.FechamentoCaixaDiario;
import inv.finance.domain.model.ReconciliacaoCheckpoint;
import inv.finance.infrastructure.persistence.FechamentoCaixaRepository;
import inv.finance.infrastructure.persistence.LancamentoFinanceiroRepository;
import inv.finance.infrastructure.persistence.ReconciliacaoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class FechamentoCaixaService {

    private final JdbcTemplate jdbcTemplate;
    private final LancamentoFinanceiroRepository ledgerRepo;
    private final ReconciliacaoService reconciliacaoService;
    private final FechamentoCaixaRepository fechamentoRepo;
    private final ReconciliacaoRepository reconciliacaoRepository;

    @Transactional
    public void executarFechamentoTransacional() {
        FechamentoCaixaDiario ultimo = fechamentoRepo.findTopByOrderByDataReferenciaDesc().orElse(null);
        long ultimoId = ultimo != null ? ultimo.getUltimoLancamentoId() : 0L;

        SnapshotResult snapshot = ledgerRepo.calcularSnapshotAtomico(ultimoId);

        if (snapshot.getUltimoLancamentoId() <= ultimoId) {
            log.info("📊 Nenhum novo lançamento financeiro para processar no fechamento.");
            return;
        }

        BigDecimal saldoInicial = ultimo != null ? ultimo.getSaldoFinal() : BigDecimal.ZERO;
        BigDecimal saldoFinal = saldoInicial
                .add(snapshot.getCreditos())
                .subtract(snapshot.getDebitos());

        LocalDate dataFechamento = jdbcTemplate.queryForObject(
                "SELECT CURRENT_DATE - INTERVAL '1 day'", LocalDate.class
        );

        fechamentoRepo.save(new FechamentoCaixaDiario(
                dataFechamento, saldoInicial, snapshot.getCreditos(),
                snapshot.getDebitos(), saldoFinal, snapshot.getUltimoLancamentoId()
        ));

        long ultimoReconhecido = reconciliacaoRepository.findTopByOrderByIdDesc()
                .map(ReconciliacaoCheckpoint::getUltimoLancamentoId)
                .orElse(0L);

        reconciliacaoService.executarReconciliacaoIncremental(ultimoReconhecido, snapshot.getUltimoLancamentoId());
        log.info("✅ Fechamento D+0 concluído (Data DB: {}). Saldo: {}, High Watermark: {}",
                dataFechamento, saldoFinal, snapshot.getUltimoLancamentoId());
    }
}