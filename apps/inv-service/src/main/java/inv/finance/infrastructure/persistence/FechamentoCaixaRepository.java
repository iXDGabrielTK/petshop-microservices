/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.finance.infrastructure.persistence;

import inv.finance.domain.model.FechamentoCaixaDiario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface FechamentoCaixaRepository extends JpaRepository<FechamentoCaixaDiario, LocalDate> {
    Optional<FechamentoCaixaDiario> findTopByOrderByDataReferenciaDesc();
}
