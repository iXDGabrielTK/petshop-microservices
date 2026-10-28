package inv.finance.infrastructure.persistence;

import inv.finance.domain.model.ProjectionRetry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ProjectionRetryRepository extends JpaRepository<ProjectionRetry, Long> {

    @Query(value = """
        SELECT *
        FROM projection_retry_queue
        WHERE proxima_execucao <= now()
          AND tentativas < 5
          AND status = 'PENDENTE'
        FOR UPDATE SKIP LOCKED
        LIMIT 100
    """, nativeQuery = true)
    List<ProjectionRetry> buscarPendentesComLock();
}