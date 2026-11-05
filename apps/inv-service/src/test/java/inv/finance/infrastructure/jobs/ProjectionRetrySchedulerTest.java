package inv.finance.infrastructure.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import inv.finance.domain.model.ProjectionRetry;
import inv.finance.domain.model.TipoLancamento;
import inv.finance.infrastructure.messaging.FinancialProjectionListener;
import inv.finance.infrastructure.messaging.LancamentoRegistradoEvent;
import inv.finance.infrastructure.persistence.ProjectionRetryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectionRetrySchedulerTest {

    @Mock
    private ProjectionRetryRepository retryRepository;

    @Mock
    private FinancialProjectionListener projectionListener;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ProjectionRetryScheduler scheduler;

    private final LancamentoRegistradoEvent evento = new LancamentoRegistradoEvent(
            42L, 7L, new BigDecimal("-30.00"), TipoLancamento.ESTORNO);

    @BeforeEach
    void setUp() {
        scheduler = new ProjectionRetryScheduler(retryRepository, projectionListener, objectMapper);
    }

    private ProjectionRetry pendencia() throws Exception {
        ProjectionRetry retry = new ProjectionRetry(evento.lancamentoId(), objectMapper.writeValueAsString(evento), null);
        retry.setId(1L);
        return retry;
    }

    @Test
    @DisplayName("DEVE delegar à rotina idempotente, que quita a pendência na transação das projeções")
    void deveDelegarARotinaIdempotente() throws Exception {
        when(retryRepository.buscarPendentes()).thenReturn(List.of(pendencia()));

        scheduler.reprocessarFalhasDeProjecao();

        verify(projectionListener).projetar(evento);
        verify(retryRepository, never()).delete(any());
        verify(retryRepository, never()).save(any());
    }

    @Test
    @DisplayName("DEVE manter a pendência com backoff quando a projeção falhar")
    void deveManterPendenciaQuandoProjecaoFalhar() throws Exception {
        ProjectionRetry retry = pendencia();
        LocalDateTime execucaoAnterior = retry.getProximaExecucao();
        when(retryRepository.buscarPendentes()).thenReturn(List.of(retry));
        doThrow(new IllegalStateException("falha transitória")).when(projectionListener).projetar(evento);

        scheduler.reprocessarFalhasDeProjecao();

        verify(retryRepository).registrarFalhaSeAtual(eq(1L), eq(0), eq(execucaoAnterior),
                eq("falha transitória"), eq("PENDENTE"), any());
        verify(retryRepository, never()).save(any());
        verify(retryRepository, never()).delete(any());
        assertThat(retry.getTentativas()).isEqualTo(1);
        assertThat(retry.getStatus()).isEqualTo("PENDENTE");
        assertThat(retry.getErro()).isEqualTo("falha transitória");
    }

    @Test
    void devePreservarLimiteDeCincoTentativas() throws Exception {
        ProjectionRetry retry = pendencia();
        retry.setTentativas(4);
        LocalDateTime execucaoAnterior = retry.getProximaExecucao();
        when(retryRepository.buscarPendentes()).thenReturn(List.of(retry));
        doThrow(new IllegalStateException("falha persistente")).when(projectionListener).projetar(evento);

        scheduler.reprocessarFalhasDeProjecao();

        verify(retryRepository).registrarFalhaSeAtual(1L, 4, execucaoAnterior,
                "falha persistente", "FALHA_PERMANENTE", execucaoAnterior);
        verify(retryRepository, never()).save(any());
    }
}
