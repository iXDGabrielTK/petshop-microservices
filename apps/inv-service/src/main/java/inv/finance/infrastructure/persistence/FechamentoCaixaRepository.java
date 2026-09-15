package inv.finance.infrastructure.persistence;

import inv.finance.domain.model.FechamentoCaixaDiario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface FechamentoCaixaRepository extends JpaRepository<FechamentoCaixaDiario, LocalDate> {
    Optional<FechamentoCaixaDiario> findTopByOrderByDataReferenciaDesc();
}