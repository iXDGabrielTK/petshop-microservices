package inv.finance.infrastructure.jobs;

import inv.finance.infrastructure.persistence.ProjecaoReceitaDiariaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job de catch-up e backfill da projeção financeira diária (DEBT-ARCH-001 E3 / ADR-0006).
 * Garante idempotência via checkpoints físicos no PostgreSQL e cobre lacunas ou latências eventuais.
 */
@Slf4j
@Component
public class ProjecaoDiariaCatchUpJob {

    private final ProjecaoReceitaDiariaRepository projecaoDiariaRepository;
    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;
    private final int batchSize;
    private final long safetyWindow;

    public ProjecaoDiariaCatchUpJob(
            ProjecaoReceitaDiariaRepository projecaoDiariaRepository,
            JdbcTemplate jdbcTemplate,
            @Value("${finance.projection.diaria.enabled:true}") boolean enabled,
            @Value("${finance.projection.diaria.catch-up.batch-size:5000}") int batchSize,
            @Value("${finance.projection.diaria.catch-up.safety-window:1000}") long safetyWindow) {
        this.projecaoDiariaRepository = projecaoDiariaRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.safetyWindow = safetyWindow;
    }

    /**
     * Execução periódica de catch-up: verifica o lag entre o ledger e a projeção diária
     * e processa os lotes pendentes com idempotência garantida pela CTE no banco.
     */
    @Scheduled(fixedDelayString = "${finance.projection.diaria.catch-up.fixed-delay-ms:60000}",
               initialDelayString = "${finance.projection.diaria.catch-up.initial-delay-ms:10000}")
    public int executarCatchUp() {
        if (!enabled) {
            return 0;
        }

        Long maxLedgerId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM lancamentos_financeiros", Long.class);
        Long maxCheckpointId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(lancamento_id), 0) FROM financial_projection_diaria_checkpoint", Long.class);

        if (maxLedgerId == null || maxCheckpointId == null || maxLedgerId == 0) {
            return 0;
        }

        // Se a projeção já alcançou o high-watermark do ledger, nada a processar
        if (maxCheckpointId >= maxLedgerId) {
            return 0;
        }

        // Janela de segurança para cobrir eventuais gaps/commits concorrentes fora de ordem
        long inicio = Math.max(0, maxCheckpointId - safetyWindow);

        int totalAfetado = 0;
        long de = inicio;
        while (de < maxLedgerId) {
            long ate = Math.min(maxLedgerId, de + batchSize);
            totalAfetado += projecaoDiariaRepository.aplicarFaixa(de, ate);
            de = ate;
        }

        if (totalAfetado > 0) {
            log.info("Projeção diária catch-up aplicou lançamentos pendentes (faixa {} a {}). Dias afetados: {}",
                    inicio, maxLedgerId, totalAfetado);
        }
        return totalAfetado;
    }

    /**
     * Backfill completo: processa toda a base de lancamentos_financeiros desde o ID 0 até MAX(id)
     * em lotes configuráveis (padrão 5.000). Totalmente idempotente.
     */
    public int executarBackfillCompleto() {
        Long maxLedgerId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM lancamentos_financeiros", Long.class);
        if (maxLedgerId == null || maxLedgerId == 0) {
            return 0;
        }
        return executarBackfill(0, maxLedgerId, batchSize);
    }

    public int executarBackfill(long deExclusivo, long ateInclusivo, int lote) {
        log.info("Iniciando backfill da projeção diária: faixa {} a {}, lote {}.",
                deExclusivo, ateInclusivo, lote);
        int totalAfetado = 0;
        long de = deExclusivo;
        while (de < ateInclusivo) {
            long ate = Math.min(ateInclusivo, de + lote);
            totalAfetado += projecaoDiariaRepository.aplicarFaixa(de, ate);
            de = ate;
        }
        log.info("Backfill da projeção diária finalizado. Linhas diárias afetadas/atualizadas: {}.", totalAfetado);
        return totalAfetado;
    }
}
