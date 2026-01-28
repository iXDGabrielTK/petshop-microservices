package inv.repository;

import inv.model.Produto;
import org.springframework.data.jpa.repository.JpaRepository;
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

    // A mágica do SQL: Tenta atualizar E devolve o novo saldo na mesma query.
    // Se a condição (quantidade_estoque >= :qtd) falhar, não atualiza e retorna null.
    @Query(value = """
        UPDATE produto 
        SET quantidade_estoque = quantidade_estoque - :qtd 
        WHERE id = :id AND quantidade_estoque >= :qtd 
        RETURNING quantidade_estoque
        """, nativeQuery = true)
    BigDecimal decrementarEretornarSaldo(@Param("id") Long id, @Param("qtd") BigDecimal qtd);
}