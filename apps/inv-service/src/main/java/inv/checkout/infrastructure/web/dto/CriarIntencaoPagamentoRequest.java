package inv.checkout.infrastructure.web.dto;

import inv.checkout.domain.model.MetodoPagamento;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record CriarIntencaoPagamentoRequest(
        @NotNull(message = "O UUID de referência é obrigatório")
        UUID uuidReferencia,

        @NotNull(message = "Método de pagamento é obrigatório")
        MetodoPagamento metodo,

        @NotNull(message = "Valor é obrigatório")
        @Positive(message = "Valor deve ser maior que zero")
        BigDecimal valor
) {}