package inv.finance.infrastructure.jobs;

import inv.finance.infrastructure.persistence.SnapshotLockRepository;
import inv.finance.usecase.FechamentoCaixaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FechamentoCaixaScheduler {

    private final FechamentoCaixaService fechamentoService;
    private final SnapshotLockRepository lockRepo;

    @Scheduled(cron = "0 5 0 * * *")
    public void fechamentoDiario() {
        if (!lockRepo.tryLock()) {
            log.info("🔒 Outra instância já está executando o fechamento do caixa. Abortando.");
            return;
        }

        try {
            fechamentoService.executarFechamentoTransacional();
        } catch (Exception e) {
            log.error("🚨 Falha crítica ao processar o fechamento diário.", e);
        } finally {
            lockRepo.unlock();
        }
    }
}