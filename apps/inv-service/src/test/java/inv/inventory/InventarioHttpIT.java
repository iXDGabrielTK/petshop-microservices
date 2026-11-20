/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory;

import inv.InvServiceApplication;
import inv.inventory.domain.model.MovimentacaoEstoque;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.TipoMovimentacao;
import inv.inventory.domain.model.UnidadeMedida;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class, properties = "spring.jpa.open-in-view=false")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class InventarioHttpIT {

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
    private org.springframework.test.web.servlet.MockMvc mvc;

    @Autowired
    private inv.inventory.infrastructure.persistence.MovimentacaoRepository movimentacaoRepository;

    @Autowired
    private org.springframework.core.env.Environment environment;

    @Autowired
    private ProdutoRepository produtoRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM itens_venda");
        jdbcTemplate.update("DELETE FROM movimentacoes_estoque");
        produtoRepository.deleteAll();
    }

    @Test
    void projetaPaginaCheiaContaEDesempataSemOsiv() throws Exception {
        assertThat(environment.getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
        Produto produto = salvarProdutoPadrao();
        LocalDateTime instante = LocalDateTime.of(2026, 11, 16, 10, 0);
        var ids = new java.util.ArrayList<Long>();
        for (int i = 0; i < 3; i++) {
            var movimento = new MovimentacaoEstoque(null, produto, null,
                    TipoMovimentacao.ENTRADA, BigDecimal.ONE, instante, "Entrada");
            ids.add(movimentacaoRepository.saveAndFlush(movimento).getId());
        }
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        mvc.perform(get("/movimentacoes").param("page", "0").param("size", "2")
                        .param("sort", "dataHora,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(ids.get(0)))
                .andExpect(jsonPath("$.content[1].id").value(ids.get(1)))
                .andExpect(jsonPath("$.content[0].length()").value(9))
                .andExpect(jsonPath("$.content[0].produtoId").value(produto.getId()))
                .andExpect(jsonPath("$.content[0].produtoNome").value("Ração"))
                .andExpect(jsonPath("$.content[0].codigoBarras").value("7891234567890"))
                .andExpect(jsonPath("$.content[0].vendaId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.content[0].tipo").value("ENTRADA"))
                .andExpect(jsonPath("$.content[0].quantidade").value(1))
                .andExpect(jsonPath("$.content[0].dataHora").value("2026-11-16T10:00:00"))
                .andExpect(jsonPath("$.content[0].observacao").value("Entrada"))
                .andExpect(jsonPath("$.content[0].produto").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        assertThat(stats.getQueryExecutionCount()).isEqualTo(2);
        assertThat(stats.getEntityLoadCount()).isZero();
        mvc.perform(get("/movimentacoes").param("page", "1").param("size", "2")
                        .param("sort", "dataHora,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(ids.get(2)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/produtos").param("page", "-1").param("size", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.content[0].length()").value(6))
                .andExpect(jsonPath("$.content[0].estoqueDisponivel").value(10));
    }

    @Test
    void ordenaEfetivamentePorCadaCampoAceitoNoPostgresql() throws Exception {
        Produto produto = salvarProdutoPadrao();

        Long id1 = movimentacaoRepository.saveAndFlush(new MovimentacaoEstoque(
                null, produto, null, TipoMovimentacao.SAIDA, new BigDecimal("2.000"),
                LocalDateTime.of(2026, 11, 16, 12, 0), "M1")).getId();
        Long id2 = movimentacaoRepository.saveAndFlush(new MovimentacaoEstoque(
                null, produto, null, TipoMovimentacao.ENTRADA, new BigDecimal("5.000"),
                LocalDateTime.of(2026, 11, 16, 10, 0), "M2")).getId();
        Long id3 = movimentacaoRepository.saveAndFlush(new MovimentacaoEstoque(
                null, produto, null, TipoMovimentacao.RESERVA, new BigDecimal("1.000"),
                LocalDateTime.of(2026, 11, 16, 13, 0), "M3")).getId();
        Long id4 = movimentacaoRepository.saveAndFlush(new MovimentacaoEstoque(
                null, produto, null, TipoMovimentacao.DEVOLUCAO, new BigDecimal("10.000"),
                LocalDateTime.of(2026, 11, 16, 11, 0), "M4")).getId();

        assertThat(id1).isLessThan(id2);
        assertThat(id2).isLessThan(id3);
        assertThat(id3).isLessThan(id4);

        esperarOrdemIds("id,asc", List.of(id1, id2, id3, id4));
        esperarOrdemIds("id,desc", List.of(id4, id3, id2, id1));
        esperarOrdemIds("dataHora,asc", List.of(id2, id4, id1, id3));
        esperarOrdemIds("dataHora,desc", List.of(id3, id1, id4, id2));
        esperarOrdemIds("tipo,asc", List.of(id4, id2, id3, id1));
        esperarOrdemIds("tipo,desc", List.of(id1, id3, id2, id4));
        esperarOrdemIds("quantidade,asc", List.of(id3, id1, id2, id4));
        esperarOrdemIds("quantidade,desc", List.of(id4, id2, id1, id3));
    }

    private Produto salvarProdutoPadrao() {
        Produto produto = new Produto();
        produto.setCodigoBarras("7891234567890");
        produto.setNome("Ração");
        produto.setUnidadeMedida(UnidadeMedida.UN);
        produto.setPrecoVenda(new BigDecimal("50.00"));
        produto.setEstoqueDisponivel(BigDecimal.TEN);
        return produtoRepository.saveAndFlush(produto);
    }

    private void esperarOrdemIds(String sort, List<Long> idsEsperados) throws Exception {
        var result = mvc.perform(get("/movimentacoes").param("sort", sort))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(idsEsperados.size()));
        for (int i = 0; i < idsEsperados.size(); i++) {
            result.andExpect(jsonPath("$.content[" + i + "].id").value(idsEsperados.get(i)));
        }
    }
}
