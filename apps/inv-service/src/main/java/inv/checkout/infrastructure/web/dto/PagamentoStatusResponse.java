package inv.checkout.infrastructure.web.dto;

import inv.checkout.domain.model.StatusPagamento;

public record PagamentoStatusResponse(StatusPagamento status) {}