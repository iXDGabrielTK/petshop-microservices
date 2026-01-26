package inv.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record VendaRequest(
        @NotEmpty(message = "O carrinho não pode estar vazio")
        @Valid
        List<ItemVendaRequest> itens
) {}