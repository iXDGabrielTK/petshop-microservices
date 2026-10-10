package inv.finance.api;

/**
 * Indicador de frescor da projeção CQRS.
 * Torna a consistência eventual e atrasos de replicação observáveis para os clientes.
 */
public record FrescorProjecao(
        long ultimoLancamentoLedger,
        long ultimoLancamentoProjetado,
        long pendentesRetry
) {
    public FrescorProjecao {
        if (ultimoLancamentoLedger < 0 || ultimoLancamentoProjetado < 0 || pendentesRetry < 0) {
            throw new IllegalArgumentException("Valores de frescor não podem ser negativos");
        }
    }

    public long atraso() {
        return Math.max(0, ultimoLancamentoLedger - ultimoLancamentoProjetado);
    }

    public boolean emDia() {
        return atraso() == 0 && pendentesRetry == 0;
    }
}
