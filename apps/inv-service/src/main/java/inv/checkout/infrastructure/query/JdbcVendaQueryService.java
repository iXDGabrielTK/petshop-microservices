package inv.checkout.infrastructure.query;

import inv.checkout.api.VendaQueryService;
import inv.checkout.api.VendaRecenteView;
import inv.checkout.domain.model.StatusVenda;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Component
@Transactional(readOnly = true)
class JdbcVendaQueryService implements VendaQueryService {

    private static final int LIMITE_MAXIMO = 50;
    private static final List<String> STATUS_FATURADAS =
            StatusVenda.FATURADAS.stream().map(Enum::name).toList();

    private final JdbcClient jdbc;

    JdbcVendaQueryService(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<VendaRecenteView> listarRecentes(int limite) {
        int limiteEfetivo = Math.clamp(limite, 1, LIMITE_MAXIMO);
        return jdbc.sql("""
                SELECT v.id AS venda_id,
                       pi.nome_produto_snapshot AS produto_destaque,
                       CAST((SELECT COUNT(*) FROM itens_venda x WHERE x.venda_id = v.id) AS integer) AS quantidade_itens,
                       v.valor_total,
                       v.data_hora_abertura AS aberta_em
                  FROM vendas v
                  LEFT JOIN LATERAL (
                       SELECT i.nome_produto_snapshot FROM itens_venda i
                        WHERE i.venda_id = v.id ORDER BY i.id LIMIT 1) pi ON TRUE
                 WHERE v.status IN (:status)
                 ORDER BY v.data_hora_conclusao DESC
                 LIMIT :limite
                """)
            .param("status", STATUS_FATURADAS)
            .param("limite", limiteEfetivo)
            .query((rs, rowNum) -> new VendaRecenteView(
                    rs.getLong("venda_id"),
                    rs.getString("produto_destaque"),
                    rs.getInt("quantidade_itens"),
                    rs.getBigDecimal("valor_total"),
                    rs.getObject("aberta_em", LocalDateTime.class)
            ))
            .list();
    }

    @Override
    public long contarVendasConcluidas(LocalDateTime inicioInclusivo, LocalDateTime fimExclusivo) {
        Objects.requireNonNull(inicioInclusivo, "inicioInclusivo");
        Objects.requireNonNull(fimExclusivo, "fimExclusivo");
        if (fimExclusivo.isBefore(inicioInclusivo)) {
            return 0L;
        }
        Long count = jdbc.sql("""
                SELECT COUNT(*) FROM vendas
                 WHERE status IN (:status)
                   AND data_hora_conclusao >= :inicio
                   AND data_hora_conclusao < :fim
                """)
            .param("status", STATUS_FATURADAS)
            .param("inicio", inicioInclusivo)
            .param("fim", fimExclusivo)
            .query(Long.class)
            .single();
        return count != null ? count : 0L;
    }
}

