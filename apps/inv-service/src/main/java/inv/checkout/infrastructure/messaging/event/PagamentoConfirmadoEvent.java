package inv.checkout.infrastructure.messaging.event;

public record PagamentoConfirmadoEvent(Long vendaId, Long pagamentoId) {}