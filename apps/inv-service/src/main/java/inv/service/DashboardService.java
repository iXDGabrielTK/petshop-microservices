package inv.service;

import inv.dto.DashboardStats;
import inv.dto.DashboardStats.ChartDataDTO;
import inv.dto.DashboardStats.RecentSaleDTO;
import inv.model.Venda;
import inv.repository.ProdutoRepository;
import inv.repository.VendaRepository;
import common.util.DateRange;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DashboardService {

    private final VendaRepository vendaRepository;
    private final ProdutoRepository produtoRepository;
    private final StringRedisTemplate redisTemplate;

    private static final String ACTIVITY_KEY = "system:activity:ts";

    public DashboardService(
            VendaRepository vendaRepository,
            ProdutoRepository produtoRepository,
            StringRedisTemplate redisTemplate
    ) {
        this.vendaRepository = vendaRepository;
        this.produtoRepository = produtoRepository;
        this.redisTemplate = redisTemplate;
    }

    public DashboardStats getStats() {

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");

        DateRange mesAtual = DateRange.mesAtual();
        BigDecimal receitaMesAtual = getSafeValue(
                vendaRepository.somarReceitaNoPeriodo(mesAtual.inicio(), mesAtual.fim())
        );

        DateRange mesPassado = DateRange.mesPassado();
        BigDecimal receitaMesPassado = getSafeValue(
                vendaRepository.somarReceitaNoPeriodo(mesPassado.inicio(), mesPassado.fim())
        );

        DateRange hoje = DateRange.hoje();
        BigDecimal receitaHoje = getSafeValue(
                vendaRepository.somarReceitaNoPeriodo(hoje.inicio(), hoje.fim())
        );

        long vendasHoje = vendaRepository.countVendasNoPeriodo(hoje.inicio(), hoje.fim());

        long estoqueBaixo = produtoRepository.countProdutosComEstoqueBaixo();

        long agoraMillis = Instant.now().toEpochMilli();
        long umaHoraAtras = agoraMillis - 3600000;

        long activityCount = Optional.ofNullable(
                redisTemplate.opsForZSet()
                        .count(ACTIVITY_KEY, umaHoraAtras, agoraMillis)
        ).orElse(0L);

        BigDecimal growth = calcularGrowth(receitaMesAtual, receitaMesPassado);

        List<RecentSaleDTO> recentSales =
                vendaRepository.findTop5ByOrderByDataHoraDesc()
                        .stream()
                        .map(v -> mapRecentSale(v, formatter))
                        .toList();

        List<ChartDataDTO> chartData = calcularChartData();

        return new DashboardStats(
                receitaHoje,
                growth,
                vendasHoje,
                estoqueBaixo,
                activityCount,
                recentSales,
                chartData
        );
    }

    private BigDecimal calcularGrowth(BigDecimal atual, BigDecimal passado) {
        if (passado.compareTo(BigDecimal.ZERO) == 0)
            return BigDecimal.ZERO;

        return atual.subtract(passado)
                .divide(passado, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal getSafeValue(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private RecentSaleDTO mapRecentSale(Venda v, DateTimeFormatter formatter) {
        String destaque = v.getItens().isEmpty()
                ? "Venda sem itens"
                : v.getItens().getFirst().getNomeProdutoSnapshot()
                + (v.getItens().size() > 1 ? " + outros" : "");

        return new RecentSaleDTO(
                v.getId().toString(),
                destaque,
                v.getValorTotal(),
                v.getDataHora().format(formatter)
        );
    }

    private List<ChartDataDTO> calcularChartData() {
        DateRange ultimos7Dias = DateRange.ultimosDias(7);

        List<Venda> ultimasVendas =
                vendaRepository.findByDataHoraAfter(ultimos7Dias.inicio());

        Map<LocalDate, BigDecimal> map = new TreeMap<>();
        LocalDate dataAtual = LocalDate.now();

        for (int i = 0; i < 7; i++) {
            map.put(dataAtual.minusDays(i), BigDecimal.ZERO);
        }

        ultimasVendas.forEach(v ->
                map.merge(
                        v.getDataHora().toLocalDate(),
                        v.getValorTotal(),
                        BigDecimal::add
                )
        );

        return map.entrySet()
                .stream()
                .map(entry ->
                        new ChartDataDTO(
                                entry.getKey()
                                        .getDayOfWeek()
                                        .getDisplayName(
                                                TextStyle.SHORT,
                                                Locale.of("pt", "BR")
                                        ),
                                entry.getValue()
                        )
                )
                .collect(Collectors.toList());
    }
}
