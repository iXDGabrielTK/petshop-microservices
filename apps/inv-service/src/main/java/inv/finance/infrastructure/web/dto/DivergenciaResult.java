package inv.finance.infrastructure.web.dto;

import java.math.BigDecimal;

public interface DivergenciaResult {
    Long getVendaId();
    BigDecimal getSaldoProjetado();
    BigDecimal getSaldoReal();
    BigDecimal getDivergencia();
}