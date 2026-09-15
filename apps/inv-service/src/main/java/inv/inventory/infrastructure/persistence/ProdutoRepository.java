package inv.inventory.infrastructure.persistence;

import inv.inventory.domain.model.Produto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public interface ProdutoRepository extends JpaRepository<Produto, Long> {

    boolean existsByCodigoBarras(String codigoBarras);

    @Query("SELECT COUNT(p) FROM Produto p WHERE p.estoqueDisponivel <= p.estoqueMinimo")
    long countProdutosComEstoqueBaixo();

    Optional<Produto> findByCodigoBarras(String codigoBarras);

    Page<Produto> findByNomeContainingIgnoreCase(String nome, Pageable pageable);

    @Query(value = """
        UPDATE produtos 
        SET estoque_disponivel = estoque_disponivel - :qtd,
            estoque_reservado = estoque_reservado + :qtd
        WHERE id = :id AND estoque_disponivel >= :qtd 
        RETURNING estoque_disponivel
        """, nativeQuery = true)
    BigDecimal reservarEstoqueAtomo(@Param("id") Long id, @Param("qtd") BigDecimal qtd);

    @Query(value = """
        UPDATE produtos 
        SET estoque_reservado = estoque_reservado - :qtd 
        WHERE id = :id AND estoque_reservado >= :qtd 
        RETURNING estoque_reservado
        """, nativeQuery = true)
    BigDecimal confirmarBaixaEstoqueAtomo(@Param("id") Long id, @Param("qtd") BigDecimal qtd);

    @Query(value = """
        UPDATE produtos 
        SET estoque_reservado = estoque_reservado - :qtd,
            estoque_disponivel = estoque_disponivel + :qtd
        WHERE id = :id AND estoque_reservado >= :qtd 
        RETURNING estoque_disponivel
        """, nativeQuery = true)
    BigDecimal estornarReservaEstoqueAtomo(@Param("id") Long id, @Param("qtd") BigDecimal qtd);
}