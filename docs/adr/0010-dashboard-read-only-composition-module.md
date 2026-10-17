# ADR-0010: Dashboard como Módulo de Composição de Leitura (BFF Interno Read-Only)

* **Status:** Aceito
* **Data:** 2026-10-17
* **Autores:** Equipe de Engenharia
* **Bounded Contexts Afetados:** Dashboard, Checkout, Finance, Inventory, Shared
* **Serviços:** `apps/inv-service`, `apps/common-lib`, `apps/api-gateway`

---

## 1. Contexto e Problema (Context & Problem Statement)

Historicamente, o endpoint executivo `GET /dashboard/stats` residia dentro do contexto financeiro (`inv.finance.usecase.DashboardService`). Para montar o painel gerencial (receita mensal e de hoje, volume de vendas concluídas, itens com estoque baixo, atividade recente do sistema no Redis, gráfico semanal e top 5 vendas recentes), o `DashboardService` injetava diretamente repositórios e entidades transacionais de outros contextos (`VendaRepository`, `Venda`, `ItemVenda`, `ProdutoRepository`, `StringRedisTemplate`).

Esse desenho gerava múltiplos problemas arquiteturais e operacionais identificados na auditoria do **[DEBT-ARCH-001](../debt/DEBT-ARCH-001-desacoplamento-dashboard.md)**:
1. **Violação de Fronteiras DDD:** 22 violações diretas de acoplamento de `finance` em direção a `checkout` e `inventory`, exigindo `@ArchIgnore` nas regras do ArchUnit.
2. **Risco de `LazyInitializationException` e N+1:** Acesso a coleções `@OneToMany(fetch = LAZY)` (`Venda.getItens()`) fora de escopo transacional com `spring.jpa.open-in-view=false`.
3. **Inversão de Autoridade Contábil:** A receita era calculada somando `vendas.valor_total` no `Checkout` (regime de competência), ignorando o Ledger Financeiro (`lancamentos_financeiros`), estornos e retenção líquida de troco.
4. **Acoplamento de Infraestrutura na Camada de Caso de Uso:** Consulta direta a chaves mágicas do Redis (`system:activity`) e retorno de DTOs da camada web diretamente pelo caso de uso.
5. **Fragilidade Sistêmica (Tudo-ou-Nada):** Uma falha transitória no Redis ou em qualquer consulta secundária derrubava o endpoint inteiro com erro HTTP 500.

---

## 2. Drivers de Decisão (Decision Drivers / Forces)

* **Isolamento Estrito de Bounded Contexts:** Nenhum contexto de domínio (`checkout`, `finance`, `inventory`) deve conhecer internos (entidades JPA, repositórios, serviços de domínio) de outro contexto.
* **Published Language Pura (`inv.<contexto>.api`):** A comunicação síncrona de leitura entre módulos deve ocorrer exclusivamente por contratos imutáveis (interfaces, `record`s e `enum`s) livres de anotações JPA, Spring Data ou Jackson.
* **Autoridade Contábil no Finance (Regime de Caixa):** Indicadores de receita devem refletir o dinheiro efetivamente retido no Ledger (`valorRecebido - troco` menos estornos), lido em $O(\text{dias})$ a partir da projeção CQRS diária ([ADR-0006](0006-cqrs-asynchronous-projections-and-self-healing-retry.md)).
* **Resiliência e Degradação Graciosa por Card:** A indisponibilidade parcial de uma fonte (ex: Redis fora do ar) deve degradar apenas o respectivo card (`cardsDegradados` em `meta`), preservando HTTP 200 e os demais indicadores.
* **Baixa Latência e Proteção de Banco:** Consultas SQL dedicadas com índices parciais (`CONCURRENTLY`) e cache de curtíssima duração (Caffeine, TTL 15s) que não retém respostas degradadas.

---

## 3. Opções Consideradas (Considered Options)

1. **Opção 1: Manter o `DashboardService` em `inv.finance` com Portas de Saída:** O `finance` definiria portas próprias e os contextos `checkout` e `inventory` implementariam adaptadores para o `finance`.
2. **Opção 2: Projeção Super-Desnormalizada Única por Eventos:** Um módulo consumidor manteria uma tabela única consolidando vendas, estoque e finanças por eventos assíncronos.
3. **Opção 3: Módulo de Composição `inv.dashboard` (BFF Interno Read-Only) + *Open Host Service* (`inv.<contexto>.api`) + Projeção CQRS Diária no Finance:** Criar um módulo par `inv.dashboard` estritamente de leitura que orquestra contratos publicados (`checkout.api`, `finance.api`, `inventory.api`) e uma porta própria para telemetria Redis (`AtividadeSistemaPort`).

---

## 4. Decisão Tomada e Justificativa (Decision Outcome)

Decidimos adotar a **Opção 3: Módulo de Composição `inv.dashboard` consumindo *Open Host Services* (`*.api`) e Projeção CQRS Diária**, porque:
* **Simetria Modular:** `inv.dashboard` atua como um consumidor *downstream* (Conformist) dos três contextos de negócio, sem estado de escrita próprio e sem ser dependência de nenhum outro módulo (garantido pela regra `R5` do ArchUnit).
* **Encapsulamento Forte (`package-private`):** As implementações `JdbcVendaQueryService`, `ProjecaoReceitaQueryService`, `JpaEstoqueQueryService` e `RedisAtividadeSistemaAdapter` possuem visibilidade de pacote, forçando a injeção exclusivamente pelas interfaces públicas (garantido pela regra `R10`).
* **Eliminação de `LazyInitializationException` e N+1:** `JdbcVendaQueryService` busca as vendas recentes e agrega a contagem de itens via SQL puro (`JdbcClient`) apoiado pelos índices parciais da migration `V5__indices_dashboard.sql`.
* **Governança Automatizada sem Exceções:** Viabilizou a remoção definitiva do `@ArchIgnore` e do `FreezingArchRule` sobre `inv.finance`, ativando 12 regras arquiteturais (`R1`–`R12`) em [`ArquiteturaTest.java`](../../apps/inv-service/src/test/java/inv/architect/ArquiteturaTest.java).

### Topologia do Módulo `inv.dashboard` e Contratos `*.api`
```plaintext
apps/inv-service/src/main/java/inv/
├── dashboard/
│   ├── application/
│   │   ├── DashboardQueryService.java          # Orquestrador read-only (@Cacheable + isolamento por card)
│   │   ├── DashboardSnapshot.java              # Modelo imutável de aplicação (records)
│   │   ├── IndicadoresCalculadora.java         # Funções puras (crescimento MTD vs MTD, rótulos pt-BR)
│   │   └── port/AtividadeSistemaPort.java      # Porta de saída para telemetria de borda
│   └── infrastructure/
│       ├── config/DashboardCacheConfig.java    # Cache Caffeine (TTL 15s, bypass em degradação)
│       ├── redis/RedisAtividadeSistemaAdapter.java
│       └── web/{DashboardController, DashboardStatsMapper, dto/DashboardStats}
├── checkout/api/                               # VendaQueryService, VendaRecenteView
├── finance/api/                                # ReceitaQueryService, PeriodoContabil, ResumoReceita, ...
└── inventory/api/                              # EstoqueQueryService, ResumoEstoque
```

### Consequências e Trade-offs

* **Positivas (+):**
  * **Zero acoplamento entre contextos de domínio:** `Finance`, `Checkout` e `Inventory` evoluem seus modelos internos sem impacto cruzado.
  * **Observabilidade e Resiliência nativas:** Cada card é medido individualmente por `dashboard.card.duration{card}` e falhas incrementam `dashboard.card.failure{card}`, expondo o diagnóstico no bloco aditivo `meta` do payload JSON sem quebrar clientes legados.
  * **Facilidade de Extração Futura:** Caso o `inv-service` seja fatiado em microsserviços independentes no futuro, basta substituir as implementações locais de `*.api` por clientes HTTP/gRPC sem alterar uma linha de `inv.dashboard.application`.
* **Negativas / Desafios (-):**
  * Exige mapeamento explícito entre os records da *Published Language* (`*.api`), o snapshot de aplicação (`DashboardSnapshot`) e o DTO web (`DashboardStats`).

---

## 5. Prós e Contras das Opções Analisadas (Pros & Cons)

### Opção 1: Manter em `inv.finance` com Portas
* **Prós:** Menor número de pacotes de topo.
* **Contras:** Mantém o `Finance` como agregador transversal de conceitos alheios (estoque e pedidos), perpetuando a violação conceitual de Bounded Context.

### Opção 2: Projeção Super-Desnormalizada Única por Eventos
* **Prós:** Leitura em uma única tabela local do dashboard.
* **Contras:** Duplicação desnecessária de estado simples (como contagem de produtos com estoque baixo) e necessidade de novos eventos de integração para qualquer card adicional.

### Opção 3: Módulo `inv.dashboard` + Contratos `*.api` + CQRS Finance (Escolhida)
* **Prós:** Desacoplamento completo, queries otimizadas por contexto, isolamento de falhas por card e proteção estrita via ArchUnit.
* **Contras:** Coordenação em memória de 4 chamadas locais por requisição (mitigada pelo cache Caffeine de 15s e queries indexadas de sub-milissegundo).

---

## 6. Estratégia de Validação & Testes de Carga/Concorrência (Verification & Testing)

> [!IMPORTANT]
> A transição do motor legado para o módulo `inv.dashboard` foi validada por testes de contrato, testes de degradação graciosa, cache e carga sintética de 7 dias com Testcontainers (PostgreSQL 15 e Redis 7).

### Cenários de Teste Automatizados
* **Testes de Fronteira Arquitetural (`R1`–`R12`):** [`ArquiteturaTest`](../../apps/inv-service/src/test/java/inv/architect/ArquiteturaTest.java) garante ausência de ciclos (`R4`), que ninguém depende de `inv.dashboard` (`R5`), que `inv.dashboard` consome apenas `*.api` (`R6`), pureza dos contratos `*.api` (`R9`) e visibilidade `package-private` das implementações de query (`R10`).
* **Carga Sintética de 7 Dias e Cutover:** [`CargaSintetica7DiasCutoverIT`](../../apps/inv-service/src/test/java/inv/dashboard/CargaSintetica7DiasCutoverIT.java) valida vendas à vista, pagamentos divididos, retenção líquida de troco em dinheiro, estornos parciais, lançamentos na fronteira da meia-noite, filtro de vendas faturadas (`CONCLUIDA`, `CANCELAMENTO_SOLICITADO`), reconciliação 100% (`Σ projeção diária == Σ ledger`) e fechamento D+0 sem divergência.
* **Cache e Auto-Cura de Degradação:** [`DashboardCacheIT`](../../apps/inv-service/src/test/java/inv/dashboard/DashboardCacheIT.java) e [`DashboardQueryServiceTest`](../../apps/inv-service/src/test/java/inv/dashboard/application/DashboardQueryServiceTest.java) provam que respostas com `cardsDegradados` não são armazenadas no Caffeine (`unless = "!#result.meta().cardsDegradados().isEmpty()"`).

---

## 7. Referências e Links Relevantes

* [Plano Executivo DEBT-ARCH-001](../debt/DEBT-ARCH-001-desacoplamento-dashboard.md)
* [Orquestrador DashboardQueryService](../../apps/inv-service/src/main/java/inv/dashboard/application/DashboardQueryService.java)
* [Suíte ArchUnit ArquiteturaTest](../../apps/inv-service/src/test/java/inv/architect/ArquiteturaTest.java)
* [ADR-0001: Monólito Modular com Bounded Contexts](0001-modular-monolith-bounded-contexts.md)
* [ADR-0006: Projeções CQRS Assíncronas e Auto-Cura](0006-cqrs-asynchronous-projections-and-self-healing-retry.md)
