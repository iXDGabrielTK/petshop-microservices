package inv.finance.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SnapshotLockRepository {

    private final JdbcTemplate jdbc;
    private static final long FECHAMENTO_LOCK_ID = 987654321L;

    public boolean tryLock() {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, FECHAMENTO_LOCK_ID)
        );
    }

    public void unlock() {
        jdbc.execute("SELECT pg_advisory_unlock(" + FECHAMENTO_LOCK_ID + ")");
    }
}