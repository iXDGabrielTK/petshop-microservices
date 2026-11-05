package inv.checkout.usecase;

/**
 * Exceção lançada quando a feature flag de cancelamento de venda concluída está desabilitada
 * (kill switch operacional). Mapeada para HTTP 503 Service Unavailable.
 */
public class CancelamentoVendaDesabilitadoException extends RuntimeException {

    private static final String DEFAULT_MESSAGE = "Cancelamento de venda concluída temporariamente desabilitado";

    public CancelamentoVendaDesabilitadoException() {
        super(DEFAULT_MESSAGE);
    }

    public CancelamentoVendaDesabilitadoException(String message) {
        super(message != null ? message : DEFAULT_MESSAGE);
    }

    public CancelamentoVendaDesabilitadoException(String message, Throwable cause) {
        super(message != null ? message : DEFAULT_MESSAGE, cause);
    }
}
