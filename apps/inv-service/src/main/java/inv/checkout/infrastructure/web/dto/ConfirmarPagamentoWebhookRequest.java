package inv.checkout.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record ConfirmarPagamentoWebhookRequest(
    @NotNull(message = "valor_recebido é obrigatório")
    @Positive(message = "valor_recebido deve ser positivo")
    BigDecimal valor_recebido,
    @NotBlank(message = "nsu é obrigatório")
    String nsu
) {}
