package inv.controller;

import inv.dto.DashboardStats;
import inv.model.Venda;
import inv.repository.ProdutoRepository;
import inv.repository.VendaRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    private final VendaRepository vendaRepository;
    private final ProdutoRepository produtoRepository;

    public DashboardController(VendaRepository vendaRepository, ProdutoRepository produtoRepository) {
        this.vendaRepository = vendaRepository;
        this.produtoRepository = produtoRepository;
    }

    @GetMapping("/stats")
    public ResponseEntity<DashboardStats> getStats() {
        LocalDateTime inicioDia = LocalDate.now().atStartOfDay();
        LocalDateTime fimDia = LocalDate.now().atTime(23, 59, 59);

        BigDecimal receitaHoje = vendaRepository.somarReceitaNoPeriodo(inicioDia, fimDia);
        long vendasHoje = vendaRepository.countByDataHoraBetween(inicioDia, fimDia);
        long estoqueBaixo = produtoRepository.countProdutosComEstoqueBaixo();

        List<DashboardStats.RecentSaleDTO> recentSales = vendaRepository.findTop5ByOrderByDataHoraDesc().stream()
            .map(v -> {
                String destaque = v.getItens().isEmpty()
                        ? "Venda sem itens"
                        : v.getItens().getFirst().getNomeProdutoSnapshot() + (v.getItens().size() > 1 ? " + outros" : "");
                return new DashboardStats.RecentSaleDTO(
                        v.getId().toString(),
                        destaque,
                        v.getValorTotal(),
                        v.getDataHora().toLocalTime().toString().substring(0, 5)
                );
            })
            .toList();

        LocalDateTime seteDiasAtras = LocalDateTime.now().minusDays(6).withHour(0).withMinute(0);
        List<Venda> ultimasVendas = vendaRepository.findByDataHoraAfter(seteDiasAtras);

        Map<LocalDate, BigDecimal> mapPorDia = new TreeMap<>();
        for (int i = 0; i < 7; i++) {
            mapPorDia.put(LocalDate.now().minusDays(i), BigDecimal.ZERO);
        }

        ultimasVendas.forEach(v -> {
            LocalDate dia = v.getDataHora().toLocalDate();
            mapPorDia.merge(dia, v.getValorTotal(), BigDecimal::add);
        });

        List<DashboardStats.ChartDataDTO> chartData = mapPorDia.entrySet().stream()
                .map(entry -> new DashboardStats.ChartDataDTO(
                        entry.getKey().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.of("pt", "BR")),
                        entry.getValue()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(new DashboardStats(
                receitaHoje,
                vendasHoje,
                estoqueBaixo,
                recentSales,
                chartData
        ));
    }
}