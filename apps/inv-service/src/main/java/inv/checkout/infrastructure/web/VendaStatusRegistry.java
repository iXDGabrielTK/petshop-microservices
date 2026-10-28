package inv.checkout.infrastructure.web;

import inv.checkout.infrastructure.web.dto.VendaStatusResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class VendaStatusRegistry {

    private final Map<Long, CopyOnWriteArrayList<DeferredResult<ResponseEntity<VendaStatusResponse>>>> waitingRequests = new ConcurrentHashMap<>();

    public void estacionarRequisicao(Long vendaId, DeferredResult<ResponseEntity<VendaStatusResponse>> result) {
        waitingRequests.computeIfAbsent(vendaId, k -> new CopyOnWriteArrayList<>()).add(result);

        result.onCompletion(() ->
                waitingRequests.computeIfPresent(vendaId, (id, list) -> {
                    list.remove(result);
                    return list.isEmpty() ? null : list;
                })
        );
    }

    public List<DeferredResult<ResponseEntity<VendaStatusResponse>>> recuperarELimpar(Long vendaId) {
        List<DeferredResult<ResponseEntity<VendaStatusResponse>>> pendentes = waitingRequests.remove(vendaId);
        return pendentes != null ? pendentes : Collections.emptyList();
    }
}