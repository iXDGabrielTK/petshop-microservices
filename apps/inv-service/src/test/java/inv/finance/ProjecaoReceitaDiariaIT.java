package inv.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import common.exception.BusinessException;
import inv.InvServiceApplication;
import inv.checkout.domain.model.Pagamento;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.messaging.VendaEventHandler;
import inv.checkout.infrastructure.messaging.event.PagamentoConfirmadoEvent;
import inv.checkout.infrastructure.persistence.PagamentoRepository;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.finance.api.*;
import inv.finance.domain.model.LancamentoFinanceiro;
import inv.finance.domain.model.TipoLancamento;
import inv.finance.infrastructure.jobs.ProjecaoDiariaCatchUpJob;
import inv.finance.infrastructure.jobs.ProjectionRetryScheduler;
import inv.finance.infrastructure.messaging.FinancialProjectionListener;
import inv.finance.infrastructure.messaging.LancamentoRegistradoEvent;
import inv.finance.infrastructure.persistence.FechamentoCaixaRepository;
import inv.finance.infrastructure.persistence.LancamentoFinanceiroRepository;
import inv.finance.infrastructure.persistence.ProjecaoReceitaDiariaRepository;
import inv.finance.infrastructure.persistence.ProjectionRetryRepository;
import inv.inventory.domain.model.Produto;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import inv.shared.domain.MetodoPagamento;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
class ProjecaoReceitaDiariaIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("inv_db_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("finance.projection.diaria.enabled", () -> "true");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private ConnectionFactory connectionFactory;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoSpyBean
    private ProjecaoReceitaDiariaRepository projecaoDiariaRepository;

    @Autowired
    private ReceitaQueryService receitaQueryService;

    @Autowired
    private FinancialProjectionListener financialProjectionListener;

    @Autowired
    private ProjectionRetryScheduler projectionRetryScheduler;

    @Autowired
    private ProjecaoDiariaCatchUpJob projecaoDiariaCatchUpJob;

    @Autowired
    private LancamentoFinanceiroRepository lancamentoRepository;

    @Autowired
    private ProjectionRetryRepository retryRepository;

    @Autowired
    private VendaRepository vendaRepository;

    @Autowired
    private PagamentoRepository pagamentoRepository;

    @Autowired
    private ProdutoRepository produtoRepository;

    @Autowired
    private FechamentoCaixaRepository fechamentoCaixaRepository;

    @Autowired
    private VendaEventHandler vendaEventHandler;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM financial_reconciliation_checkpoint");
        jdbcTemplate.update("DELETE FROM financial_projection_diaria_checkpoint");
        jdbcTemplate.update("DELETE FROM financial_projection_diaria");
        jdbcTemplate.update("DELETE FROM financial_projection_checkpoint_v2");
        jdbcTemplate.update("DELETE FROM financial_projection_checkpoint");
        jdbcTemplate.update("DELETE FROM financial_projection_venda");
        jdbcTemplate.update("DELETE FROM projection_retry_queue");
        jdbcTemplate.update("DELETE FROM fechamento_caixa_diario");
        jdbcTemplate.update("DELETE FROM itens_venda");
        jdbcTemplate.update("DELETE FROM pagamentos");
        jdbcTemplate.update("DELETE FROM movimentacoes_estoque");
        jdbcTemplate.update("DELETE FROM outbox");
        jdbcTemplate.update("DELETE FROM lancamentos_financeiros");
        jdbcTemplate.update("DELETE FROM vendas");
        produtoRepository.deleteAll();
        Mockito.reset(projecaoDiariaRepository);
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (E3): Idempotência estrita — mesmo lançamento aplicado 3x conta exatamente 1x na projeção")
    void deveGarantirIdempotenciaAoProcessarMesmoLancamentoMultiplasVezes() {
        Venda venda = vendaRepository.save(new Venda());
        BigDecimal valor = new BigDecimal("150.00");

        LancamentoFinanceiro lancamento = lancamentoRepository.save(new LancamentoFinanceiro(
                venda.getId(), UUID.randomUUID(), TipoLancamento.CREDITO, valor));
        Long lancamentoId = lancamento.getId();

        LancamentoRegistradoEvent event = new LancamentoRegistradoEvent(
                lancamentoId, venda.getId(), valor, TipoLancamento.CREDITO);

        // 1ª execução
        financialProjectionListener.processarComIdempotencia(event);

        // 2ª e 3ª execuções repetidas
        financialProjectionListener.processarComIdempotencia(event);
        financialProjectionListener.processarComIdempotencia(event);

        // Validação no banco: exatamente 1 registro no checkpoint diário
        Integer countCp = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM financial_projection_diaria_checkpoint WHERE lancamento_id = ?",
                Integer.class, lancamentoId);
        assertThat(countCp).isEqualTo(1);

        // Validação da projeção: exatamente 1 lançamento e valores corretos
        List<ReceitaDiariaView> serie = receitaQueryService.serieDiaria(PeriodoContabil.dia(LocalDate.now()));
        assertThat(serie).hasSize(1);
        ReceitaDiariaView view = serie.getFirst();
        assertThat(view.creditos()).isEqualByComparingTo("150.00");
        assertThat(view.debitos()).isEqualByComparingTo("0.00");
        assertThat(view.liquida()).isEqualByComparingTo("150.00");

        // Gate de auditoria E3: Σ projecao_diaria.(creditos - debitos) == SUM(lancamentos_financeiros.valor)
        BigDecimal totalLedger = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(valor), 0) FROM lancamentos_financeiros", BigDecimal.class);
        BigDecimal totalProjecao = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(total_creditos - total_debitos), 0) FROM financial_projection_diaria", BigDecimal.class);
        assertThat(totalProjecao).isEqualByComparingTo(totalLedger);

        // Frescor em dia
        FrescorProjecao frescor = receitaQueryService.frescor();
        assertThat(frescor.emDia()).isTrue();
        assertThat(frescor.atraso()).isEqualTo(0);
        assertThat(frescor.pendentesRetry()).isEqualTo(0);
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (E3): Falha transitória na projeção diária deve enfileirar na retry queue e auto-cura restabelece projeção")
    void deveSimularFalhaNoListenerEAutoCuraViaRetryQueue() {
        Venda venda = vendaRepository.save(new Venda());
        BigDecimal valor = new BigDecimal("220.50");

        LancamentoFinanceiro lancamento = lancamentoRepository.save(new LancamentoFinanceiro(
                venda.getId(), UUID.randomUUID(), TipoLancamento.CREDITO, valor));
        Long lancamentoId = lancamento.getId();

        LancamentoRegistradoEvent event = new LancamentoRegistradoEvent(
                lancamentoId, venda.getId(), valor, TipoLancamento.CREDITO);

        // 1. Simular falha transitória no método aplicar da projeção diária
        Mockito.doThrow(new RuntimeException("Simulação de falha de conexão na projeção diária"))
                .when(projecaoDiariaRepository).aplicar(lancamentoId);

        financialProjectionListener.processarComIdempotencia(event);

        // Validação: evento foi enfileirado na retry queue
        assertThat(retryRepository.count()).isEqualTo(1);
        FrescorProjecao frescorComFalha = receitaQueryService.frescor();
        assertThat(frescorComFalha.pendentesRetry()).isEqualTo(1);
        assertThat(frescorComFalha.emDia()).isFalse();

        // 2. Restabelecer repositório e acionar auto-cura
        Mockito.reset(projecaoDiariaRepository);
        projectionRetryScheduler.reprocessarFalhasDeProjecao();

        // Validação pós auto-cura
        assertThat(retryRepository.count()).isEqualTo(0);
        FrescorProjecao frescorCurado = receitaQueryService.frescor();
        assertThat(frescorCurado.pendentesRetry()).isEqualTo(0);
        assertThat(frescorCurado.emDia()).isTrue();

        ResumoReceita resumo = receitaQueryService.resumir(PeriodoContabil.dia(LocalDate.now()));
        assertThat(resumo.creditos()).isEqualByComparingTo("220.50");
        assertThat(resumo.liquida()).isEqualByComparingTo("220.50");
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (E3): Corrida concorrente entre Listener e CatchUpJob/Backfill garante integridade total e zero duplicação")
    void deveGarantirConsistenciaEmConcorrenciaCorridaEntreListenerEBackfill() throws Exception {
        Venda venda = vendaRepository.save(new Venda());
        int totalLancamentos = 60;
        BigDecimal valorUnitario = new BigDecimal("10.00");

        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < totalLancamentos; i++) {
            LancamentoFinanceiro lanc = lancamentoRepository.save(new LancamentoFinanceiro(
                    venda.getId(), UUID.randomUUID(), TipoLancamento.CREDITO, valorUnitario));
            ids.add(lanc.getId());
        }

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread 1: simula chamadas concorrentes ao listener
        Future<?> f1 = executor.submit(() -> {
            try {
                startLatch.await();
                for (Long id : ids) {
                    financialProjectionListener.processarComIdempotencia(new LancamentoRegistradoEvent(
                            id, venda.getId(), valorUnitario, TipoLancamento.CREDITO));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Thread 2: simula catch-up / backfill simultâneo
        Future<?> f2 = executor.submit(() -> {
            try {
                startLatch.await();
                projecaoDiariaCatchUpJob.executarBackfillCompleto();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Dispara ambas simultaneamente
        startLatch.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Verificação: total no checkpoint deve ser exatamente 60
        Integer checkpointsCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM financial_projection_diaria_checkpoint", Integer.class);
        assertThat(checkpointsCount).isEqualTo(totalLancamentos);

        // Verificação: soma na projeção bate com soma no ledger
        BigDecimal totalEsperado = valorUnitario.multiply(BigDecimal.valueOf(totalLancamentos));
        ResumoReceita resumo = receitaQueryService.resumir(PeriodoContabil.dia(LocalDate.now()));
        assertThat(resumo.creditos()).isEqualByComparingTo(totalEsperado);
        assertThat(resumo.liquida()).isEqualByComparingTo(totalEsperado);

        BigDecimal totalLedger = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(valor), 0) FROM lancamentos_financeiros", BigDecimal.class);
        BigDecimal totalProjecao = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(total_creditos - total_debitos), 0) FROM financial_projection_diaria", BigDecimal.class);
        assertThat(totalProjecao).isEqualByComparingTo(totalLedger);

        // Frescor em dia
        FrescorProjecao frescor = receitaQueryService.frescor();
        System.out.println("FRESCOR EM CORRIDA: " + frescor + ", checkpoints=" + checkpointsCount);
        if (frescor.pendentesRetry() > 0) {
            projectionRetryScheduler.reprocessarFalhasDeProjecao();
            frescor = receitaQueryService.frescor();
            System.out.println("FRESCOR POS-RETRY: " + frescor);
        }
        assertThat(frescor.emDia()).isTrue();
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (E3): Lançamentos perto da meia-noite (23:59:59 e 00:00:01) caem nos dias de calendário corretos")
    void deveRegistrarLancamentosPertoDaMeiaNoiteNoDiaCorreto() {
        Venda venda = vendaRepository.save(new Venda());

        Long idD1 = jdbcTemplate.queryForObject("""
                INSERT INTO lancamentos_financeiros (venda_id, referencia, tipo, valor, data_hora)
                VALUES (?, ?, 'CREDITO', 100.00, '2026-05-10 23:59:59'::timestamp)
                RETURNING id
                """, Long.class, venda.getId(), UUID.randomUUID());

        Long idD2 = jdbcTemplate.queryForObject("""
                INSERT INTO lancamentos_financeiros (venda_id, referencia, tipo, valor, data_hora)
                VALUES (?, ?, 'CREDITO', 200.00, '2026-05-11 00:00:01'::timestamp)
                RETURNING id
                """, Long.class, venda.getId(), UUID.randomUUID());

        assertThat(idD1).isNotNull();
        assertThat(idD2).isNotNull();

        projecaoDiariaRepository.aplicar(idD1);
        projecaoDiariaRepository.aplicar(idD2);

        LocalDate d1 = LocalDate.of(2026, 5, 10);
        LocalDate d2 = LocalDate.of(2026, 5, 11);

        List<ReceitaDiariaView> serie = receitaQueryService.serieDiaria(new PeriodoContabil(d1, d2));
        assertThat(serie).hasSize(2);

        ReceitaDiariaView dia1 = serie.stream().filter(s -> s.data().equals(d1)).findFirst().orElseThrow();
        ReceitaDiariaView dia2 = serie.stream().filter(s -> s.data().equals(d2)).findFirst().orElseThrow();

        assertThat(dia1.creditos()).isEqualByComparingTo("100.00");
        assertThat(dia1.liquida()).isEqualByComparingTo("100.00");

        assertThat(dia2.creditos()).isEqualByComparingTo("200.00");
        assertThat(dia2.liquida()).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (E3): Série diária densa — dias sem movimentação financeira retornam com zeros e em ordem crescente")
    void deveGerarSerieDensaComDiasSemMovimentoZerados() {
        Venda venda = vendaRepository.save(new Venda());

        Long id1 = jdbcTemplate.queryForObject("""
                INSERT INTO lancamentos_financeiros (venda_id, referencia, tipo, valor, data_hora)
                VALUES (?, ?, 'CREDITO', 80.00, '2026-06-01 10:00:00'::timestamp)
                RETURNING id
                """, Long.class, venda.getId(), UUID.randomUUID());

        Long id5 = jdbcTemplate.queryForObject("""
                INSERT INTO lancamentos_financeiros (venda_id, referencia, tipo, valor, data_hora)
                VALUES (?, ?, 'CREDITO', 120.00, '2026-06-05 15:30:00'::timestamp)
                RETURNING id
                """, Long.class, venda.getId(), UUID.randomUUID());

        projecaoDiariaRepository.aplicar(id1);
        projecaoDiariaRepository.aplicar(id5);

        LocalDate inicio = LocalDate.of(2026, 6, 1);
        LocalDate fim = LocalDate.of(2026, 6, 5);

        List<ReceitaDiariaView> serie = receitaQueryService.serieDiaria(new PeriodoContabil(inicio, fim));

        assertThat(serie).hasSize(5); // Dias 1, 2, 3, 4, 5
        assertThat(serie.get(0).data()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(serie.get(0).creditos()).isEqualByComparingTo("80.00");
        assertThat(serie.get(0).liquida()).isEqualByComparingTo("80.00");

        // Dias 2, 3 e 4 devem ser exatamente zero
        for (int i = 1; i <= 3; i++) {
            ReceitaDiariaView vazio = serie.get(i);
            assertThat(vazio.creditos()).isEqualByComparingTo("0.00");
            assertThat(vazio.debitos()).isEqualByComparingTo("0.00");
            assertThat(vazio.liquida()).isEqualByComparingTo("0.00");
        }

        assertThat(serie.get(4).data()).isEqualTo(LocalDate.of(2026, 6, 5));
        assertThat(serie.get(4).creditos()).isEqualByComparingTo("120.00");
        assertThat(serie.get(4).liquida()).isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (Q1 / E3): Pagamento em dinheiro com troco — soma somente o valor líquido efetivamente retido")
    void deveSomarApenasValorLiquidoRetidoQuandoHouverPagamentoComTrocoEmDinheiro() throws Exception {
        // 1. Cadastrar produto de R$ 75.00
        Produto produto = new Produto();
        produto.setCodigoBarras("PROD-TROCO-01");
        produto.setNome("Ração Premium 10kg");
        produto.setUnidadeMedida(inv.inventory.domain.model.UnidadeMedida.UN);
        produto.setPrecoVenda(new BigDecimal("75.00"));
        produto.setEstoqueDisponivel(new BigDecimal("20.000"));
        produto.setEstoqueReservado(new BigDecimal("1.000"));
        produto.setEstoqueMinimo(new BigDecimal("5.000"));
        produto = produtoRepository.save(produto);

        // 2. Criar venda no valor de R$ 75.00 e avançar para pagamento
        Venda venda = new Venda();
        venda.adicionarItem(produto.getId(), produto.getNome(), produto.getPrecoVenda(), BigDecimal.ONE);
        venda.irParaPagamento();
        venda = vendaRepository.save(venda);
        assertThat(venda.getValorTotal()).isEqualByComparingTo("75.00");

        // 3. Criar pagamento com valor esperado R$ 75.00
        Pagamento pagamento = new Pagamento(venda, MetodoPagamento.DINHEIRO, new BigDecimal("75.00"), UUID.randomUUID());
        // Cliente entregou R$ 100.00 em dinheiro (troco esperado = R$ 25.00)
        pagamento.confirmar(new BigDecimal("100.00"), "NSU-CASH-001");
        pagamento = pagamentoRepository.save(pagamento);

        // 4. Disparar processamento de confirmação via VendaEventHandler
        PagamentoConfirmadoEvent event = new PagamentoConfirmadoEvent(venda.getId(), pagamento.getId());
        String payload = objectMapper.writeValueAsString(event);
        vendaEventHandler.handlePagamentoConfirmado(payload);

        // 5. Validar que na venda o troco foi calculado
        Venda vendaConcluida = vendaRepository.findById(venda.getId()).orElseThrow();
        assertThat(vendaConcluida.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);
        assertThat(vendaConcluida.getValorPago()).isEqualByComparingTo("100.00");
        assertThat(vendaConcluida.getValorTroco()).isEqualByComparingTo("25.00");

        // 6. Validar que no ledger foi registrado o valor retido (R$ 75.00), nunca os R$ 100.00 entregues
        List<LancamentoFinanceiro> lancamentos = lancamentoRepository.findAll();
        assertThat(lancamentos).hasSize(1);
        LancamentoFinanceiro lancamento = lancamentos.getFirst();
        assertThat(lancamento.getValor()).isEqualByComparingTo("75.00");

        // 7. Aguardar projeção assíncrona
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            ResumoReceita resumo = receitaQueryService.resumir(PeriodoContabil.dia(LocalDate.now()));
            assertThat(resumo.creditos()).isEqualByComparingTo("75.00");
            assertThat(resumo.liquida()).isEqualByComparingTo("75.00");
        });

        // Auditoria estrita:
        BigDecimal totalLedger = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(valor), 0) FROM lancamentos_financeiros", BigDecimal.class);
        BigDecimal totalProjecao = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(total_creditos - total_debitos), 0) FROM financial_projection_diaria", BigDecimal.class);
        assertThat(totalProjecao).isEqualByComparingTo(new BigDecimal("75.00"));
        assertThat(totalProjecao).isEqualByComparingTo(totalLedger);
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (E3): CatchUpJob detecta lançamentos pendentes e atualiza projeção com 100% de paridade")
    void deveExecutarCatchUpJobEAtualizarProjecaoParaLancamentosPendentes() {
        Venda venda = vendaRepository.save(new Venda());

        // Inserir lançamentos direto no ledger sem notificar listener (simulando falha de mensagem ou legado)
        for (int i = 1; i <= 5; i++) {
            jdbcTemplate.update("""
                    INSERT INTO lancamentos_financeiros (venda_id, referencia, tipo, valor, data_hora)
                    VALUES (?, ?, 'CREDITO', 50.00, CURRENT_TIMESTAMP)
                    """, venda.getId(), UUID.randomUUID());
        }

        FrescorProjecao frescorAntes = receitaQueryService.frescor();
        assertThat(frescorAntes.atraso()).isGreaterThanOrEqualTo(5);
        assertThat(frescorAntes.emDia()).isFalse();

        // Executar catch-up job
        int afetados = projecaoDiariaCatchUpJob.executarCatchUp();
        assertThat(afetados).isGreaterThanOrEqualTo(1);

        FrescorProjecao frescorDepois = receitaQueryService.frescor();
        assertThat(frescorDepois.atraso()).isEqualTo(0);
        assertThat(frescorDepois.emDia()).isTrue();

        ResumoReceita resumo = receitaQueryService.resumir(PeriodoContabil.dia(LocalDate.now()));
        assertThat(resumo.creditos()).isEqualByComparingTo("250.00");
        assertThat(resumo.liquida()).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (E3): Consulta do último fechamento D+0 via ReceitaQueryService")
    void deveConsultarUltimoFechamentoCaixaCorretamente() {
        LocalDate data = LocalDate.of(2026, 4, 10);
        jdbcTemplate.update("""
                INSERT INTO fechamento_caixa_diario (data_referencia, saldo_inicial, total_creditos, total_debitos, saldo_final, ultimo_lancamento_id, processado_em)
                VALUES (?, 1000.00, 500.00, 200.00, 1300.00, 42, CURRENT_TIMESTAMP)
                """, data);

        Optional<FechamentoCaixaView> fechamento = receitaQueryService.ultimoFechamento();
        assertThat(fechamento).isPresent();
        FechamentoCaixaView view = fechamento.get();
        assertThat(view.dataReferencia()).isEqualTo(data);
        assertThat(view.saldoFinal()).isEqualByComparingTo("1300.00");
        assertThat(view.highWatermark()).isEqualTo(42L);
        assertThat(view.processadoEm()).isNotNull();
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (E3): Validações e invariantes de integridade do PeriodoContabil")
    void deveValidarInvariantesDePeriodoContabil() {
        LocalDate hoje = LocalDate.of(2026, 4, 15);

        // dia
        PeriodoContabil dia = PeriodoContabil.dia(hoje);
        assertThat(dia.inicio()).isEqualTo(hoje);
        assertThat(dia.fim()).isEqualTo(hoje);

        // mes
        YearMonth ym = YearMonth.of(2026, 2); // fevereiro 2026 (não bissexto = 28 dias)
        PeriodoContabil mes = PeriodoContabil.mes(ym);
        assertThat(mes.inicio()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(mes.fim()).isEqualTo(LocalDate.of(2026, 2, 28));

        // fim < inicio deve falhar
        assertThatThrownBy(() -> new PeriodoContabil(hoje, hoje.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fim < inicio");

        // nulos devem falhar
        assertThatThrownBy(() -> new PeriodoContabil(null, hoje))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PeriodoContabil(hoje, null))
                .isInstanceOf(NullPointerException.class);

        // período > 400 dias deve falhar
        assertThatThrownBy(() -> new PeriodoContabil(hoje, hoje.plusDays(401)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("período excede 400 dias");
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (E3): Período sem movimentações retorna ResumoReceita com zeros e sem erros de execução")
    void deveResumirPeriodoVazioRetornandoZeroSemErros() {
        ResumoReceita resumo = receitaQueryService.resumir(PeriodoContabil.dia(LocalDate.of(2025, 1, 1)));
        assertThat(resumo).isNotNull();
        assertThat(resumo.creditos()).isEqualByComparingTo("0.00");
        assertThat(resumo.debitos()).isEqualByComparingTo("0.00");
        assertThat(resumo.liquida()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (E3): CatchUpJob não executa quando a projeção já está em dia (maxCheckpointId >= maxLedgerId)")
    void deveNaoExecutarCatchUpQuandoProjecaoEstiverEmDia() {
        Venda venda = vendaRepository.save(new Venda());
        LancamentoFinanceiro lanc = lancamentoRepository.save(new LancamentoFinanceiro(
                venda.getId(), UUID.randomUUID(), TipoLancamento.CREDITO, new BigDecimal("100.00")));

        // Aplica o lançamento na projeção
        projecaoDiariaRepository.aplicar(lanc.getId());
        Mockito.reset(projecaoDiariaRepository);

        // Como o checkpoint já alcançou o ledger, executarCatchUp deve retornar 0 sem acionar aplicarFaixa
        int afetados = projecaoDiariaCatchUpJob.executarCatchUp();
        assertThat(afetados).isEqualTo(0);
        Mockito.verify(projecaoDiariaRepository, Mockito.never()).aplicarFaixa(Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (E3): Lançamento de estorno/débito incrementa total_debitos e reduz receita líquida na projeção")
    void deveRegistrarEstornoEAtualizarDebitosNaProjecaoDiaria() {
        Venda venda = vendaRepository.save(new Venda());

        Long idCredito = jdbcTemplate.queryForObject("""
                INSERT INTO lancamentos_financeiros (venda_id, referencia, tipo, valor, data_hora)
                VALUES (?, ?, 'CREDITO', 200.00, '2026-07-01 10:00:00'::timestamp)
                RETURNING id
                """, Long.class, venda.getId(), UUID.randomUUID());

        Long idEstorno = jdbcTemplate.queryForObject("""
                INSERT INTO lancamentos_financeiros (venda_id, referencia, tipo, valor, data_hora)
                VALUES (?, ?, 'ESTORNO', -80.00, '2026-07-02 14:00:00'::timestamp)
                RETURNING id
                """, Long.class, venda.getId(), UUID.randomUUID());

        assertThat(idCredito).isNotNull();
        assertThat(idEstorno).isNotNull();

        projecaoDiariaRepository.aplicar(idCredito);
        projecaoDiariaRepository.aplicar(idEstorno);

        LocalDate d1 = LocalDate.of(2026, 7, 1);
        LocalDate d2 = LocalDate.of(2026, 7, 2);

        // Série diária
        List<ReceitaDiariaView> serie = receitaQueryService.serieDiaria(new PeriodoContabil(d1, d2));
        assertThat(serie).hasSize(2);

        ReceitaDiariaView dia1 = serie.get(0);
        assertThat(dia1.data()).isEqualTo(d1);
        assertThat(dia1.creditos()).isEqualByComparingTo("200.00");
        assertThat(dia1.debitos()).isEqualByComparingTo("0.00");
        assertThat(dia1.liquida()).isEqualByComparingTo("200.00");

        ReceitaDiariaView dia2 = serie.get(1);
        assertThat(dia2.data()).isEqualTo(d2);
        assertThat(dia2.creditos()).isEqualByComparingTo("0.00");
        assertThat(dia2.debitos()).isEqualByComparingTo("80.00");
        assertThat(dia2.liquida()).isEqualByComparingTo("-80.00");

        // Resumo do período
        ResumoReceita resumo = receitaQueryService.resumir(new PeriodoContabil(d1, d2));
        assertThat(resumo.creditos()).isEqualByComparingTo("200.00");
        assertThat(resumo.debitos()).isEqualByComparingTo("80.00");
        assertThat(resumo.liquida()).isEqualByComparingTo("120.00");

        // Auditoria estrita: Σ projecao == SUM(ledger)
        BigDecimal totalLedger = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(valor), 0) FROM lancamentos_financeiros", BigDecimal.class);
        BigDecimal totalProjecao = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(total_creditos - total_debitos), 0) FROM financial_projection_diaria", BigDecimal.class);
        assertThat(totalProjecao).isEqualByComparingTo(new BigDecimal("120.00"));
        assertThat(totalProjecao).isEqualByComparingTo(totalLedger);
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (E3): Backfill completo particionado processa créditos e estornos em múltiplos dias")
    void deveExecutarBackfillCompletoComMultiplosDiasETiposDeLancamento() {
        Venda venda = vendaRepository.save(new Venda());

        // Inserir lançamentos de múltiplos dias direto no banco
        jdbcTemplate.update("""
                INSERT INTO lancamentos_financeiros (venda_id, referencia, tipo, valor, data_hora)
                VALUES (?, ?, 'CREDITO', 100.00, '2026-08-01 10:00:00'::timestamp)
                """, venda.getId(), UUID.randomUUID());

        jdbcTemplate.update("""
                INSERT INTO lancamentos_financeiros (venda_id, referencia, tipo, valor, data_hora)
                VALUES (?, ?, 'CREDITO', 150.00, '2026-08-02 11:00:00'::timestamp)
                """, venda.getId(), UUID.randomUUID());

        jdbcTemplate.update("""
                INSERT INTO lancamentos_financeiros (venda_id, referencia, tipo, valor, data_hora)
                VALUES (?, ?, 'ESTORNO', -50.00, '2026-08-02 16:00:00'::timestamp)
                """, venda.getId(), UUID.randomUUID());

        // Executa backfill completo
        int afetados = projecaoDiariaCatchUpJob.executarBackfillCompleto();
        assertThat(afetados).isGreaterThanOrEqualTo(2); // 2 dias afetados

        // Valida reconciliação
        BigDecimal totalLedger = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(valor), 0) FROM lancamentos_financeiros", BigDecimal.class);
        BigDecimal totalProjecao = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(total_creditos - total_debitos), 0) FROM financial_projection_diaria", BigDecimal.class);
        assertThat(totalProjecao).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(totalProjecao).isEqualByComparingTo(totalLedger);

        ResumoReceita resumo = receitaQueryService.resumir(
                new PeriodoContabil(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2)));
        assertThat(resumo.creditos()).isEqualByComparingTo("250.00");
        assertThat(resumo.debitos()).isEqualByComparingTo("50.00");
        assertThat(resumo.liquida()).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (Q1 / E3): Pagamento múltiplo com Cartão + Dinheiro com troco retém valor líquido correto")
    void deveValidarPagamentoMultiploComCartaoEDinheiroComTroco() throws Exception {
        Produto produto = new Produto();
        produto.setCodigoBarras("PROD-MULTI-01");
        produto.setNome("Kit Cuidado Pet");
        produto.setUnidadeMedida(inv.inventory.domain.model.UnidadeMedida.UN);
        produto.setPrecoVenda(new BigDecimal("150.00"));
        produto.setEstoqueDisponivel(new BigDecimal("10.000"));
        produto.setEstoqueReservado(new BigDecimal("1.000"));
        produto.setEstoqueMinimo(new BigDecimal("2.000"));
        produto = produtoRepository.save(produto);

        Venda venda = new Venda();
        venda.adicionarItem(produto.getId(), produto.getNome(), produto.getPrecoVenda(), BigDecimal.ONE);
        venda.irParaPagamento();
        venda = vendaRepository.save(venda);

        // Pagamento 1: Cartão de R$ 50.00
        Pagamento pagCartao = new Pagamento(venda, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("50.00"), UUID.randomUUID());
        pagCartao.processar();
        pagCartao.confirmar(new BigDecimal("50.00"), "NSU-CARD-001");
        pagCartao = pagamentoRepository.save(pagCartao);

        String payload1 = objectMapper.writeValueAsString(new PagamentoConfirmadoEvent(venda.getId(), pagCartao.getId()));
        vendaEventHandler.handlePagamentoConfirmado(payload1);

        // Pagamento 2: Dinheiro entregue R$ 120.00 (esperado R$ 100.00, troco R$ 20.00)
        Pagamento pagDinheiro = new Pagamento(venda, MetodoPagamento.DINHEIRO, new BigDecimal("100.00"), UUID.randomUUID());
        pagDinheiro.confirmar(new BigDecimal("120.00"), "NSU-CASH-002");
        pagDinheiro = pagamentoRepository.save(pagDinheiro);

        String payload2 = objectMapper.writeValueAsString(new PagamentoConfirmadoEvent(venda.getId(), pagDinheiro.getId()));
        vendaEventHandler.handlePagamentoConfirmado(payload2);

        // Validação da venda
        Venda vendaConcluida = vendaRepository.findById(venda.getId()).orElseThrow();
        assertThat(vendaConcluida.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);
        assertThat(vendaConcluida.getValorPago()).isEqualByComparingTo("170.00");
        assertThat(vendaConcluida.getValorTroco()).isEqualByComparingTo("20.00");

        // Validação do ledger: 2 lançamentos (R$ 50.00 + R$ 100.00 = R$ 150.00)
        List<LancamentoFinanceiro> lancamentos = lancamentoRepository.findAll();
        assertThat(lancamentos).hasSize(2);
        BigDecimal somaLedger = lancamentos.stream().map(LancamentoFinanceiro::getValor).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(somaLedger).isEqualByComparingTo("150.00");

        // Aguarda projeção
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            ResumoReceita resumo = receitaQueryService.resumir(PeriodoContabil.dia(LocalDate.now()));
            assertThat(resumo.creditos()).isEqualByComparingTo("150.00");
            assertThat(resumo.liquida()).isEqualByComparingTo("150.00");
        });
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (E3): Invariantes de FrescorProjecao e validações de nulo em ReceitaQueryService")
    void deveValidarInvariantesDeFrescorProjecaoENulosNoReceitaQueryService() {
        // FrescorProjecao: negativos devem falhar
        assertThatThrownBy(() -> new FrescorProjecao(-1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FrescorProjecao(0, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FrescorProjecao(0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);

        // ReceitaQueryService: período nulo deve falhar
        assertThatThrownBy(() -> receitaQueryService.resumir(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> receitaQueryService.serieDiaria(null))
                .isInstanceOf(NullPointerException.class);
    }
}
