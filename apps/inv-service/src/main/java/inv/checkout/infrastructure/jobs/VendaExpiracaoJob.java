package inv.checkout.infrastructure.jobs;

import inv.checkout.domain.model.StatusVenda;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.checkout.usecase.VendaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Slf4j
public class VendaExpiracaoJob {

    private final VendaRepository vendaRepository;
    private final VendaService vendaService;

    public VendaExpiracaoJob(VendaRepository vendaRepository, VendaService vendaService) {
        this.vendaRepository = vendaRepository;
        this.vendaService = vendaService;
    }

    @Scheduled(fixedDelay = 300000)
    public void cancelarVendasAbandonadas() {
        LocalDateTime limite = LocalDateTime.now().minusMinutes(30);

        var vendasAbandonadas = vendaRepository.findIdsByStatusInAndDataHoraAberturaBefore(
                java.util.List.of(StatusVenda.ABERTA, StatusVenda.AGUARDANDO_PAGAMENTO), limite
        );

        for (Long vendaId : vendasAbandonadas) {
            try {
                vendaService.cancelarVendaExpirada(vendaId);
                log.info("🧹 Venda abandonada #{} cancelada e estoque liberado.", vendaId);
            } catch (Exception e) {
                log.error("Erro ao cancelar venda abandonada #{}", vendaId, e);
            }
        }
    }
}