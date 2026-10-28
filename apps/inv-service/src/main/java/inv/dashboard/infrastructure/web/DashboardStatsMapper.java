package inv.dashboard.infrastructure.web;

import inv.checkout.api.VendaRecenteView;
import inv.dashboard.application.DashboardSnapshot;
import inv.dashboard.infrastructure.web.dto.DashboardStats;
import inv.dashboard.infrastructure.web.dto.DashboardStats.ChartDataDTO;
import inv.dashboard.infrastructure.web.dto.DashboardStats.FrescorDTO;
import inv.dashboard.infrastructure.web.dto.DashboardStats.MetaDTO;
import inv.dashboard.infrastructure.web.dto.DashboardStats.RecentSaleDTO;
import inv.finance.api.FrescorProjecao;
import inv.finance.api.ReceitaDiariaView;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Mapper de apresentação para conversão de snapshots de aplicação e respostas legadas
 * para o DTO de transporte web {@link DashboardStats}.
 */
@Component
public class DashboardStatsMapper {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final Locale LOCALE_BR = Locale.of("pt", "BR");

    public DashboardStats toDto(DashboardSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        List<RecentSaleDTO> recentSales = snapshot.vendasRecentes().stream()
                .map(this::mapRecentSale)
                .toList();

        List<ChartDataDTO> chartData = snapshot.serieDiaria().stream()
                .map(this::mapChartData)
                .toList();

        MetaDTO meta = mapMeta(snapshot.meta());

        return new DashboardStats(
                snapshot.receitaHoje(),
                snapshot.crescimentoMes(),
                snapshot.vendasHoje(),
                snapshot.produtosEstoqueBaixo(),
                snapshot.eventosAtividade(),
                recentSales,
                chartData,
                meta
        );
    }

    private RecentSaleDTO mapRecentSale(VendaRecenteView v) {
        String destaque;
        if (v.produtoDestaque() == null || v.quantidadeItens() == 0) {
            destaque = "Venda sem itens";
        } else if (v.quantidadeItens() > 1) {
            destaque = v.produtoDestaque() + " + outros";
        } else {
            destaque = v.produtoDestaque();
        }

        return new RecentSaleDTO(
                String.valueOf(v.vendaId()),
                destaque,
                v.valorTotal(),
                v.abertaEm().format(TIME_FORMATTER)
        );
    }

    private ChartDataDTO mapChartData(ReceitaDiariaView r) {
        String diaNome = r.data().getDayOfWeek().getDisplayName(TextStyle.SHORT, LOCALE_BR);
        return new ChartDataDTO(diaNome, r.liquida());
    }

    private MetaDTO mapMeta(DashboardSnapshot.Meta meta) {
        if (meta == null) {
            return null;
        }

        FrescorDTO frescorDto = null;
        FrescorProjecao frescor = meta.frescor();
        if (frescor != null) {
            frescorDto = new FrescorDTO(
                    frescor.ultimoLancamentoLedger(),
                    frescor.ultimoLancamentoProjetado(),
                    frescor.pendentesRetry(),
                    frescor.atraso(),
                    frescor.emDia()
            );
        }

        return new MetaDTO(
                meta.geradoEm(),
                frescorDto,
                meta.cardsDegradados()
        );
    }
}
