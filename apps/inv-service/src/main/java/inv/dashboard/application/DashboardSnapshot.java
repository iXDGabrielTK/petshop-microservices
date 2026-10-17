package inv.dashboard.application;

import inv.checkout.api.VendaRecenteView;
import inv.finance.api.FrescorProjecao;
import inv.finance.api.ReceitaDiariaView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Modelo imutável de snapshot analítico agregado da camada de aplicação do Dashboard.
 */
public record DashboardSnapshot(
        BigDecimal receitaHoje,
        BigDecimal crescimentoMes,
        long vendasHoje,
        long produtosEstoqueBaixo,
        long eventosAtividade,
        List<VendaRecenteView> vendasRecentes,
        List<ReceitaDiariaView> serieDiaria,
        Meta meta
) {
    public DashboardSnapshot {
        Objects.requireNonNull(receitaHoje, "receitaHoje");
        Objects.requireNonNull(crescimentoMes, "crescimentoMes");
        vendasRecentes = vendasRecentes != null ? List.copyOf(vendasRecentes) : List.of();
        serieDiaria = serieDiaria != null ? List.copyOf(serieDiaria) : List.of();
        Objects.requireNonNull(meta, "meta");
    }

    public record Meta(
            Instant geradoEm,
            FrescorProjecao frescor,
            List<String> cardsDegradados
    ) {
        public Meta {
            Objects.requireNonNull(geradoEm, "geradoEm");
            cardsDegradados = cardsDegradados != null ? List.copyOf(cardsDegradados) : List.of();
        }
    }
}
