package inv.checkout.usecase;

/**
 * Exceção lançada quando uma solicitação de cancelamento de venda concluída entra em conflito
 * com o estado atual da venda (ex: venda já cancelada ou ainda em aberto).
 * Mapeada para HTTP 409 Conflict.
 */
public class CancelamentoVendaConflitoException extends RuntimeException {

    private static final String DEFAULT_MESSAGE = "Conflito ao cancelar venda: status atual incompatível";

    public CancelamentoVendaConflitoException() {
        super(DEFAULT_MESSAGE);
    }

    public CancelamentoVendaConflitoException(String message) {
        super(message != null ? message : DEFAULT_MESSAGE);
    }

    public CancelamentoVendaConflitoException(String message, Throwable cause) {
        super(message != null ? message : DEFAULT_MESSAGE, cause);
    }
}
