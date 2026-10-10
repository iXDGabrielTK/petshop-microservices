package inv.checkout.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

public record VendaRecenteView(
        long vendaId,
        String produtoDestaque /* null = sem itens */,
        int quantidadeItens,
        BigDecimal valorTotal,
        LocalDateTime abertaEm
) {
    public VendaRecenteView {
        Objects.requireNonNull(valorTotal, "valorTotal");
        Objects.requireNonNull(abertaEm, "abertaEm");
        if (quantidadeItens < 0) {
            throw new IllegalArgumentException("quantidadeItens < 0");
        }
    }
}
