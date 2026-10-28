package inv.checkout.infrastructure.messaging;

import inv.checkout.infrastructure.messaging.event.VendaAtualizadaEvent;
import inv.checkout.infrastructure.web.dto.VendaStatusResponse;
import inv.checkout.infrastructure.web.VendaStatusRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class VendaAtualizadaListener {

    private final VendaStatusRegistry registry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVendaAtualizada(VendaAtualizadaEvent event) {
        List<DeferredResult<ResponseEntity<VendaStatusResponse>>> pendentes = registry.recuperarELimpar(event.vendaId());

        if (!pendentes.isEmpty()) {
            ResponseEntity<VendaStatusResponse> response = ResponseEntity.ok(event.snapshot());
            for (var req : pendentes) {
                if (!req.isSetOrExpired()) {
                    req.setResult(response);
                }
            }
        }
    }
}