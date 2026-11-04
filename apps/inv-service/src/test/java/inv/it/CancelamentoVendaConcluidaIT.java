package inv.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import common.exception.BusinessException;
import common.exception.ResourceNotFoundException;
import inv.InvServiceApplication;
import inv.checkout.domain.model.Pagamento;
import inv.checkout.domain.model.StatusPagamento;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.messaging.VendaEventHandler;
import inv.checkout.infrastructure.persistence.PagamentoRepository;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.checkout.infrastructure.web.dto.ItemVendaRequest;
import inv.checkout.infrastructure.web.dto.VendaRequest;
import inv.checkout.usecase.PagamentoService;
import inv.checkout.usecase.VendaService;
import inv.finance.domain.model.LancamentoFinanceiro;
import inv.finance.domain.model.TipoLancamento;
import inv.finance.infrastructure.messaging.VendaEstornadaEventListener;
import inv.finance.infrastructure.persistence.LancamentoFinanceiroRepository;
import inv.inventory.domain.model.MovimentacaoEstoque;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.TipoMovimentacao;
import inv.inventory.domain.model.UnidadeMedida;
import inv.inventory.infrastructure.persistence.MovimentacaoRepository;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import inv.shared.config.RabbitMQConfig;
import inv.shared.domain.MetodoPagamento;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de integração ponta a ponta (E2E) cobrindo o Cenário 3 do Runbook DEBT-ARCH-003/E3:
 * Cancelamento de Venda Concluída com troco, recomposição de estoque com DEVOLUCAO,
 * estorno contábil idempotente no Finance e semântica HTTP (200, 409, 503).
 */
@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
@AutoConfigureMockMvc(addFilters = false)
class CancelamentoVendaConcluidaIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("features.cancelamento-venda-concluida.enabled", () -> "true");
        registry.add("finance.projection.diaria.enabled", () -> "false");
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
    private OutboxRepository outboxRepository;

    @MockitoSpyBean
    private MovimentacaoRepository movimentacaoRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VendaService vendaService;

    @Autowired
    private PagamentoService pagamentoService;

    @Autowired
    private VendaEventHandler vendaEventHandler;

    @Autowired
    private VendaEstornadaEventListener vendaEstornadaEventListener;

    @Autowired
    private VendaRepository vendaRepository;

    @Autowired
    private PagamentoRepository pagamentoRepository;

    @Autowired
    private ProdutoRepository produtoRepository;

    @Autowired
    private LancamentoFinanceiroRepository lancamentoRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "pass", List.of(new SimpleGrantedAuthority("ADMIN")))
        );

        Mockito.reset(outboxRepository);
        Mockito.reset(movimentacaoRepository);

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

        vendaService.setCancelamentoConcluidoHabilitado(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Cenário 3 (E3): Venda concluída de 100.00 com troco 20.00 -> 1º POST cancela, devolve estoque, gera outbox; listener grava estorno de -100.00 e zera saldo; replay idempotente; 2º POST retorna 409")
    void cenario3_cancelamentoPontaAPontaComTrocoReplayIdempotenteEConflito() throws Exception {
        // 1. Criar produto: 10 unidades a 100.00 cada
        Produto produto = salvarProduto("7890001000011", "Ração Especial 10kg",
                new BigDecimal("100.00"), new BigDecimal("10.000"), new BigDecimal("2.000"));

        // 2. Iniciar venda de 1 unidade (valorTotal = 100.00)
        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(
                new ItemVendaRequest(produto.getId(), new BigDecimal("1.000"))
        )));
        Long vendaId = venda.getId();

        // 3. Pagamento de 120.00 em dinheiro (troco = 20.00)
        UUID uuidPagamento = UUID.randomUUID();
        Long pagamentoId = pagamentoService.criarIntencaoPagamento(
                vendaId, MetodoPagamento.DINHEIRO, new BigDecimal("120.00"), uuidPagamento);
        pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("120.00"), "NSU-IT-001");

        // 4. Confirmar pagamento via VendaEventHandler e concluir a venda
        String payloadConfirmacao = """
                {"vendaId":%d,"pagamentoId":%d}
                """.formatted(vendaId, pagamentoId);
        vendaEventHandler.handlePagamentoConfirmado(payloadConfirmacao);

        // Verificações da venda concluída
        Venda vendaConcluida = vendaRepository.findById(vendaId).orElseThrow();
        assertThat(vendaConcluida.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);
        assertThat(vendaConcluida.getValorTotal()).isEqualByComparingTo("100.00");
        assertThat(vendaConcluida.getValorPago()).isEqualByComparingTo("120.00");
        assertThat(vendaConcluida.getValorTroco()).isEqualByComparingTo("20.00");

        // Estoque após conclusão: 9 disponíveis, 0 reservados
        Produto produtoConcluido = produtoRepository.findById(produto.getId()).orElseThrow();
        assertThat(produtoConcluido.getEstoqueDisponivel()).isEqualByComparingTo("9.000");
        assertThat(produtoConcluido.getEstoqueReservado()).isEqualByComparingTo("0.000");

        // Projeção financeira da venda criada: saldo = 100.00
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            BigDecimal saldo = jdbcTemplate.queryForObject(
                    "SELECT saldo FROM financial_projection_venda WHERE venda_id = ?",
                    BigDecimal.class, vendaId);
            assertThat(saldo).isNotNull().isEqualByComparingTo("100.00");
        });

        // Limpar registros residuais do outbox para isolar a asserção do evento de cancelamento
        outboxRepository.deleteAll();

        // 5. 1º POST /vendas/{id}/cancelar: deve retornar 200 OK
        mockMvc.perform(post("/vendas/{id}/cancelar", vendaId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(vendaId))
                .andExpect(jsonPath("$.status").value("CANCELADA"))
                .andExpect(jsonPath("$.valorTotal").value(100.00))
                .andExpect(jsonPath("$.valorPago").value(120.00))
                .andExpect(jsonPath("$.valorTroco").value(20.00));

        // Verificações pós 1º cancelamento:
        // Venda CANCELADA
        Venda vendaCancelada = vendaRepository.findById(vendaId).orElseThrow();
        assertThat(vendaCancelada.getStatus()).isEqualTo(StatusVenda.CANCELADA);

        // Estoque recomposto para 10.000 com movimentação DEVOLUCAO
        Produto produtoAposCancelamento = produtoRepository.findById(produto.getId()).orElseThrow();
        assertThat(produtoAposCancelamento.getEstoqueDisponivel()).isEqualByComparingTo("10.000");
        assertThat(produtoAposCancelamento.getEstoqueReservado()).isEqualByComparingTo("0.000");

        List<MovimentacaoEstoque> devolucoes = movimentacaoRepository.findAll().stream()
                .filter(m -> m.getTipo() == TipoMovimentacao.DEVOLUCAO && vendaId.equals(m.getVendaId()))
                .toList();
        assertThat(devolucoes).hasSize(1);
        assertThat(devolucoes.getFirst().getQuantidade()).isEqualByComparingTo("1.000");

        // Pagamento em ESTORNADO
        Pagamento pagamentoEstornado = pagamentoRepository.findById(pagamentoId).orElseThrow();
        assertThat(pagamentoEstornado.getStatus()).isEqualTo(StatusPagamento.ESTORNADO);

        // Evento VendaEstornada persistido no Outbox
        List<Outbox> outboxList = outboxRepository.findAll().stream()
                .filter(o -> RabbitMQConfig.ROUTING_KEY_VENDA_ESTORNADA.equals(o.getRoutingKey()))
                .toList();
        assertThat(outboxList).hasSize(1);
        Outbox outboxMsg = outboxList.getFirst();
        assertThat(outboxMsg.getExchange()).isEqualTo(RabbitMQConfig.PDV_EXCHANGE);

        // 6. Consumo no VendaEstornadaEventListener
        vendaEstornadaEventListener.handleVendaEstornada(outboxMsg.getPayload());

        // Lançamento contábil de estorno no Finance
        List<LancamentoFinanceiro> lancamentosEstorno = lancamentoRepository.findAll().stream()
                .filter(l -> l.getVendaId().equals(vendaId) && l.getTipo() == TipoLancamento.ESTORNO)
                .toList();
        assertThat(lancamentosEstorno).hasSize(1);
        LancamentoFinanceiro lancamentoEstorno = lancamentosEstorno.getFirst();
        assertThat(lancamentoEstorno.getValor()).isEqualByComparingTo("-100.00");
        UUID uuidEsperado = UUID.nameUUIDFromBytes(("ESTORNO:" + vendaId).getBytes(StandardCharsets.UTF_8));
        assertThat(lancamentoEstorno.getReferencia()).isEqualTo(uuidEsperado);

        // Projeção financeira da venda zerada (saldo == 0.00, total_creditos == 100.00, total_estornos == 100.00)
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            BigDecimal saldo = jdbcTemplate.queryForObject(
                    "SELECT saldo FROM financial_projection_venda WHERE venda_id = ?",
                    BigDecimal.class, vendaId);
            BigDecimal totalCreditos = jdbcTemplate.queryForObject(
                    "SELECT total_creditos FROM financial_projection_venda WHERE venda_id = ?",
                    BigDecimal.class, vendaId);
            BigDecimal totalEstornos = jdbcTemplate.queryForObject(
                    "SELECT total_estornos FROM financial_projection_venda WHERE venda_id = ?",
                    BigDecimal.class, vendaId);

            assertThat(saldo).isEqualByComparingTo("0.00");
            assertThat(totalCreditos).isEqualByComparingTo("100.00");
            assertThat(totalEstornos).isEqualByComparingTo("100.00");
        });

        // 7. Reentrega do evento no listener: valida idempotência estrita sem duplicar lançamento
        vendaEstornadaEventListener.handleVendaEstornada(outboxMsg.getPayload());

        List<LancamentoFinanceiro> lancamentosAposReentrega = lancamentoRepository.findAll().stream()
                .filter(l -> l.getVendaId().equals(vendaId) && l.getTipo() == TipoLancamento.ESTORNO)
                .toList();
        assertThat(lancamentosAposReentrega).hasSize(1);

        BigDecimal saldoAposReentrega = jdbcTemplate.queryForObject(
                "SELECT saldo FROM financial_projection_venda WHERE venda_id = ?",
                BigDecimal.class, vendaId);
        assertThat(saldoAposReentrega).isEqualByComparingTo("0.00");

        // 8. 2º POST /vendas/{id}/cancelar: deve retornar 409 Conflict
        mockMvc.perform(post("/vendas/{id}/cancelar", vendaId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensagem").value("Venda #" + vendaId + " não pode ser cancelada pois está no status: CANCELADA"));
    }

    @Test
    @DisplayName("Cenário 3 (E3): POST /vendas/{id}/cancelar com feature flag desabilitada deve retornar HTTP 503")
    void cenario3_deveRetornar503QuandoFeatureFlagDesabilitada() throws Exception {
        Produto produto = salvarProduto("7890001000028", "Sachê Gato 85g",
                new BigDecimal("5.00"), new BigDecimal("20.000"), new BigDecimal("5.000"));

        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(
                new ItemVendaRequest(produto.getId(), new BigDecimal("2.000"))
        )));
        Long vendaId = venda.getId();

        Long pagamentoId = pagamentoService.criarIntencaoPagamento(
                vendaId, MetodoPagamento.CARTAO_DEBITO, new BigDecimal("10.00"), UUID.randomUUID());
        pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("10.00"), "NSU-IT-002");
        vendaEventHandler.handlePagamentoConfirmado("""
                {"vendaId":%d,"pagamentoId":%d}
                """.formatted(vendaId, pagamentoId));

        // Desabilita a feature flag
        vendaService.setCancelamentoConcluidoHabilitado(false);

        mockMvc.perform(post("/vendas/{id}/cancelar", vendaId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.mensagem").value("Cancelamento de venda concluída temporariamente desabilitado"));

        // Venda permanece CONCLUIDA
        Venda vendaAposTentativa = vendaRepository.findById(vendaId).orElseThrow();
        assertThat(vendaAposTentativa.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);
    }

    @Test
    @DisplayName("Cenário 3 (E3): Falha simulada no Outbox reverte integralmente a transação mantendo a venda CONCLUIDA")
    void cenario3_falhaSimuladaNoOutboxReverteIntegralmenteTransacaoMantendoVendaConcluida() throws Exception {
        Produto produto = salvarProduto("7890001000035", "Petisco Canino 100g",
                new BigDecimal("15.00"), new BigDecimal("10.000"), new BigDecimal("2.000"));

        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(
                new ItemVendaRequest(produto.getId(), new BigDecimal("1.000"))
        )));
        Long vendaId = venda.getId();

        Long pagamentoId = pagamentoService.criarIntencaoPagamento(
                vendaId, MetodoPagamento.PIX, new BigDecimal("15.00"), UUID.randomUUID());
        pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("15.00"), "NSU-IT-003");
        vendaEventHandler.handlePagamentoConfirmado("""
                {"vendaId":%d,"pagamentoId":%d}
                """.formatted(vendaId, pagamentoId));

        outboxRepository.deleteAll();

        // Simula falha ao salvar no outbox durante o BEFORE_COMMIT
        doThrow(new RuntimeException("Simulação de indisponibilidade no Outbox"))
                .when(outboxRepository).save(any());

        mockMvc.perform(post("/vendas/{id}/cancelar", vendaId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        // Rollback total: venda permanece CONCLUIDA
        Venda vendaAposFalha = vendaRepository.findById(vendaId).orElseThrow();
        assertThat(vendaAposFalha.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);

        // Pagamento permanece CONFIRMADO
        Pagamento pagamentoAposFalha = pagamentoRepository.findById(pagamentoId).orElseThrow();
        assertThat(pagamentoAposFalha.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);

        // Estoque permanece 9.000 (não foi devolvido)
        Produto produtoAposFalha = produtoRepository.findById(produto.getId()).orElseThrow();
        assertThat(produtoAposFalha.getEstoqueDisponivel()).isEqualByComparingTo("9.000");

        // Nenhuma movimentação DEVOLUCAO
        List<MovimentacaoEstoque> devolucoes = movimentacaoRepository.findAll().stream()
                .filter(m -> m.getTipo() == TipoMovimentacao.DEVOLUCAO && vendaId.equals(m.getVendaId()))
                .toList();
        assertThat(devolucoes).isEmpty();
    }

    @Test
    @DisplayName("Cenário 3 (E3): Falha simulada em devolverEstoque reverte integralmente a transação mantendo a venda CONCLUIDA")
    void cenario3_falhaSimuladaNaDevolucaoEstoqueReverteIntegralmenteTransacaoMantendoVendaConcluida() throws Exception {
        Produto produto = salvarProduto("7890001000042", "Coleira Ajustável",
                new BigDecimal("30.00"), new BigDecimal("10.000"), new BigDecimal("2.000"));

        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(
                new ItemVendaRequest(produto.getId(), new BigDecimal("1.000"))
        )));
        Long vendaId = venda.getId();

        Long pagamentoId = pagamentoService.criarIntencaoPagamento(
                vendaId, MetodoPagamento.PIX, new BigDecimal("30.00"), UUID.randomUUID());
        pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("30.00"), "NSU-IT-004");
        vendaEventHandler.handlePagamentoConfirmado("""
                {"vendaId":%d,"pagamentoId":%d}
                """.formatted(vendaId, pagamentoId));

        outboxRepository.deleteAll();

        // Simula falha ao registrar movimentação de devolução durante devolverEstoque
        doThrow(new RuntimeException("Simulação de falha física na persistência de devolução de estoque"))
                .when(movimentacaoRepository).save(any());

        mockMvc.perform(post("/vendas/{id}/cancelar", vendaId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError());

        // Rollback total: venda permanece CONCLUIDA
        Venda vendaAposFalha = vendaRepository.findById(vendaId).orElseThrow();
        assertThat(vendaAposFalha.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);

        // Pagamento permanece CONFIRMADO
        Pagamento pagamentoAposFalha = pagamentoRepository.findById(pagamentoId).orElseThrow();
        assertThat(pagamentoAposFalha.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);

        // Zero outbox gerado
        List<Outbox> outboxList = outboxRepository.findAll().stream()
                .filter(o -> RabbitMQConfig.ROUTING_KEY_VENDA_ESTORNADA.equals(o.getRoutingKey()))
                .toList();
        assertThat(outboxList).isEmpty();
    }

    @Test
    @DisplayName("Cenário 3 (E3): POST /vendas/{id}/cancelar para venda inexistente deve retornar HTTP 404 Not Found")
    void cenario3_deveRetornar404QuandoVendaInexistente() throws Exception {
        mockMvc.perform(post("/vendas/{id}/cancelar", 999999L)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso não encontrado"))
                .andExpect(jsonPath("$.detail").value("Venda não encontrada: 999999"));
    }

    @Test
    @DisplayName("Cenário 3 (E3): POST /vendas/{id}/cancelar para venda em status AGUARDANDO_PAGAMENTO deve retornar HTTP 409 Conflict")
    void cenario3_deveRetornar409QuandoVendaEmStatusAguardandoPagamento() throws Exception {
        Produto produto = salvarProduto("7890001000059", "Brinquedo Mordedor",
                new BigDecimal("25.00"), new BigDecimal("10.000"), new BigDecimal("2.000"));

        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(
                new ItemVendaRequest(produto.getId(), new BigDecimal("1.000"))
        )));
        Long vendaId = venda.getId();

        mockMvc.perform(post("/vendas/{id}/cancelar", vendaId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensagem").value("Venda #" + vendaId + " não pode ser cancelada pois está no status: AGUARDANDO_PAGAMENTO"));

        // Venda permanece AGUARDANDO_PAGAMENTO
        Venda vendaAposTentativa = vendaRepository.findById(vendaId).orElseThrow();
        assertThat(vendaAposTentativa.getStatus()).isEqualTo(StatusVenda.AGUARDANDO_PAGAMENTO);
    }

    private Produto salvarProduto(String codigoBarras, String nome, BigDecimal preco, BigDecimal disponivel, BigDecimal minimo) {
        Produto p = new Produto();
        p.setCodigoBarras(codigoBarras);
        p.setNome(nome);
        p.setUnidadeMedida(UnidadeMedida.UN);
        p.setPrecoVenda(preco);
        p.setEstoqueDisponivel(disponivel);
        p.setEstoqueReservado(BigDecimal.ZERO);
        p.setEstoqueMinimo(minimo);
        return produtoRepository.saveAndFlush(p);
    }
}
