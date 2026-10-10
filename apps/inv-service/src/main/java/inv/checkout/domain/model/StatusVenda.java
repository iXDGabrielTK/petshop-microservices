package inv.checkout.domain.model;

import java.util.List;

public enum StatusVenda {
    ABERTA,
    AGUARDANDO_PAGAMENTO,
    CANCELAMENTO_SOLICITADO,
    CONCLUIDA,
    CANCELADA;

    public static final List<StatusVenda> FATURADAS = List.of(CONCLUIDA, CANCELAMENTO_SOLICITADO);

    public boolean aceitaPagamento() {
        return this == AGUARDANDO_PAGAMENTO;
    }

    public boolean podeSerCancelada() {
        return this == ABERTA || this == AGUARDANDO_PAGAMENTO;
    }
}