package inv.controller;

import inv.dto.ReciboResponse;
import inv.dto.VendaRequest;
import inv.service.VendaService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/vendas")
public class VendaController {

    private final VendaService vendaService;

    public VendaController(VendaService vendaService) {
        this.vendaService = vendaService;
    }

    @PostMapping
    public ResponseEntity<ReciboResponse> registrarVenda(@RequestBody @Valid VendaRequest request) {
        ReciboResponse recibo = vendaService.realizarVenda(request);
        return ResponseEntity.ok(recibo);
    }
}