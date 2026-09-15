package inv.finance.usecase;

import inv.finance.infrastructure.web.dto.DivergenciaResult;
import inv.finance.domain.model.ReconciliacaoCheckpoint;
import inv.finance.infrastructure.persistence.ReconciliacaoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliacaoService {

    private final ReconciliacaoRepository reconRepository;

    // Executa em uma nova transação para não impactar o fechamento caso gere logs massivos
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executarReconciliacaoIncremental(long ultimoReconhecido, long novoMaxId) {

        List<DivergenciaResult> divergencias = reconRepository.auditarDivergencias(ultimoReconhecido, novoMaxId);

        for (DivergenciaResult div : divergencias) {
            BigDecimal diferenca = div.getDivergencia();

            if (diferenca.compareTo(new BigDecimal("0.01")) <= 0) {
                log.warn("⚠️ WARN: Divergência de arredondamento aceitável. Venda: {}, Diferença: {}",
                        div.getVendaId(), diferenca);
            } else if (diferenca.compareTo(new BigDecimal("1.00")) <= 0) {
                log.warn("🚨 ALERT: Divergência suspeita. Venda: {}, Diferença: {}",
                        div.getVendaId(), diferenca);
            } else {
                log.error("💀 CRITICAL: Furo financeiro grave detectado! Venda: {}, Saldo Real: {}, Projetado: {}",
                        div.getVendaId(), div.getSaldoReal(), div.getSaldoProjetado());
                // Aqui você pode plugar um disparo para o Slack/Discord da engenharia
            }
        }

        reconRepository.save(new ReconciliacaoCheckpoint(novoMaxId));
        log.info("🔍 Reconciliação O(Δ) concluída até o ID {}", novoMaxId);
    }
}