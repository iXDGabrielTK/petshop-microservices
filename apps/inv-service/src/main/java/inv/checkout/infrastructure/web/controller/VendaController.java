package inv.checkout.infrastructure.web.controller;

import inv.checkout.infrastructure.web.dto.ItemReciboResponse;
import inv.checkout.infrastructure.web.dto.ReciboResponse;
import inv.checkout.infrastructure.web.dto.VendaRequest;
import inv.checkout.infrastructure.web.dto.VendaStatusResponse;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.web.VendaStatusRegistry;
import inv.checkout.usecase.VendaService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/vendas")
@PreAuthorize("hasAuthority('ADMIN')")
public class VendaController {

    private final VendaService vendaService;
    private final VendaStatusRegistry registry;

    public VendaController(VendaService vendaService, VendaStatusRegistry registry) {
        this.vendaService = vendaService;
        this.registry = registry;
    }

    @PostMapping
    public ResponseEntity<ReciboResponse> iniciarVenda(@RequestBody @Valid VendaRequest request) {
        Venda venda = vendaService.iniciarVenda(request);

        List<ItemReciboResponse> itensResponse = venda.getItens().stream()
                .map(item -> new ItemReciboResponse(
                        item.getNomeProdutoSnapshot(),
                        item.getQuantidade(),
                        item.getPrecoUnitarioSnapshot(),
                        item.getSubTotal()
                ))
                .collect(Collectors.toList());

        ReciboResponse response = new ReciboResponse(
                venda.getId().toString(),
                venda.getDataHoraAbertura().toString(),
                venda.getValorTotal(),
                itensResponse
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/status")
    public DeferredResult<ResponseEntity<VendaStatusResponse>> consultarStatus(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "0") long sinceVersion,
            @RequestParam(required = false, defaultValue = "false") boolean wait) {

        DeferredResult<ResponseEntity<VendaStatusResponse>> deferredResult = new DeferredResult<>(30000L);

        deferredResult.onTimeout(() -> deferredResult.setResult(ResponseEntity.noContent().build()));

        VendaStatusResponse statusAtual = vendaService.buscarStatusVenda(id);

        if (!wait || statusAtual.version() > sinceVersion) {
            deferredResult.setResult(ResponseEntity.ok(statusAtual));
            return deferredResult;
        }

        registry.estacionarRequisicao(id, deferredResult);

        VendaStatusResponse recheckStatus = vendaService.buscarStatusVenda(id);
        if (recheckStatus.version() > sinceVersion) {
            deferredResult.setResult(ResponseEntity.ok(recheckStatus));
        }

        return deferredResult;
    }

    @GetMapping("/aberta")
    public ResponseEntity<ReciboResponse> buscarVendaAtiva() {

        ReciboResponse vendaAtiva = vendaService.recuperarVendaAtiva();

        if (vendaAtiva == null) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(vendaAtiva);
    }
}