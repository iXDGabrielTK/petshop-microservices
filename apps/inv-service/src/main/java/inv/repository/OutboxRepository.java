package inv.repository;

import inv.model.Outbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OutboxRepository extends JpaRepository<Outbox, Long> {

     // Busca 1 item, ordena pelo mais antigo e BLOQUEIA ignorando os que já estão travados
    @Query(value = """
        SELECT * FROM outbox 
        ORDER BY created_at ASC 
        LIMIT 1 
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<Outbox> findNextMessageToProcess();
}