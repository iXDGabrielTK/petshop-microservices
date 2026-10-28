package inv.finance.api;

import java.util.List;
import java.util.Optional;

/**
 * Open Host Service de receita do Finance.
 * Fonte: projeções CQRS (ADR-0006) e fechamento D+0 (ADR-0007).
 */
public interface ReceitaQueryService {

    ResumoReceita resumir(PeriodoContabil periodo);

    /** Série densa, com dias sem movimento iguais a zero, em ordem crescente. */
    List<ReceitaDiariaView> serieDiaria(PeriodoContabil periodo);

    Optional<FechamentoCaixaView> ultimoFechamento();

    /** Torna a consistência eventual observável para o cliente. */
    FrescorProjecao frescor();
}
