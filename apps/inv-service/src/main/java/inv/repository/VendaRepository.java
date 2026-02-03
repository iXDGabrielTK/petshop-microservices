package inv.repository;

import inv.model.Venda;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface VendaRepository extends JpaRepository<Venda, Long> {

    @Query("SELECT COALESCE(SUM(v.valorTotal), 0) FROM Venda v WHERE v.dataHora BETWEEN :inicio AND :fim")
    BigDecimal somarReceitaNoPeriodo(@Param("inicio") LocalDateTime inicio, @Param("fim") LocalDateTime fim);

    long countByDataHoraBetween(LocalDateTime inicio, LocalDateTime fim);

    List<Venda> findTop5ByOrderByDataHoraDesc();

    List<Venda> findByDataHoraAfter(LocalDateTime data);
}