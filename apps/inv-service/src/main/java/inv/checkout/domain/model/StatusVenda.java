package inv.checkout.domain.model;

public enum StatusVenda {
    ABERTA,
    AGUARDANDO_PAGAMENTO,
    CANCELAMENTO_SOLICITADO,
    CONCLUIDA,
    CANCELADA;

    public boolean aceitaPagamento() {
        return this == AGUARDANDO_PAGAMENTO;
    }

    public boolean podeSerCancelada() {
        return this == ABERTA || this == AGUARDANDO_PAGAMENTO;
    }
}