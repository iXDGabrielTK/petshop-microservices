package inv.checkout.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Open Host Service de leitura do Checkout. Nunca expõe agregados JPA.
 */
public interface VendaQueryService {

    long contarVendasConcluidas(LocalDateTime inicioInclusivo, LocalDateTime fimExclusivo);

    List<VendaRecenteView> listarRecentes(int limite);

    /** TRANSITÓRIO — paridade da Etapa 4. Vira @Deprecated(forRemoval = true) e sai na Etapa 6 se a semântica de ledger for aprovada (§7-Q1). */
    BigDecimal somarFaturamentoConcluido(LocalDateTime inicioInclusivo, LocalDateTime fimExclusivo);

    /** TRANSITÓRIO — idem. */
    List<FaturamentoDiarioView> faturamentoDiarioConcluido(LocalDate inicio, LocalDate fim);
}
