package inv.it;

import inv.finance.domain.model.ProjectionRetry;
import inv.finance.infrastructure.messaging.LancamentoRegistradoEvent;
import inv.shared.event.VendaEstornadaEvent;
import inv.shared.event.VendaPagaEvent;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@TestPropertySource(properties = "finance.projection.diaria.enabled=true")
class ProjecaoDuravelIT extends ProjecaoDuravelSupport {
    @Test
    void deveRecuperarCreditoEEstornoSemFastPathNemRegistroDeFalha() {
        registrarCreditoEEstorno();

        assertThat(contar("projection_retry_queue")).isEqualTo(2);
        assertThat(retryRepository.findAll()).allSatisfy(p -> {
            assertThat(p.getErro()).isNull();
            assertThat(p.getTentativas()).isZero();
        });
        assertThat(contar("financial_projection_checkpoint_v2")).isZero();
        assertThat(contar("financial_projection_diaria_checkpoint")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM projection_retry_queue
                WHERE proxima_execucao > now() AND proxima_execucao <= now() + INTERVAL '30 seconds'
                """, Integer.class)).isEqualTo(2);
        scheduler.reprocessarFalhasDeProjecao();
        assertThat(contar("projection_retry_queue")).isEqualTo(2);

        tornarElegivel();
        scheduler.reprocessarFalhasDeProjecao();
        scheduler.reprocessarFalhasDeProjecao();

        assertThat(contar("projection_retry_queue")).isZero();
        assertThat(contar("financial_projection_checkpoint_v2")).isEqualTo(2);
        assertThat(contar("financial_projection_diaria_checkpoint")).isEqualTo(2);
        conferirVenda("0.00", "100.00");
        var diaria = jdbc.queryForMap("SELECT * FROM financial_projection_diaria");
        assertThat((BigDecimal) diaria.get("total_creditos")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) diaria.get("total_debitos")).isEqualByComparingTo("100.00");
        assertThat(((Number) diaria.get("qtd_lancamentos")).longValue()).isEqualTo(2);
        verify(projectionExecutor, times(2)).execute(any());
    }

    @Test
    void rollbackDoLedgerDeveReverterPendenciasDeCreditoEEstorno() {
        tx.executeWithoutResult(status -> {
            registrarCreditoEEstorno();
            status.setRollbackOnly();
        });

        assertThat(contar("lancamentos_financeiros")).isZero();
        assertThat(contar("projection_retry_queue")).isZero();
        verifyNoInteractions(projectionExecutor);
    }

    @Test
    void falhaAoRegistrarPendenciaDeveReverterLedgerDosDoisListeners() {
        doThrow(new IllegalStateException("fila indisponível"))
                .when(retryRepository).registrarPendencia(anyLong(), anyString(), anyLong());

        assertThatThrownBy(() -> creditoListener.onVendaPaga(
                new VendaPagaEvent(vendaId, UUID.randomUUID(), BigDecimal.TEN))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> estornoListener.handleVendaEstornada(
                new VendaEstornadaEvent(vendaId, UUID.randomUUID(), BigDecimal.TEN))).isInstanceOf(IllegalStateException.class);

        assertThat(contar("lancamentos_financeiros")).isZero();
        assertThat(contar("projection_retry_queue")).isZero();
        verifyNoInteractions(projectionExecutor);
    }

    @Test
    void falhaAposQuitacaoDeveReverterJdbcJpaEAsDuasProjecoes() throws Exception {
        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
        var event = registrarCredito();
        tornarElegivel();
        var repositorioReal = mockingDetails(retryRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            repositorioReal.answer(invocation);
            throw new IllegalStateException("falha após DELETE");
        }).when(retryRepository).quitarPendencia(event.lancamentoId());

        scheduler.reprocessarFalhasDeProjecao();

        assertThat(contar("financial_projection_checkpoint_v2")).isZero();
        assertThat(contar("financial_projection_venda")).isZero();
        assertThat(contar("financial_projection_diaria_checkpoint")).isZero();
        assertThat(contar("financial_projection_diaria")).isZero();
        assertThat(retryRepository.findAll()).singleElement().satisfies(p -> assertThat(p.getTentativas()).isEqualTo(1));

        reset(retryRepository);
        tornarElegivel();
        scheduler.reprocessarFalhasDeProjecao();
        assertThat(contar("projection_retry_queue")).isZero();
        conferirVenda("100.00", "0.00");
    }

    @Test
    void deveConsumirPendenciaLegadaComVendaJaProjetadaEDiariaPendente() throws Exception {
        var event = registrarCredito();
        // Simula uma pendência anterior à persistência transacional, com checkpoint parcial.
        retryRepository.deleteAll();
        retryRepository.save(new ProjectionRetry(event.lancamentoId(), mapper.writeValueAsString(event), "falha legada"));
        jdbc.update("INSERT INTO financial_projection_checkpoint_v2 (lancamento_id) VALUES (?)", event.lancamentoId());
        jdbc.update("""
                INSERT INTO financial_projection_venda (venda_id, saldo, total_creditos, total_estornos)
                VALUES (?, 100, 100, 0)
                """, vendaId);

        scheduler.reprocessarFalhasDeProjecao();

        conferirVenda("100.00", "0.00");
        assertThat(contar("projection_retry_queue")).isZero();
        assertThat(contar("financial_projection_diaria_checkpoint")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT total_creditos FROM financial_projection_diaria", BigDecimal.class))
                .isEqualByComparingTo("100.00");
    }

    @Test
    void duasInstanciasDoSchedulerEFastPathDevemAplicarUmaUnicaVez() throws Exception {
        var event = registrarCredito();
        tornarElegivel();
        CyclicBarrier selecoes = new CyclicBarrier(2);
        var repositorioReal = mockingDetails(retryRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object resultado = repositorioReal.answer(invocation);
            selecoes.await(10, TimeUnit.SECONDS);
            return resultado;
        }).when(retryRepository).buscarPendentes();
        CyclicBarrier consumidores = new CyclicBarrier(3);
        doAnswer(invocation -> {
            consumidores.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(projectionListener).projetar(event);

        try (var executor = Executors.newFixedThreadPool(3)) {
            var primeiro = executor.submit(scheduler::reprocessarFalhasDeProjecao);
            var segundo = executor.submit(scheduler::reprocessarFalhasDeProjecao);
            var rapido = executor.submit(() -> projectionListener.processarComIdempotencia(event));
            primeiro.get(20, TimeUnit.SECONDS);
            segundo.get(20, TimeUnit.SECONDS);
            rapido.get(20, TimeUnit.SECONDS);
        }

        conferirVenda("100.00", "0.00");
        assertThat(contar("projection_retry_queue")).isZero();
        assertThat(contar("financial_projection_checkpoint_v2")).isEqualTo(1);
        assertThat(contar("financial_projection_diaria_checkpoint")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT qtd_lancamentos FROM financial_projection_diaria", Long.class)).isEqualTo(1);
    }

    @Test
    void falhaAssincronaAtrasadaNaoDeveRecriarPendenciaQuitada() throws Exception {
        var event = registrarCredito();
        CountDownLatch falhaIniciada = new CountDownLatch(1);
        CountDownLatch projecaoConfirmada = new CountDownLatch(1);
        AtomicBoolean primeira = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (primeira.getAndSet(false)) {
                falhaIniciada.countDown();
                assertThat(projecaoConfirmada.await(10, TimeUnit.SECONDS)).isTrue();
                throw new IllegalStateException("falha atrasada");
            }
            return invocation.callRealMethod();
        }).when(projectionListener).projetar(event);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var atrasado = executor.submit(() -> projectionListener.processarComIdempotencia(event));
            try {
                assertThat(falhaIniciada.await(10, TimeUnit.SECONDS)).isTrue();
                projectionListener.projetar(event);
            } finally {
                projecaoConfirmada.countDown();
            }
            atrasado.get(15, TimeUnit.SECONDS);
        }

        assertThat(contar("projection_retry_queue")).isZero();
        conferirVenda("100.00", "0.00");
    }

    @Test
    void registroDeFalhaDeveAguardarCommitDaQuitacaoConcorrente() throws Exception {
        var event = registrarCredito();
        CountDownLatch quitacaoAberta = new CountDownLatch(1);
        CountDownLatch liberarCommit = new CountDownLatch(1);
        var repositorioReal = mockingDetails(retryRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object resultado = repositorioReal.answer(invocation);
            quitacaoAberta.countDown();
            assertThat(liberarCommit.await(10, TimeUnit.SECONDS)).isTrue();
            return resultado;
        }).when(retryRepository).quitarPendencia(event.lancamentoId());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var projecao = executor.submit(() -> projectionListener.projetar(event));
            try {
                assertThat(quitacaoAberta.await(10, TimeUnit.SECONDS)).isTrue();
                doThrow(new IllegalStateException("falha enquanto outra projeção confirma"))
                        .when(projectionListener).projetar(event);
                var falha = executor.submit(() -> projectionListener.processarComIdempotencia(event));
                await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                        assertThat(jdbc.queryForObject("""
                                SELECT count(*) FROM pg_stat_activity
                                WHERE wait_event_type = 'Lock' AND state = 'active'
                                  AND lower(query) LIKE '%lancamentos_financeiros%'
                                  AND lower(query) LIKE '%for update%'
                                """, Integer.class)).isEqualTo(1));
                // Outra conexão ainda enxerga a pendência e nenhum checkpoint antes do commit.
                assertThat(contar("projection_retry_queue")).isEqualTo(1);
                assertThat(contar("financial_projection_checkpoint_v2")).isZero();
                liberarCommit.countDown();
                projecao.get(10, TimeUnit.SECONDS);
                falha.get(10, TimeUnit.SECONDS);
            } finally {
                liberarCommit.countDown();
            }
        }

        assertThat(contar("projection_retry_queue")).isZero();
        assertThat(contar("financial_projection_diaria_checkpoint")).isEqualTo(1);
        conferirVenda("100.00", "0.00");
    }

    @Test
    void falhaDoSchedulerComSelecaoAntigaNaoDeveRecriarPendencia() throws Exception {
        var event = registrarCredito();
        tornarElegivel();
        List<ProjectionRetry> selecaoAntiga = retryRepository.buscarPendentes();
        projectionListener.projetar(event);
        doReturn(selecaoAntiga).when(retryRepository).buscarPendentes();
        doThrow(new IllegalStateException("consumidor atrasado")).when(projectionListener).projetar(event);

        scheduler.reprocessarFalhasDeProjecao();

        assertThat(contar("projection_retry_queue")).isZero();
        verify(retryRepository, never()).save(any());
    }

    @Test
    void falhasAtrasadasDevemPreservarBackoffELimiteDeTentativas() throws Exception {
        LancamentoRegistradoEvent event = registrarCredito();
        tornarElegivel();
        List<ProjectionRetry> selecaoAntiga = retryRepository.buscarPendentes();
        doThrow(new IllegalStateException("falha persistente")).when(projectionListener).projetar(event);
        scheduler.reprocessarFalhasDeProjecao();
        ProjectionRetry atual = retryRepository.findAll().getFirst();
        assertThat(atual.getTentativas()).isEqualTo(1);
        assertThat(Duration.between(selecaoAntiga.getFirst().getProximaExecucao(), atual.getProximaExecucao()).toSeconds())
                .isGreaterThanOrEqualTo(120);

        doReturn(selecaoAntiga).when(retryRepository).buscarPendentes();
        scheduler.reprocessarFalhasDeProjecao();
        projectionListener.processarComIdempotencia(event);
        ProjectionRetry preservado = retryRepository.findAll().getFirst();
        assertThat(preservado.getTentativas()).isEqualTo(1);
        assertThat(preservado.getProximaExecucao()).isEqualTo(atual.getProximaExecucao());
        reset(retryRepository);

        for (int tentativa = 2; tentativa <= 5; tentativa++) {
            tornarElegivel();
            scheduler.reprocessarFalhasDeProjecao();
        }
        assertThat(retryRepository.findAll()).singleElement().satisfies(p -> {
            assertThat(p.getTentativas()).isEqualTo(5);
            assertThat(p.getStatus()).isEqualTo("FALHA_PERMANENTE");
        });
        assertThat(retryRepository.buscarPendentes()).isEmpty();
        projectionListener.processarComIdempotencia(event);
        assertThat(retryRepository.findAll().getFirst().getStatus()).isEqualTo("FALHA_PERMANENTE");
    }
}
