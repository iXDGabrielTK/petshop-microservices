# ADR-0011: Open Host Service Síncrono de Comandos entre Contextos (Checkout → Inventory)

* **Status:** Aceito
* **Data:** 2026-10-28
* **Autores:** Equipe de Engenharia
* **Bounded Contexts Afetados:** Checkout, Inventory
* **Serviços:** `apps/inv-service`

---

## 1. Contexto e Problema (Context & Problem Statement)

Historicamente, o contexto de vendas (`inv.checkout`) dependia diretamente das classes internas do contexto de estoque (`inv.inventory`) para validar catálogo, reservar unidades na abertura da venda, confirmar a baixa na liquidação do pagamento e estornar reservas na expiração:

1. **Acoplamento de Entidade JPA entre Contextos (F3):** `ItemVenda` declarava `@ManyToOne(fetch = LAZY) Produto produto` e `Venda.adicionarItem(Produto, BigDecimal)` acessava métodos da entidade de `inventory.domain.model`, exigindo o congelamento da regra `R8` no `archunit_store/`.
2. **Injeção de Caso de Uso e Repositório Internos (F1/F4):** `VendaService` e `VendaEventHandler` injetavam diretamente `ProdutoRepository` (`inventory.infrastructure.persistence`) e `EstoqueService` (`inventory.usecase`). A regra `R7` usava `onlyBeAccessed()`, que não detectava campos nem métodos herdados de `CrudRepository` (`findAllById`), gerando falso positivo com 0 violações registradas.
3. **Risco de Deadlock `40P01` em Carrinhos Multi-Item (F5):** Reserva, baixa e estorno iteravam os itens na ordem arbitrária da requisição. Dois carrinhos simultâneos com os mesmos produtos em ordem inversa (`[A, B]` e `[B, A]`) formavam ciclo no grafo de espera dos row locks de `produtos`.
4. **Leitura Obsoleta no Alerta Anti-Spam e Hidratação Desnecessária (F6):** `EstoqueService` dependia da entidade `Produto` carregada antes do lock para obter `saldoAnterior`, sujeito a condição de corrida sob alta concorrência.
5. **Estorno Duplicado / Roubo de Reserva na Expiração (F2):** `cancelarVendaExpirada` não revalidava o status após adquirir o lock pessimista em `vendas`, permitindo que execuções concorrentes estornassem reservas de outras vendas ativas.

---

## 2. Drivers de Decisão (Decision Drivers / Forces)

* **Isolamento Estrito de Bounded Contexts:** `inv.checkout` não deve conhecer entidades JPA (`Produto`), repositórios (`ProdutoRepository`) nem casos de uso internos (`EstoqueService`) de `inv.inventory`.
* **Published Language Pura (`inv.inventory.api`):** Contratos públicos restritos a interfaces, `record`s imutáveis e `enum`s, livres de anotações JPA/Spring Data (regra `R9`), com implementações `package-private` (regras `R10` e `R14`).
* **Consistência Transacional ACID e Baixa Latência no PDV:** A reserva de estoque na abertura da venda e a baixa/estorno devem executar de forma síncrona na mesma transação local do chamador (`Propagation.MANDATORY`), preservando o modelo híbrido da [ADR-0002](0002-atomic-sql-inventory-and-hybrid-locking.md).
* **Eliminação Estrutural de Deadlocks (`40P01`):** Aquisição determinística de row locks em ordem global (`produtoId ASC`) sob posse exclusiva do Inventory.
* **Prontidão para Extração Futura (TCC):** Modelar a conversação entre contextos com semântica *Try-Confirm-Cancel* em lote (1 chamada por fase do ciclo de vida da venda).

---

## 3. Opções Consideradas (Considered Options)

1. **Opção 1: Coreografia Assíncrona por Eventos (Reserva/Baixa Eventual):** O Checkout publica eventos e aguarda confirmação assíncrona de reserva do Inventory.
2. **Opção 2: Porta de Saída no Checkout (`EstoquePort`) ou Chamadas Síncronas Item a Item:** Definir a interface dentro de `checkout` (implementada por `inventory`) ou manter chamadas unitárias por item ocultando apenas a entidade `Produto`.
3. **Opção 3: Open Host Service Síncrono em Lote Ordenado em `inv.inventory.api` (`EstoqueCommandService` + `CatalogoQueryService`) + Referência Escalar (`ItemVenda.produtoId`):** O provedor (`Inventory`) publica contratos segregados de comando em lote e consulta de catálogo em `inv.inventory.api`, implementados por classes `package-private` com `Propagation.MANDATORY` e ordenação interna por `produtoId ASC`, enquanto `ItemVenda` armazena apenas `Long produtoId` e snapshots escalares.

---

## 4. Decisão Tomada e Justificativa (Decision Outcome)

Decidimos adotar a **Opção 3: Open Host Service Síncrono em Lote Ordenado em `inv.inventory.api` + Referência Escalar `ItemVenda.produtoId`**, porque:

* **Direção Unidirecional e Acíclica (`checkout → inventory.api ← inventory.*`):** Segue o mesmo padrão arquitetural estabelecido na [ADR-0010](0010-dashboard-read-only-composition-module.md). O `Inventory` permanece completamente agnóstico ao `Checkout` (regra `R4`), e o `Checkout` depende exclusivamente de `inv.inventory.api` (validado pela whitelist da regra `R13`).
* **Segregação entre Comandos e Consultas (Q2):**
  * [`EstoqueCommandService`](../../apps/inv-service/src/main/java/inv/inventory/api/EstoqueCommandService.java): expõe `reservar(ComandoEstoqueVenda)`, `confirmarBaixa(ComandoEstoqueVenda)` e `estornarReserva(ComandoEstoqueVenda)` (semântica TCC: *Try / Confirm / Cancel*), recebendo lotes de [`ItemComando`](../../apps/inv-service/src/main/java/inv/inventory/api/ItemComando.java) (`produtoId`, `quantidade`).
  * [`CatalogoQueryService`](../../apps/inv-service/src/main/java/inv/inventory/api/CatalogoQueryService.java): expõe `buscarParaCheckout(Set<Long> produtoIds)`, retornando projeções enxutas [`ProdutoCheckoutView`](../../apps/inv-service/src/main/java/inv/inventory/api/ProdutoCheckoutView.java) (`id`, `nome`, `precoVenda`).
* **Encapsulamento Forte (`package-private`):** [`EstoqueService`](../../apps/inv-service/src/main/java/inv/inventory/usecase/EstoqueService.java) e [`JpaCatalogoQueryService`](../../apps/inv-service/src/main/java/inv/inventory/infrastructure/query/JpaCatalogoQueryService.java) possuem visibilidade de pacote, impedindo injeção pelo tipo concreto (garantido pela regra `R14`).
* **Desacoplamento de Domínio sem Migration Estrutural (Q4):** [`ItemVenda`](../../apps/inv-service/src/main/java/inv/checkout/domain/model/ItemVenda.java) substitui `@ManyToOne Produto` por `@Column(name = "produto_id", nullable = false, updatable = false) Long produtoId` e fábrica `ItemVenda.criar(...)`, preservando o schema físico e documentando as FKs de fronteira modular via [`V6__comentarios_fk_fronteira_modular.sql`](../../apps/inv-service/src/main/resources/db/migration/V6__comentarios_fk_fronteira_modular.sql) ([ADR-0001](0001-modular-monolith-bounded-contexts.md)).
* **Erradicação Definitiva da Catraca ArchUnit (Q6):** Permite remover o `FreezingArchRule` de `R7` e `R8` (agora com `onlyHaveDependentClassesThat()`), ativar `R13` e `R14` e excluir o diretório `archunit_store/`.

### Topologia de Pacotes e Contratos (`Checkout → Inventory`)
```plaintext
apps/inv-service/src/main/java/inv/
├── checkout/
│   ├── domain/model/
│   │   ├── Venda.java                          # adicionarItem(Long, String, BigDecimal, BigDecimal)
│   │   └── ItemVenda.java                      # Long produtoId + snapshots escalares (factory ItemVenda.criar)
│   ├── usecase/VendaService.java               # consome CatalogoQueryService e EstoqueCommandService
│   └── infrastructure/messaging/VendaEventHandler.java  # consome EstoqueCommandService
└── inventory/
    ├── api/                                    # Open Host Service / Published Language
    │   ├── EstoqueCommandService.java          # reservar, confirmarBaixa, estornarReserva
    │   ├── ComandoEstoqueVenda.java            # record (vendaId, List<ItemComando>)
    │   ├── ItemComando.java                    # record (produtoId, quantidade > 0)
    │   ├── CatalogoQueryService.java           # buscarParaCheckout(Set<Long>)
    │   ├── ProdutoCheckoutView.java            # record (id, nome, precoVenda)
    │   ├── EstoqueQueryService.java            # leitura para inv.dashboard
    │   └── ResumoEstoque.java
    ├── usecase/EstoqueService.java             # package-private, @Transactional(MANDATORY), ordena por produtoId ASC
    └── infrastructure/
        ├── persistence/{ProdutoRepository, ProdutoMetadadosEstoque}
        └── query/{JpaCatalogoQueryService, JpaEstoqueQueryService}  # package-private
```

### Consequências e Trade-offs

* **Positivas (+):**
  * **Zero acoplamento a internos:** `Checkout` não importa nenhuma classe de `inv.inventory.{domain,usecase,infrastructure}`.
  * **Eliminação de Deadlocks Multi-Item:** A ordenação por `produtoId ASC` é garantida num único ponto (`EstoqueService`) para todas as operações de estoque.
  * **Economia de Queries e Precisão no Anti-Spam:** `EstoqueService.reservar` executa apenas `1 SELECT` de metadados + `N UPDATE`s atômicos + `N INSERT`s de movimentação (usando `getReferenceById` e pré-imagem `novoSaldo + qtd`), sem carregar entidades `Produto`.
* **Negativas / Desafios (-):**
  * Com a ordenação por `produtoId ASC`, quando 2 ou mais produtos do mesmo carrinho não têm estoque suficiente, a mensagem `"Estoque insuficiente para o produto: <nome>"` reporta o primeiro produto faltante em ordem de `produtoId` (documentado na decisão `Q9`).

---

## 5. Prós e Contras das Opções Analisadas (Pros & Cons)

### Opção 1: Coreografia Assíncrona por Eventos
* **Prós:** Desacoplamento temporal completo entre os módulos.
* **Contras:** Perde a resposta síncrona de reserva exigida pelo PDV, requer estado intermediário `RESERVA_PENDENTE`, Sagas de compensação e pode aprovar abertura de venda que será cancelada instantes depois.

### Opção 2: Porta de Saída no Checkout (`EstoquePort`) ou Chamadas Item a Item
* **Prós:** Oculta a entidade `Produto` rapidamente.
* **Contras:** Uma porta definida em `checkout` e implementada em `inventory` inverte a dependência e cria ciclo entre módulos (`R4`); chamadas item a item deixam a ordenação de locks sob responsabilidade do chamador e multiplicam round-trips numa futura extração para RPC.

### Opção 3: OHS Síncrono em Lote Ordenado em `inv.inventory.api` + `ItemVenda.produtoId` (Escolhida)
* **Prós:** Mantém ACID local (`MANDATORY`), elimina deadlocks `40P01` por construção, respeita estritamente as 14 regras do ArchUnit e modela 1 chamada em lote por fase TCC.
* **Contras:** Requer que o chamador abra a transação antes de invocar `EstoqueCommandService`.

---

## 6. Estratégia de Validação & Testes de Carga/Concorrência (Verification & Testing)

> [!IMPORTANT]
> A decisão é validada continuamente pela suíte do ArchUnit (`R1`–`R14` sem `freeze`) e por testes de integração e concorrência com Testcontainers (PostgreSQL 15).

### Cenários de Teste Automatizados
1. **Governança Arquitetural (`R1`–`R14`):** [`ArquiteturaTest`](../../apps/inv-service/src/test/java/inv/architect/ArquiteturaTest.java) valida confinamento de repositórios (`R7`) e entidades (`R8`) via `onlyHaveDependentClassesThat()`, pureza da API (`R9`), whitelist estrita de `checkout` sobre `inv.inventory.api` (`R13`) e visibilidade `package-private` de todas as implementações de `*.api` (`R14`).
2. **Contrato e Contagem de SQL:** [`EstoqueCommandServiceIT`](../../apps/inv-service/src/test/java/inv/inventory/EstoqueCommandServiceIT.java) valida `Propagation.MANDATORY`, o ciclo completo *Try-Confirm-Cancel*, rollback integral em falha parcial de lote e estatísticas do Hibernate (`1 SELECT` de metadados + `N UPDATE` + `N INSERT`, `0` loads de entidade `Produto`).
3. **Anti-Deadlock sob Barreira e Controle Negativo:** [`CarrinhosInvertidosDeadlockIT`](../../apps/inv-service/src/test/java/inv/it/CarrinhosInvertidosDeadlockIT.java) dispara 40 threads simultâneas (`20× [A, B]` vs `20× [B, A]`) sincronizadas por `CyclicBarrier` com `0` deadlocks em `pg_stat_database.deadlocks`, além de teste de controle negativo que comprova a ocorrência determinística do SQLState `40P01` sem ordenação.
4. **Concorrência Multi-Item e Corrida Pagamento × Expiração:** [`EstoqueMultiItemConcorrenciaIT`](../../apps/inv-service/src/test/java/inv/it/EstoqueMultiItemConcorrenciaIT.java), [`HandlerExpiracaoMesmaVendaIT`](../../apps/inv-service/src/test/java/inv/it/HandlerExpiracaoMesmaVendaIT.java) e [`EstoqueConcorrenciaIntegrationTest`](../../apps/inv-service/src/test/java/inv/it/EstoqueConcorrenciaIntegrationTest.java) validam zero *overselling*, atomicidade de lote e exclusão mútua entre liquidação e expiração.

---

## 7. Referências e Links Relevantes

* [Plano Executivo e Runbooks DEBT-ARCH-002](../debt/DEBT-ARCH-002/README.md)
* [Contrato EstoqueCommandService](../../apps/inv-service/src/main/java/inv/inventory/api/EstoqueCommandService.java)
* [Contrato CatalogoQueryService](../../apps/inv-service/src/main/java/inv/inventory/api/CatalogoQueryService.java)
* [Implementação EstoqueService](../../apps/inv-service/src/main/java/inv/inventory/usecase/EstoqueService.java)
* [Suíte ArchUnit ArquiteturaTest](../../apps/inv-service/src/test/java/inv/architect/ArquiteturaTest.java)
* [ADR-0001: Monólito Modular com Bounded Contexts](0001-modular-monolith-bounded-contexts.md)
* [ADR-0002: Controle Híbrido de Concorrência e Estoque Atômico](0002-atomic-sql-inventory-and-hybrid-locking.md)
* [ADR-0010: Dashboard como Módulo de Composição de Leitura](0010-dashboard-read-only-composition-module.md)
