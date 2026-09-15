package inv.checkout.infrastructure.web.controller;

import inv.checkout.domain.model.StatusPagamento;
import inv.checkout.infrastructure.web.dto.CriarIntencaoPagamentoRequest;
import inv.checkout.infrastructure.web.dto.IntencaoPagamentoResponse;
import inv.checkout.infrastructure.web.dto.PagamentoStatusResponse;
import inv.checkout.usecase.PagamentoService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/pagamentos")
public class PagamentoController {

    private final PagamentoService pagamentoService;

    public PagamentoController(PagamentoService pagamentoService) {
        this.pagamentoService = pagamentoService;
    }

    @PostMapping("/intencao/{vendaId}")
    public ResponseEntity<IntencaoPagamentoResponse> criarIntencaoPagamento(
            @PathVariable Long vendaId,
            @RequestBody @Valid CriarIntencaoPagamentoRequest request) {

        Long id = pagamentoService.criarIntencaoPagamento(
                vendaId,
                request.metodo(),
                request.valor(),
                request.uuidReferencia()
        );

        return ResponseEntity.ok(new IntencaoPagamentoResponse(id));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<PagamentoStatusResponse> consultarStatus(@PathVariable Long id) {
        StatusPagamento status = pagamentoService.buscarStatusPagamento(id);
        return ResponseEntity.ok(new PagamentoStatusResponse(status));
    }

    @PostMapping("/webhook/confirmar/{pagamentoId}")
    public ResponseEntity<Void> webhookConfirmar(@PathVariable Long pagamentoId,
                                                 @RequestBody Map<String, String> payload) {

        BigDecimal valorReal = new BigDecimal(payload.get("valor_recebido"));
        String nsu = payload.get("nsu");

        pagamentoService.confirmarPagamentoWebhook(pagamentoId, valorReal, nsu);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/webhook/falhar/{pagamentoId}")
    public ResponseEntity<Void> webhookFalhar(@PathVariable Long pagamentoId) {
        pagamentoService.falharPagamentoWebhook(pagamentoId);
        return ResponseEntity.ok().build();
    }
}
