package inv.dashboard.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import inv.checkout.api.VendaQueryService;
import inv.checkout.api.VendaRecenteView;
import inv.dashboard.application.port.AtividadeSistemaPort;
import inv.finance.api.FrescorProjecao;
import inv.finance.api.PeriodoContabil;
import inv.finance.api.ReceitaDiariaView;
import inv.finance.api.ReceitaQueryService;
import inv.finance.api.ResumoReceita;
import inv.inventory.api.EstoqueQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Orquestrador central de composição (BFF interno somente-leitura) do módulo inv.dashboard.
 * <p>
 * Agrega Published Languages públicas de Checkout, Finance e Inventory sem acoplamento
 * aos seus modelos de domínio ou persistência JPA.
 */
@Service
public class DashboardQueryService {

    private static final Logger log = LoggerFactory.getLogger(DashboardQueryService.class);

    private static final Duration JANELA_ATIVIDADE = Duration.ofHours(1);
    private static final int TOP_RECENTES = 5;

    private final ReceitaQueryService receitas;
    private final VendaQueryService vendas;
    private final EstoqueQueryService estoque;
    private final AtividadeSistemaPort atividade;
    private final Clock clock;
    private final MeterRegistry meters;
    private final CacheManager cacheManager;
    private final ReentrantLock refreshLock = new ReentrantLock();

    public DashboardQueryService(
            ReceitaQueryService receitas,
            VendaQueryService vendas,
            EstoqueQueryService estoque,
            AtividadeSistemaPort atividade,
            Clock clock,
            MeterRegistry meters
    ) {
        this(receitas, vendas, estoque, atividade, clock, meters, null);
    }

    @Autowired
    public DashboardQueryService(
            ReceitaQueryService receitas,
            VendaQueryService vendas,
            EstoqueQueryService estoque,
            AtividadeSistemaPort atividade,
            Clock clock,
            MeterRegistry meters,
            @Autowired(required = false) CacheManager cacheManager
    ) {
        this.receitas = Objects.requireNonNull(receitas, "receitas");
        this.vendas = Objects.requireNonNull(vendas, "vendas");
        this.estoque = Objects.requireNonNull(estoque, "estoque");
        this.atividade = Objects.requireNonNull(atividade, "atividade");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meters = Objects.requireNonNull(meters, "meters");
        this.cacheManager = cacheManager;
    }

    /**
     * Consulta e consolida métricas do dashboard de forma resiliente.
     * <p>
     * Sem @Transactional intencionalmente: não abre transação cross-module nem segura conexões de banco
     * durante chamadas a serviços de telemetria (Redis).
     * <p>
     * Conta com escudo de proteção contra cache stampede (double-checked locking no recálculo) e regra
     * 'unless' que impede retenção em cache de snapshots degradados.
     *
     * @return snapshot completo com telemetria de frescor e cards degradados
     */
    @Cacheable(value = "dashboard", key = "'snapshot'", unless = "!#result.meta().cardsDegradados().isEmpty()")
    public DashboardSnapshot consultar() {
        if (cacheManager != null) {
            refreshLock.lock();
            try {
                org.springframework.cache.Cache cache = cacheManager.getCache(inv.dashboard.infrastructure.cache.DashboardCacheConfig.CACHE_NAME);
                if (cache != null) {
                    DashboardSnapshot cached = cache.get("snapshot", DashboardSnapshot.class);
                    if (cached != null) {
                        return cached;
                    }
                }
                DashboardSnapshot novo = gerarSnapshot();
                if (cache != null && novo.meta().cardsDegradados().isEmpty()) {
                    cache.put("snapshot", novo);
                }
                return novo;
            } finally {
                refreshLock.unlock();
            }
        }
        return gerarSnapshot();
    }

    private DashboardSnapshot gerarSnapshot() {
        LocalDate hoje = LocalDate.now(clock);
        YearMonth mes = YearMonth.from(hoje);
        YearMonth mesAnt = mes.minusMonths(1);
        List<String> degradados = new ArrayList<>();

        ResumoReceita receitaHoje = card("receitaHoje", () -> receitas.resumir(PeriodoContabil.dia(hoje)), ResumoReceita.ZERO, degradados);

        // Decisão Q2: Crescimento MTD vs MTD (do dia 1 até hoje vs do dia 1 até o mesmo dia do mês anterior)
        PeriodoContabil periodoMesAtual = new PeriodoContabil(mes.atDay(1), hoje);
        int diaFimMesAnt = Math.min(hoje.getDayOfMonth(), mesAnt.lengthOfMonth());
        PeriodoContabil periodoMesAnterior = new PeriodoContabil(mesAnt.atDay(1), mesAnt.atDay(diaFimMesAnt));

        ResumoReceita mesAtual = card("receitaMes", () -> receitas.resumir(periodoMesAtual), ResumoReceita.ZERO, degradados);
        ResumoReceita mesAnterior = card("receitaMesAnterior", () -> receitas.resumir(periodoMesAnterior), ResumoReceita.ZERO, degradados);

        List<ReceitaDiariaView> serie = card("serie7d",
                () -> receitas.serieDiaria(new PeriodoContabil(hoje.minusDays(6), hoje)), List.of(), degradados);

        long vendasHoje = card("vendasHoje",
                () -> vendas.contarVendasConcluidas(hoje.atStartOfDay(), hoje.plusDays(1).atStartOfDay()), 0L, degradados);

        List<VendaRecenteView> recentes = card("recentes",
                () -> vendas.listarRecentes(TOP_RECENTES), List.of(), degradados);

        long estoqueBaixo = card("estoqueBaixo",
                () -> estoque.resumo().produtosAbaixoDoMinimo(), 0L, degradados);

        OptionalLong ativ = card("atividade",
                () -> atividade.contarEventosNaJanela(JANELA_ATIVIDADE), OptionalLong.empty(), degradados);
        long eventosAtividade = 0L;
        if (ativ.isEmpty()) {
            if (!degradados.contains("atividade")) {
                meters.counter("dashboard.card.failure", "card", "atividade").increment();
                degradados.add("atividade");
            }
        } else {
            eventosAtividade = ativ.getAsLong();
        }

        FrescorProjecao frescor = card("frescor", receitas::frescor, null, degradados);

        BigDecimal crescimentoMes = (degradados.contains("receitaMes") || degradados.contains("receitaMesAnterior"))
                ? BigDecimal.ZERO
                : IndicadoresCalculadora.crescimentoPercentual(mesAtual.liquida(), mesAnterior.liquida());

        return new DashboardSnapshot(
                receitaHoje.liquida(),
                crescimentoMes,
                vendasHoje,
                estoqueBaixo,
                eventosAtividade,
                recentes,
                serie,
                new DashboardSnapshot.Meta(Instant.now(clock), frescor, List.copyOf(degradados))
        );
    }

    private <T> T card(String nome, Supplier<T> consulta, T fallback, List<String> degradados) {
        try {
            return Timer.builder("dashboard.card.latency").tag("card", nome).register(meters).record(consulta);
        } catch (RuntimeException e) {
            meters.counter("dashboard.card.failure", "card", nome).increment();
            log.warn("Card {} degradado: {}", nome, e.getMessage(), e);
            if (!degradados.contains(nome)) {
                degradados.add(nome);
            }
            return fallback;
        }
    }
}
