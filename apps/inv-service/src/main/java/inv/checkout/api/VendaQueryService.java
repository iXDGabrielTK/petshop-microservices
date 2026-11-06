/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
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
