package inv.finance.infrastructure.messaging;

import inv.finance.domain.model.TipoLancamento;
import inv.finance.infrastructure.persistence.FinancialProjectionRepository;
import inv.finance.infrastructure.persistence.ProjecaoReceitaDiariaRepository;
import inv.finance.infrastructure.persistence.ProjectionRetryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FinancialProjectionListenerTest {

    private static final long LANCAMENTO_ID = 10L;
    private static final long VENDA_ID = 7L;

    @Mock
    private FinancialProjectionRepository projectionRepository;

    @Mock
    private ProjectionRetryRepository retryRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private TransactionTemplate projectionTxTemplate;

    @Mock
    private RegistroPendenciaProjecao registroPendencia;

    @Mock
    private ProjecaoReceitaDiariaRepository projecaoDiariaRepository;

    private final LancamentoRegistradoEvent credito = new LancamentoRegistradoEvent(
            LANCAMENTO_ID, VENDA_ID, new BigDecimal("100.00"), TipoLancamento.CREDITO);

    private FinancialProjectionListener listener(boolean diariaHabilitada) {
        return new FinancialProjectionListener(projectionRepository, retryRepository, jdbcTemplate,
                projectionTxTemplate, registroPendencia, projecaoDiariaRepository, diariaHabilitada);
    }

    @SuppressWarnings("unchecked")
    private void executarCallbackDaTransacao() {
        when(retryRepository.bloquearLancamento(LANCAMENTO_ID)).thenReturn(LANCAMENTO_ID);
        doAnswer(inv -> {
            ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(mock(TransactionStatus.class));
            return null;
        }).when(projectionTxTemplate).executeWithoutResult(any());
    }

    private void checkpointVendaRetorna(int linhasInseridas) {
        when(jdbcTemplate.update(contains("financial_projection_checkpoint_v2"), eq(LANCAMENTO_ID)))
                .thenReturn(linhasInseridas);
    }

    @Test
    @DisplayName("DEVE aplicar projeção por venda e diária e só então quitar a pendência na mesma transação")
    void deveAplicarTodasAsProjecoesEQuitarPendencia() {
        executarCallbackDaTransacao();
        checkpointVendaRetorna(1);

        listener(true).projetar(credito);

        InOrder ordem = inOrder(jdbcTemplate, projectionRepository, projecaoDiariaRepository, retryRepository);
        ordem.verify(retryRepository).bloquearLancamento(LANCAMENTO_ID);
        ordem.verify(jdbcTemplate).update(contains("financial_projection_checkpoint_v2"), eq(LANCAMENTO_ID));
        ordem.verify(projectionRepository).upsertProjection(VENDA_ID, new BigDecimal("100.00"), new BigDecimal("100.00"), BigDecimal.ZERO);
        ordem.verify(projecaoDiariaRepository).aplicar(LANCAMENTO_ID);
        ordem.verify(retryRepository).quitarPendencia(LANCAMENTO_ID);
        verify(projectionTxTemplate, times(1)).executeWithoutResult(any());
    }

    @Test
    @DisplayName("DEVE aplicar a projeção diária mesmo quando o checkpoint da projeção por venda já existir")
    void checkpointDaVendaNaoComprovaProjecaoDiaria() {
        executarCallbackDaTransacao();
        checkpointVendaRetorna(0);

        listener(true).projetar(credito);

        verify(projectionRepository, never()).upsertProjection(anyLong(), any(), any(), any());
        verify(projecaoDiariaRepository).aplicar(LANCAMENTO_ID);
        verify(retryRepository).quitarPendencia(LANCAMENTO_ID);
    }

    @Test
    @DisplayName("DEVE quitar a pendência sem tocar a projeção diária quando ela estiver desabilitada")
    void deveRespeitarProjecaoDiariaDesabilitada() {
        executarCallbackDaTransacao();
        checkpointVendaRetorna(1);

        listener(false).projetar(credito);

        verify(projectionRepository).upsertProjection(VENDA_ID, new BigDecimal("100.00"), new BigDecimal("100.00"), BigDecimal.ZERO);
        verifyNoInteractions(projecaoDiariaRepository);
        verify(retryRepository).quitarPendencia(LANCAMENTO_ID);
    }

    @Test
    @DisplayName("DEVE propagar falha da projeção diária sem quitar a pendência")
    void naoDeveQuitarPendenciaQuandoProjecaoDiariaFalhar() {
        executarCallbackDaTransacao();
        checkpointVendaRetorna(1);
        doThrow(new IllegalStateException("falha na diária")).when(projecaoDiariaRepository).aplicar(LANCAMENTO_ID);

        assertThatThrownBy(() -> listener(true).projetar(credito)).isInstanceOf(IllegalStateException.class);

        verify(retryRepository, never()).quitarPendencia(anyLong());
        verify(projectionTxTemplate, times(1)).executeWithoutResult(any());
    }

    @Test
    @DisplayName("DEVE reexecutar a transação quando houver conflito concorrente transitório")
    void deveReexecutarEmConflitoTransitorio() {
        doThrow(new CannotAcquireLockException("could not serialize access"))
                .doAnswer(inv -> null)
                .when(projectionTxTemplate).executeWithoutResult(any());

        assertThatCode(() -> listener(true).projetar(credito)).doesNotThrowAnyException();

        verify(projectionTxTemplate, times(2)).executeWithoutResult(any());
    }

    @Test
    @DisplayName("DEVE desistir após o limite de tentativas quando o conflito persistir")
    void deveDesistirAposLimiteDeTentativas() {
        doThrow(new CannotAcquireLockException("could not serialize access"))
                .when(projectionTxTemplate).executeWithoutResult(any());

        assertThatThrownBy(() -> listener(true).projetar(credito)).isInstanceOf(CannotAcquireLockException.class);

        verify(projectionTxTemplate, times(3)).executeWithoutResult(any());
    }

    @Test
    @DisplayName("DEVE registrar a falha do caminho assíncrono na pendência sem propagar a exceção")
    void caminhoAssincronoDeveRegistrarFalhaNaPendencia() {
        doThrow(new IllegalStateException("banco indisponível"))
                .when(projectionTxTemplate).executeWithoutResult(any());

        assertThatCode(() -> listener(true).processarComIdempotencia(credito)).doesNotThrowAnyException();

        verify(registroPendencia).registrarFalhaAssincrona(credito, "banco indisponível");
        verify(retryRepository, never()).quitarPendencia(anyLong());
    }
}
