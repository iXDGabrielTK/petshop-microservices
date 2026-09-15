package inv.checkout.infrastructure.web.dto;

import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;

import java.io.Serializable;
import java.math.BigDecimal;

public record VendaStatusResponse(
        Long id,
        long version,
        StatusVenda status,
        BigDecimal valorTotal,
        BigDecimal valorPago,
        BigDecimal valorTroco
) implements Serializable {

    public static VendaStatusResponse from(Venda venda) {
        return new VendaStatusResponse(
                venda.getId(),
                venda.getVersion(),
                venda.getStatus(),
                venda.getValorTotal(),
                venda.getValorPago(),
                venda.getValorTroco()
        );
    }
}