package inv.repository;

import inv.model.Produto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;

@Repository
public interface ProdutoRepository extends JpaRepository<Produto, Long> {

    boolean existsByCodigoBarras(String codigoBarras);

    Optional<Produto> findByCodigoBarras(String codigoBarras);

    List<Produto> findByNomeContainingIgnoreCase(String nome);

    @Modifying(clearAutomatically = true) // Limpa o cache para evitar inconsistência com o objeto em memória
    @Query("UPDATE Produto p SET p.quantidadeEstoque = p.quantidadeEstoque - :quantidade " +
            "WHERE p.id = :id AND p.quantidadeEstoque >= :quantidade")
    int decrementarEstoque(@Param("id") Long id, @Param("quantidade") BigDecimal quantidade);
}