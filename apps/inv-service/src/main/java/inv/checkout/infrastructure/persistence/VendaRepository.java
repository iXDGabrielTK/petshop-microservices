package inv.checkout.infrastructure.persistence;

import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface VendaRepository extends JpaRepository<Venda, Long> {

    @Query("SELECT COALESCE(SUM(v.valorTotal), 0) FROM Venda v WHERE v.status = 'CONCLUIDA' AND v.dataHoraConclusao >= :inicio AND v.dataHoraConclusao < :fim")
    BigDecimal somarReceitaNoPeriodo(@Param("inicio") LocalDateTime inicio, @Param("fim") LocalDateTime fim);

    @Query("SELECT COUNT(v) FROM Venda v WHERE v.dataHoraConclusao >= :inicio AND v.dataHoraConclusao < :fim")
    long countVendasNoPeriodo(@Param("inicio") LocalDateTime inicio, @Param("fim") LocalDateTime fim);

    List<Venda> findTop5ByOrderByDataHoraAberturaDesc();

    List<Venda> findByStatusAndDataHoraConclusaoAfter(StatusVenda status, LocalDateTime dataHoraConclusao);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("SELECT v FROM Venda v WHERE v.id = :id")
    Optional<Venda> findByIdWithLock(@Param("id") Long id);

    @Query("SELECT v.id FROM Venda v WHERE v.status = :status AND v.dataHoraAbertura < :limite")
    List<Long> findIdsByStatusAndDataHoraAberturaBefore(
            @Param("status") StatusVenda status,
            @Param("limite") LocalDateTime limite
    );

    @Query("SELECT v.id FROM Venda v WHERE v.status IN :statuses AND v.dataHoraAbertura < :limite")
    List<Long> findIdsByStatusInAndDataHoraAberturaBefore(
            @Param("statuses") List<StatusVenda> statuses,
            @Param("limite") LocalDateTime limite
    );

    Optional<Venda> findFirstByStatusInOrderByIdDesc(List<StatusVenda> statuses);
}