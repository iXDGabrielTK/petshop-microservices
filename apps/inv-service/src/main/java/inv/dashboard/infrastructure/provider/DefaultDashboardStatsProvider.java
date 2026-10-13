package inv.dashboard.infrastructure.provider;

import inv.dashboard.application.DashboardQueryService;
import inv.dashboard.application.DashboardSnapshot;
import inv.dashboard.application.DashboardStatsProvider;
import inv.dashboard.infrastructure.legacy.LegacyDashboardStatsAdapter;
import inv.dashboard.infrastructure.shadow.DashboardParityComparator;
import inv.dashboard.infrastructure.shadow.ParityComparisonResult;
import inv.dashboard.infrastructure.web.DashboardStatsMapper;
import inv.dashboard.infrastructure.web.dto.DashboardStats;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Implementação padrão de {@link DashboardStatsProvider} com suporte aos 3 modos
 * de execução do plano DEBT-ARCH-001 (Etapa E4): 'legacy', 'shadow' e 'modular'.
 */
@Component
public class DefaultDashboardStatsProvider implements DashboardStatsProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultDashboardStatsProvider.class);

    public enum EngineMode {
        LEGACY,
        SHADOW,
        MODULAR;

        public static EngineMode from(String value) {
            if (value == null || value.isBlank()) {
                return LEGACY;
            }
            try {
                return EngineMode.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Modo de engine inválido '{}', utilizando LEGACY por padrão", value);
                return LEGACY;
            }
        }
    }

    private final EngineMode engineMode;
    private final double shadowSampleRate;
    private final LegacyDashboardStatsAdapter legacyAdapter;
    private final DashboardQueryService queryService;
    private final DashboardStatsMapper mapper;
    private final DashboardParityComparator comparator;
    private final Semaphore shadowSemaphore;
    private final ExecutorService shadowExecutor;

    public DefaultDashboardStatsProvider(
            @Value("${dashboard.engine:modular}") String engine,
            @Value("${dashboard.shadow.sample-rate:0.10}") double shadowSampleRate,
            LegacyDashboardStatsAdapter legacyAdapter,
            DashboardQueryService queryService,
            DashboardStatsMapper mapper,
            DashboardParityComparator comparator
    ) {
        this.engineMode = EngineMode.from(engine);
        this.shadowSampleRate = shadowSampleRate;
        this.legacyAdapter = Objects.requireNonNull(legacyAdapter, "legacyAdapter");
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.comparator = Objects.requireNonNull(comparator, "comparator");
        this.shadowSemaphore = new Semaphore(2);
        this.shadowExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public DashboardStats getStats() {
        return switch (engineMode) {
            case LEGACY -> legacyAdapter.getStats();
            case MODULAR -> getModularStats();
            case SHADOW -> executeShadowMode();
        };
    }

    public EngineMode getEngineMode() {
        return engineMode;
    }

    public DashboardStats getModularStats() {
        DashboardSnapshot snapshot = queryService.consultar();
        return mapper.toDto(snapshot);
    }

    private DashboardStats executeShadowMode() {
        DashboardStats legacyResponse = legacyAdapter.getStats();

        boolean shouldSample = shadowSampleRate >= 1.0
                || ThreadLocalRandom.current().nextDouble() < shadowSampleRate;

        if (shouldSample) {
            if (shadowSemaphore.tryAcquire()) {
                try {
                    shadowExecutor.submit(() -> {
                        try {
                            DashboardStats modularStats = getModularStats();
                            comparator.compareAndEmitParity(legacyResponse, modularStats);
                        } catch (Throwable t) {
                            log.warn("Erro ao executar shadow mode no background: {}", t.getMessage(), t);
                        } finally {
                            shadowSemaphore.release();
                        }
                    });
                } catch (Throwable t) {
                    shadowSemaphore.release();
                    log.warn("Falha ao submeter tarefa de shadow mode: {}", t.getMessage(), t);
                }
            } else {
                log.debug("Shadow mode pulado: limite de concorrência do semáforo atingido");
            }
        }

        return legacyResponse;
    }

    public int getAvailableShadowPermits() {
        return shadowSemaphore.availablePermits();
    }

    /**
     * Aguarda até que todas as tarefas ativas de shadow mode no executor em background sejam concluídas.
     *
     * @param timeout tempo máximo de espera
     * @return true se todas as tarefas concluíram dentro do timeout, false caso contrário
     * @throws InterruptedException se a thread for interrompida durante a espera
     */
    public boolean awaitIdleShadow(java.time.Duration timeout) throws InterruptedException {
        if (shadowSemaphore.tryAcquire(2, timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            shadowSemaphore.release(2);
            return true;
        }
        return false;
    }

    /**
     * Execução síncrona de reconciliação de paridade para testes e validações controladas.
     */
    public ParityComparisonResult executeShadowSync() {
        DashboardStats legacy = legacyAdapter.getStats();
        DashboardStats modular = getModularStats();
        return comparator.compareAndEmitParity(legacy, modular);
    }

    @PreDestroy
    void shutdown() {
        try {
            shadowExecutor.shutdown();
            if (!shadowExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                shadowExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shadowExecutor.shutdownNow();
        }
    }
}
