package inv.it;

import common.exception.BusinessException;
import common.exception.ResourceNotFoundException;
import inv.InvServiceApplication;
import inv.checkout.domain.model.ItemVenda;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.messaging.VendaEventHandler;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.checkout.infrastructure.web.dto.ItemVendaRequest;
import inv.checkout.infrastructure.web.dto.VendaRequest;
import inv.checkout.usecase.PagamentoService;
import inv.checkout.usecase.VendaService;
import inv.inventory.domain.model.MovimentacaoEstoque;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.TipoMovimentacao;
import inv.inventory.domain.model.UnidadeMedida;
import inv.inventory.infrastructure.messaging.EstoqueAtingiuMinimoEvent;
import inv.inventory.infrastructure.persistence.MovimentacaoRepository;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import inv.shared.config.RabbitMQConfig;
import inv.shared.domain.MetodoPagamento;
import inv.shared.outbox.repository.OutboxRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
@AutoConfigureMockMvc(addFilters = false)
@RecordApplicationEvents
class CicloVendaEstoqueCharacterizationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
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

    @Autowired
    private VendaService vendaService;

    @Autowired
    private PagamentoService pagamentoService;

    @Autowired
    private VendaEventHandler vendaEventHandler;

    @Autowired
    private ProdutoRepository produtoRepository;

    @Autowired
    private VendaRepository vendaRepository;

    @Autowired
    private MovimentacaoRepository movimentacaoRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Validator validator;

    @Autowired
    private ApplicationEvents applicationEvents;

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
        applicationEvents.clear();
    }

    @Test
    @DisplayName("1. Abertura + reserva: saldos corretos, snapshots de nome e preço, valorTotal e movimentação RESERVA")
    void cenario1_aberturaEReservaDeEstoque() {
        Produto produto = salvarProduto("7890001000011", "Ração Super Premium 15kg",
                new BigDecimal("120.50"), new BigDecimal("10.000"), new BigDecimal("2.000"));

        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(
                new ItemVendaRequest(produto.getId(), new BigDecimal("2.000"))
        )));

        assertThat(venda.getStatus()).isEqualTo(StatusVenda.AGUARDANDO_PAGAMENTO);
        assertThat(venda.getValorTotal()).isEqualByComparingTo("241.00");
        assertThat(venda.getItens()).hasSize(1);

        ItemVenda item = venda.getItens().getFirst();
        assertThat(item.getNomeProdutoSnapshot()).isEqualTo("Ração Super Premium 15kg");
        assertThat(item.getPrecoUnitarioSnapshot()).isEqualByComparingTo("120.50");
        assertThat(item.getQuantidade()).isEqualByComparingTo("2.000");
        assertThat(item.getSubTotal()).isEqualByComparingTo("241.00");

        Produto atualizado = produtoRepository.findById(produto.getId()).orElseThrow();
        assertThat(atualizado.getEstoqueDisponivel()).isEqualByComparingTo("8.000");
        assertThat(atualizado.getEstoqueReservado()).isEqualByComparingTo("2.000");

        List<MovimentacaoEstoque> movs = movimentacaoRepository.findAll();
        assertThat(movs).hasSize(1);
        MovimentacaoEstoque mov = movs.getFirst();
        assertThat(mov.getTipo()).isEqualTo(TipoMovimentacao.RESERVA);
        assertThat(mov.getQuantidade()).isEqualByComparingTo("2.000");
        assertThat(mov.getVendaId()).isEqualTo(venda.getId());
        assertThat(mov.getObservacao()).isEqualTo("Reserva de estoque para Venda #" + venda.getId());
    }

    @Test
    @DisplayName("2. Pagamento + baixa: venda CONCLUIDA, reservado decrementado e movimentação SAIDA")
    void cenario2_pagamentoEBaixaDefinitiva() {
        Produto produto = salvarProduto("7890001000028", "Areia Higiênica 4kg",
                new BigDecimal("35.00"), new BigDecimal("10.000"), new BigDecimal("2.000"));

        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(
                new ItemVendaRequest(produto.getId(), new BigDecimal("3.000"))
        )));

        Long pagamentoId = pagamentoService.criarIntencaoPagamento(
                venda.getId(), MetodoPagamento.PIX, new BigDecimal("105.00"), UUID.randomUUID());
        pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("105.00"), "NSU-CHAR-02");

        String payload = """
                {"vendaId":%d,"pagamentoId":%d}
                """.formatted(venda.getId(), pagamentoId);

        vendaEventHandler.handlePagamentoConfirmado(payload);

        Venda vendaConcluida = vendaRepository.findById(venda.getId()).orElseThrow();
        assertThat(vendaConcluida.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);
        assertThat(vendaConcluida.getValorPago()).isEqualByComparingTo("105.00");
        assertThat(vendaConcluida.getDataHoraConclusao()).isNotNull();

        Produto atualizado = produtoRepository.findById(produto.getId()).orElseThrow();
        assertThat(atualizado.getEstoqueDisponivel()).isEqualByComparingTo("7.000");
        assertThat(atualizado.getEstoqueReservado()).isEqualByComparingTo("0.000");

        List<MovimentacaoEstoque> saidas = movimentacaoRepository.findAll().stream()
                .filter(m -> m.getTipo() == TipoMovimentacao.SAIDA)
                .toList();
        assertThat(saidas).hasSize(1);
        MovimentacaoEstoque saida = saidas.getFirst();
        assertThat(saida.getQuantidade()).isEqualByComparingTo("3.000");
        assertThat(saida.getVendaId()).isEqualTo(venda.getId());
        assertThat(saida.getObservacao()).isEqualTo("Baixa definitiva da Venda #" + venda.getId());
    }

    @Test
    @DisplayName("3. Expiração + estorno: venda CANCELADA, disponível restaurado e movimentação CANCELAMENTO_RESERVA")
    void cenario3_expiracaoEEstornoDeReserva() {
        Produto produto = salvarProduto("7890001000035", "Brinquedo Mordedor",
                new BigDecimal("25.00"), new BigDecimal("10.000"), new BigDecimal("2.000"));

        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(
                new ItemVendaRequest(produto.getId(), new BigDecimal("4.000"))
        )));

        vendaService.cancelarVendaExpirada(venda.getId());

        Venda vendaCancelada = vendaRepository.findById(venda.getId()).orElseThrow();
        assertThat(vendaCancelada.getStatus()).isEqualTo(StatusVenda.CANCELADA);

        Produto atualizado = produtoRepository.findById(produto.getId()).orElseThrow();
        assertThat(atualizado.getEstoqueDisponivel()).isEqualByComparingTo("10.000");
        assertThat(atualizado.getEstoqueReservado()).isEqualByComparingTo("0.000");

        List<MovimentacaoEstoque> estornos = movimentacaoRepository.findAll().stream()
                .filter(m -> m.getTipo() == TipoMovimentacao.CANCELAMENTO_RESERVA)
                .toList();
        assertThat(estornos).hasSize(1);
        MovimentacaoEstoque estorno = estornos.getFirst();
        assertThat(estorno.getQuantidade()).isEqualByComparingTo("4.000");
        assertThat(estorno.getVendaId()).isEqualTo(venda.getId());
        assertThat(estorno.getObservacao()).isEqualTo("Estorno de reserva. Venda #" + venda.getId());
    }

    @Test
    @DisplayName("4. Anti-spam: saldo 6, mínimo 5 -> reserva de 1 emite 1 EstoqueAtingiuMinimoEvent; reserva seguinte (5 -> 4) emite 0")
    void cenario4_antiSpamAlertaEstoqueMinimo() {
        Produto produto = salvarProduto("7890001000042", "Shampoo Antipulgas",
                new BigDecimal("40.00"), new BigDecimal("6.000"), new BigDecimal("5.000"));

        applicationEvents.clear();

        // 1ª reserva: saldo cruza de 6.000 (> 5.000) para 5.000 (<= 5.000) -> emite 1 evento
        vendaService.iniciarVenda(new VendaRequest(List.of(
                new ItemVendaRequest(produto.getId(), new BigDecimal("1.000"))
        )));

        List<EstoqueAtingiuMinimoEvent> eventosPrimeiraReserva = applicationEvents
                .stream(EstoqueAtingiuMinimoEvent.class)
                .toList();
        assertThat(eventosPrimeiraReserva).hasSize(1);
        assertThat(eventosPrimeiraReserva.getFirst().payload().nomeProduto()).isEqualTo("Shampoo Antipulgas");
        assertThat(eventosPrimeiraReserva.getFirst().payload().estoqueAtual()).isEqualByComparingTo("5.000");
        assertThat(eventosPrimeiraReserva.getFirst().payload().estoqueMinimo()).isEqualByComparingTo("5.000");

        applicationEvents.clear();

        // 2ª reserva: saldo cai de 5.000 para 4.000 (já estava <= mínimo) -> emite 0 eventos
        vendaService.iniciarVenda(new VendaRequest(List.of(
                new ItemVendaRequest(produto.getId(), new BigDecimal("1.000"))
        )));

        assertThat(applicationEvents.stream(EstoqueAtingiuMinimoEvent.class).count()).isZero();
    }

    @Test
    @DisplayName("5. Estoque insuficiente: mensagem exata e rollback total (sem vendas, itens_venda nem movimentações)")
    void cenario5_estoqueInsuficienteFazRollbackTotal() {
        Produto produto = salvarProduto("7890001000059", "Coleira Antiparasitária",
                new BigDecimal("85.00"), new BigDecimal("2.000"), new BigDecimal("1.000"));

        VendaRequest request = new VendaRequest(List.of(
                new ItemVendaRequest(produto.getId(), new BigDecimal("3.000"))
        ));

        assertThatThrownBy(() -> vendaService.iniciarVenda(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Estoque insuficiente para o produto: Coleira Antiparasitária");

        Produto atualizado = produtoRepository.findById(produto.getId()).orElseThrow();
        assertThat(atualizado.getEstoqueDisponivel()).isEqualByComparingTo("2.000");
        assertThat(atualizado.getEstoqueReservado()).isEqualByComparingTo("0.000");

        assertThat(vendaRepository.count()).isZero();
        Integer itensCount = jdbcTemplate.queryForObject("SELECT count(*) FROM itens_venda", Integer.class);
        assertThat(itensCount).isZero();
        assertThat(movimentacaoRepository.count()).isZero();
    }

    @Test
    @DisplayName("6. Produto inexistente: lança ResourceNotFoundException com mensagem literal")
    void cenario6_produtoInexistenteLancaResourceNotFoundException() {
        VendaRequest request = new VendaRequest(List.of(
                new ItemVendaRequest(999999L, new BigDecimal("1.000"))
        ));

        assertThatThrownBy(() -> vendaService.iniciarVenda(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Um ou mais produtos não foram encontrados.");

        assertThat(vendaRepository.count()).isZero();
        assertThat(movimentacaoRepository.count()).isZero();
    }

    @Test
    @DisplayName("7. Multi-item com falha no 2º item: reserva do 1º item é integralmente revertida")
    void cenario7_multiItemComFalhaNoSegundoItemReverteReservaDoPrimeiro() {
        Produto prod1 = salvarProduto("7890001000073", "Petisco Natural",
                new BigDecimal("20.00"), new BigDecimal("10.000"), new BigDecimal("2.000"));
        Produto prod2 = salvarProduto("7890001000080", "Tapete Higiênico",
                new BigDecimal("60.00"), new BigDecimal("1.000"), new BigDecimal("1.000"));

        VendaRequest request = new VendaRequest(List.of(
                new ItemVendaRequest(prod1.getId(), new BigDecimal("3.000")),
                new ItemVendaRequest(prod2.getId(), new BigDecimal("5.000"))
        ));

        assertThatThrownBy(() -> vendaService.iniciarVenda(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Estoque insuficiente para o produto: Tapete Higiênico");

        Produto prod1Apos = produtoRepository.findById(prod1.getId()).orElseThrow();
        assertThat(prod1Apos.getEstoqueDisponivel()).isEqualByComparingTo("10.000");
        assertThat(prod1Apos.getEstoqueReservado()).isEqualByComparingTo("0.000");

        Produto prod2Apos = produtoRepository.findById(prod2.getId()).orElseThrow();
        assertThat(prod2Apos.getEstoqueDisponivel()).isEqualByComparingTo("1.000");
        assertThat(prod2Apos.getEstoqueReservado()).isEqualByComparingTo("0.000");

        assertThat(vendaRepository.count()).isZero();
        Integer itensCount = jdbcTemplate.queryForObject("SELECT count(*) FROM itens_venda", Integer.class);
        assertThat(itensCount).isZero();
        assertThat(movimentacaoRepository.count()).isZero();
    }

    @Test
    @DisplayName("8. Item duplicado [A:1, A:2]: gera 2 itens de venda e 2 movimentações sem consolidação")
    void cenario8_itemDuplicadoNaoEhConsolidado() {
        Produto prodA = salvarProduto("7890001000097", "Sachê Carne 85g",
                new BigDecimal("5.00"), new BigDecimal("10.000"), new BigDecimal("2.000"));

        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(
                new ItemVendaRequest(prodA.getId(), new BigDecimal("1.000")),
                new ItemVendaRequest(prodA.getId(), new BigDecimal("2.000"))
        )));

        assertThat(venda.getValorTotal()).isEqualByComparingTo("15.00");
        assertThat(venda.getItens()).hasSize(2);

        Integer itensNoBanco = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM itens_venda WHERE venda_id = ?", Integer.class, venda.getId());
        assertThat(itensNoBanco).isEqualTo(2);

        Produto prodAAtualizado = produtoRepository.findById(prodA.getId()).orElseThrow();
        assertThat(prodAAtualizado.getEstoqueDisponivel()).isEqualByComparingTo("7.000");
        assertThat(prodAAtualizado.getEstoqueReservado()).isEqualByComparingTo("3.000");

        List<MovimentacaoEstoque> movs = movimentacaoRepository.findAll();
        assertThat(movs).hasSize(2);
        assertThat(movs).extracting(MovimentacaoEstoque::getQuantidade)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("1.000"), new BigDecimal("2.000"));
    }

    @Test
    @DisplayName("9. Rollback x alerta: quando transação reverte após 1º item cruzar estoque mínimo, Outbox permanece vazio (BEFORE_COMMIT não persiste)")
    void cenario9_rollbackTransacaoNaoEntregaAlertaNoOutbox() {
        Produto prod1 = salvarProduto("7890001000103", "Ração Gatos Castrados",
                new BigDecimal("90.00"), new BigDecimal("6.000"), new BigDecimal("5.000"));
        Produto prod2 = salvarProduto("7890001000110", "Arranhador Torre",
                new BigDecimal("200.00"), new BigDecimal("1.000"), new BigDecimal("1.000"));

        applicationEvents.clear();

        // prod1 cruza de 6.000 para 5.000 (dispara publishEvent em memória), mas prod2 falha por falta de estoque
        VendaRequest request = new VendaRequest(List.of(
                new ItemVendaRequest(prod1.getId(), new BigDecimal("1.000")),
                new ItemVendaRequest(prod2.getId(), new BigDecimal("2.000"))
        ));

        assertThatThrownBy(() -> vendaService.iniciarVenda(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Estoque insuficiente para o produto: Arranhador Torre");

        // Caracterização: o ApplicationEventPublisher in-memory foi invocado para o 1º item antes da falha,
        // mas como RabbitMQProducerListener usa @TransactionalEventListener(phase = BEFORE_COMMIT)
        // e a transação reverteu, nenhum registro de alerta foi efetivamente persistido na tabela outbox.
        assertThat(applicationEvents.stream(EstoqueAtingiuMinimoEvent.class).count()).isEqualTo(1);
        long alertasNoOutbox = outboxRepository.findAll().stream()
                .filter(o -> RabbitMQConfig.ROUTING_KEY_LOW_STOCK.equals(o.getRoutingKey()))
                .count();
        assertThat(alertasNoOutbox).isZero();
    }

    @Test
    @DisplayName("10. Quantidade <= 0 no request: rejeitado na borda web (Bean Validation @Positive / HTTP 400)")
    void cenario10_quantidadeMenorOuIgualAZeroRejeitadaNaBordaWeb() throws Exception {
        Produto produto = salvarProduto("7890001000127", "Comedouro Inox",
                new BigDecimal("30.00"), new BigDecimal("10.000"), new BigDecimal("2.000"));

        // 1. Bean Validation direta sobre VendaRequest com quantidade 0 e negativa
        VendaRequest reqZero = new VendaRequest(List.of(
                new ItemVendaRequest(produto.getId(), BigDecimal.ZERO)
        ));
        Set<ConstraintViolation<VendaRequest>> violacoesZero = validator.validate(reqZero);
        assertThat(violacoesZero).isNotEmpty();

        VendaRequest reqNegativa = new VendaRequest(List.of(
                new ItemVendaRequest(produto.getId(), new BigDecimal("-1.000"))
        ));
        Set<ConstraintViolation<VendaRequest>> violacoesNegativa = validator.validate(reqNegativa);
        assertThat(violacoesNegativa).isNotEmpty();

        // 2. Borda HTTP POST /vendas com quantidade 0 e -1 deve retornar 400 Bad Request sem tocar no banco
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, List.of(new SimpleGrantedAuthority("ADMIN"))));
        try {
            mockMvc.perform(post("/vendas")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"itens":[{"produtoId":%d,"quantidade":0}]}
                                    """.formatted(produto.getId())))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(post("/vendas")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"itens":[{"produtoId":%d,"quantidade":-2.500}]}
                                    """.formatted(produto.getId())))
                    .andExpect(status().isBadRequest());
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(vendaRepository.count()).isZero();
        assertThat(movimentacaoRepository.count()).isZero();
        Produto inalterado = produtoRepository.findById(produto.getId()).orElseThrow();
        assertThat(inalterado.getEstoqueDisponivel()).isEqualByComparingTo("10.000");
    }

    private Produto salvarProduto(String codigoBarras, String nome, BigDecimal precoVenda,
                                  BigDecimal disponivel, BigDecimal minimo) {
        Produto produto = new Produto();
        produto.setCodigoBarras(codigoBarras);
        produto.setNome(nome);
        produto.setUnidadeMedida(UnidadeMedida.UN);
        produto.setPrecoVenda(precoVenda);
        produto.setEstoqueDisponivel(disponivel);
        produto.setEstoqueReservado(new BigDecimal("0.000"));
        produto.setEstoqueMinimo(minimo);
        return produtoRepository.saveAndFlush(produto);
    }
}
