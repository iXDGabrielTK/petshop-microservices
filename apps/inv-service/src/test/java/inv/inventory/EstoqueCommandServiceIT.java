package inv.inventory;

import common.exception.BusinessException;
import inv.InvServiceApplication;
import inv.inventory.api.ComandoEstoqueVenda;
import inv.inventory.api.EstoqueCommandService;
import inv.inventory.api.ItemComando;
import inv.inventory.domain.model.MovimentacaoEstoque;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.TipoMovimentacao;
import inv.inventory.domain.model.UnidadeMedida;
import inv.inventory.infrastructure.messaging.EstoqueAtingiuMinimoEvent;
import inv.inventory.infrastructure.persistence.MovimentacaoRepository;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
@RecordApplicationEvents
class EstoqueCommandServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
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
    private EstoqueCommandService estoqueCommandService;

    @Autowired
    private ProdutoRepository produtoRepository;

    @Autowired
    private MovimentacaoRepository movimentacaoRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEvents applicationEvents;

    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(transactionManager);
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
    @DisplayName("Q1 — semTransacaoAtivaFalha: reservar, confirmarBaixa e estornarReserva exigem Propagation.MANDATORY")
    void semTransacaoAtivaFalha() {
        ComandoEstoqueVenda comando = new ComandoEstoqueVenda(1L, List.of(
                new ItemComando(1L, new BigDecimal("1.000"))
        ));

        assertThatThrownBy(() -> estoqueCommandService.reservar(comando))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThatThrownBy(() -> estoqueCommandService.confirmarBaixa(comando))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThatThrownBy(() -> estoqueCommandService.estornarReserva(comando))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("Ciclo completo Try-Confirm-Cancel em lote: saldos, movimentações, observações e alertas idênticos aos métodos legados")
    void cicloCompletoTryConfirmCancelComEfeitosIdenticosAoLegado() {
        Produto p1 = salvarProduto("7891000000011", "Ração Filhote 3kg",
                new BigDecimal("65.00"), new BigDecimal("10.000"), new BigDecimal("8.000"));
        Produto p2 = salvarProduto("7891000000028", "Brinquedo Corda",
                new BigDecimal("19.90"), new BigDecimal("5.000"), new BigDecimal("1.000"));

        long vendaId = criarVendaFixture();
        // Ordem invertida [p2, p1] para exercitar ordenação interna por produtoId ASC
        ComandoEstoqueVenda comando = new ComandoEstoqueVenda(vendaId, List.of(
                new ItemComando(p2.getId(), new BigDecimal("2.000")),
                new ItemComando(p1.getId(), new BigDecimal("2.000"))
        ));

        applicationEvents.clear();

        // 1. Try (reservar): p1 cai de 10 para 8 (== mínimo -> emite alerta), p2 cai de 5 para 3 (> mínimo)
        txTemplate.executeWithoutResult(status -> estoqueCommandService.reservar(comando));

        Produto p1AposReserva = produtoRepository.findById(p1.getId()).orElseThrow();
        assertThat(p1AposReserva.getEstoqueDisponivel()).isEqualByComparingTo("8.000");
        assertThat(p1AposReserva.getEstoqueReservado()).isEqualByComparingTo("2.000");

        Produto p2AposReserva = produtoRepository.findById(p2.getId()).orElseThrow();
        assertThat(p2AposReserva.getEstoqueDisponivel()).isEqualByComparingTo("3.000");
        assertThat(p2AposReserva.getEstoqueReservado()).isEqualByComparingTo("2.000");

        List<EstoqueAtingiuMinimoEvent> alertas = applicationEvents.stream(EstoqueAtingiuMinimoEvent.class).toList();
        assertThat(alertas).hasSize(1);
        assertThat(alertas.getFirst().payload().nomeProduto()).isEqualTo("Ração Filhote 3kg");
        assertThat(alertas.getFirst().payload().estoqueAtual()).isEqualByComparingTo("8.000");

        List<MovimentacaoEstoque> movsReserva = movimentacaoRepository.findAll();
        assertThat(movsReserva).hasSize(2);
        assertThat(movsReserva).allSatisfy(m -> {
            assertThat(m.getTipo()).isEqualTo(TipoMovimentacao.RESERVA);
            assertThat(m.getVendaId()).isEqualTo(vendaId);
            assertThat(m.getObservacao()).isEqualTo("Reserva de estoque para Venda #" + vendaId);
        });

        // 2. Confirm (confirmarBaixa): consome reservado de p1 e p2 e registra SAIDA
        txTemplate.executeWithoutResult(status -> estoqueCommandService.confirmarBaixa(comando));

        Produto p1AposBaixa = produtoRepository.findById(p1.getId()).orElseThrow();
        assertThat(p1AposBaixa.getEstoqueDisponivel()).isEqualByComparingTo("8.000");
        assertThat(p1AposBaixa.getEstoqueReservado()).isEqualByComparingTo("0.000");

        Produto p2AposBaixa = produtoRepository.findById(p2.getId()).orElseThrow();
        assertThat(p2AposBaixa.getEstoqueDisponivel()).isEqualByComparingTo("3.000");
        assertThat(p2AposBaixa.getEstoqueReservado()).isEqualByComparingTo("0.000");

        List<MovimentacaoEstoque> movsSaida = movimentacaoRepository.findAll().stream()
                .filter(m -> m.getTipo() == TipoMovimentacao.SAIDA)
                .toList();
        assertThat(movsSaida).hasSize(2);
        assertThat(movsSaida).allSatisfy(m -> {
            assertThat(m.getVendaId()).isEqualTo(vendaId);
            assertThat(m.getObservacao()).isEqualTo("Baixa definitiva da Venda #" + vendaId);
        });

        // 3. Cancel (estornarReserva) em nova venda
        long vendaIdEstorno = criarVendaFixture();
        ComandoEstoqueVenda comandoEstorno = new ComandoEstoqueVenda(vendaIdEstorno, List.of(
                new ItemComando(p1.getId(), new BigDecimal("3.000"))
        ));
        txTemplate.executeWithoutResult(status -> estoqueCommandService.reservar(comandoEstorno));
        txTemplate.executeWithoutResult(status -> estoqueCommandService.estornarReserva(comandoEstorno));

        Produto p1AposEstorno = produtoRepository.findById(p1.getId()).orElseThrow();
        assertThat(p1AposEstorno.getEstoqueDisponivel()).isEqualByComparingTo("8.000");
        assertThat(p1AposEstorno.getEstoqueReservado()).isEqualByComparingTo("0.000");

        List<MovimentacaoEstoque> movsEstorno = movimentacaoRepository.findAll().stream()
                .filter(m -> m.getTipo() == TipoMovimentacao.CANCELAMENTO_RESERVA)
                .toList();
        assertThat(movsEstorno).hasSize(1);
        assertThat(movsEstorno.getFirst().getVendaId()).isEqualTo(vendaIdEstorno);
        assertThat(movsEstorno.getFirst().getObservacao()).isEqualTo("Estorno de reserva. Venda #" + vendaIdEstorno);
    }

    @Test
    @DisplayName("Falha no 2º item do lote em reservar reverte integralmente o UPDATE e a movimentação do 1º item na mesma transação")
    void falhaEmItemSubsequenteReverteLoteInteiro() {
        Produto p1 = salvarProduto("7891000000035", "Item Com Saldo",
                new BigDecimal("20.00"), new BigDecimal("10.000"), new BigDecimal("2.000"));
        Produto p2 = salvarProduto("7891000000042", "Item Sem Saldo",
                new BigDecimal("30.00"), new BigDecimal("1.000"), new BigDecimal("1.000"));

        long vendaId = criarVendaFixture();
        ComandoEstoqueVenda comando = new ComandoEstoqueVenda(vendaId, List.of(
                new ItemComando(p1.getId(), new BigDecimal("3.000")),
                new ItemComando(p2.getId(), new BigDecimal("5.000"))
        ));

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> estoqueCommandService.reservar(comando)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Estoque insuficiente para o produto: Item Sem Saldo");

        Produto p1Apos = produtoRepository.findById(p1.getId()).orElseThrow();
        assertThat(p1Apos.getEstoqueDisponivel()).isEqualByComparingTo("10.000");
        assertThat(p1Apos.getEstoqueReservado()).isEqualByComparingTo("0.000");
        assertThat(movimentacaoRepository.count()).isZero();
    }

    @Test
    @DisplayName("F6 — Contagem de SQL: reserva de N=3 itens executa 1 SELECT de metadados + 3 UPDATE + 3 INSERT e 0 SELECT de entidade Produto")
    void contagemDeSqlNaReservaDeTresItens() {
        Produto p1 = salvarProduto("7891000000059", "Produto SQL 1",
                new BigDecimal("10.00"), new BigDecimal("10.000"), new BigDecimal("2.000"));
        Produto p2 = salvarProduto("7891000000066", "Produto SQL 2",
                new BigDecimal("20.00"), new BigDecimal("10.000"), new BigDecimal("2.000"));
        Produto p3 = salvarProduto("7891000000073", "Produto SQL 3",
                new BigDecimal("30.00"), new BigDecimal("10.000"), new BigDecimal("2.000"));

        long vendaId = criarVendaFixture();
        ComandoEstoqueVenda comando = new ComandoEstoqueVenda(vendaId, List.of(
                new ItemComando(p3.getId(), new BigDecimal("1.000")),
                new ItemComando(p1.getId(), new BigDecimal("1.000")),
                new ItemComando(p2.getId(), new BigDecimal("1.000"))
        ));

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        txTemplate.executeWithoutResult(status -> {
            entityManager.clear();
            stats.clear();

            estoqueCommandService.reservar(comando);
            entityManager.flush();
        });

        // 1 SELECT (buscarMetadadosEstoque) + 3 UPDATE (reservarEstoqueAtomo) + 3 INSERT (movimentacoes_estoque) = 7 statements
        assertThat(stats.getPrepareStatementCount())
                .as("Reserva de 3 itens deve executar exatamente 1 SELECT de metadados + 3 UPDATE + 3 INSERT")
                .isEqualTo(7L);
        assertThat(stats.getEntityLoadCount())
                .as("Nenhuma entidade Produto deve ser carregada via SELECT individual (getReferenceById usa proxy)")
                .isZero();
        assertThat(stats.getEntityInsertCount())
                .as("Devem ser inseridas exatamente 3 movimentações de estoque")
                .isEqualTo(3L);
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

    private long criarVendaFixture() {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO vendas (data_hora_abertura, valor_total, status, valor_pago, valor_troco, version)
                VALUES (CURRENT_TIMESTAMP, 0.00, 'ABERTA', 0.00, 0.00, 0)
                RETURNING id
                """, Long.class);
        return id != null ? id : 1L;
    }
}
