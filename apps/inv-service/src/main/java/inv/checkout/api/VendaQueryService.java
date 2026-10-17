package inv.checkout.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Open Host Service de leitura do Checkout. Nunca expõe agregados JPA.
 */
public interface VendaQueryService {

    long contarVendasConcluidas(LocalDateTime inicioInclusivo, LocalDateTime fimExclusivo);

    List<VendaRecenteView> listarRecentes(int limite);
}

