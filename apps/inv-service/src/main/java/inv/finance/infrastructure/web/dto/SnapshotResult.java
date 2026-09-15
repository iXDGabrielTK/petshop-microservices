package inv.finance.infrastructure.web.dto;

import java.math.BigDecimal;

public interface SnapshotResult {
    long getUltimoLancamentoId();
    BigDecimal getCreditos();
    BigDecimal getDebitos();
}