# 📦 Inv Service (Core Domain & Monólito Modular)

O **Inv Service** é o núcleo de regras de negócio do Pet Shop, estruturado como um **Monólito Modular** (validado por ArchUnit e Spring Modulith) contendo os subdomínios de **Checkout**, **Dashboard**, **Finance** e **Inventory**.

* **Porta Padrão:** `8083`
* **Stack:** Java 21, Spring Boot 3.4.1, Spring Modulith 1.3.2, PostgreSQL 15, Flyway, RabbitMQ, Redis, Spring Security Resource Server.

---

## 🏛️ Submódulos e Bounded Contexts

```plaintext
src/main/java/inv/
├── checkout/       # Vendas, Pagamentos, Webhooks e Long Polling de PDV
├── dashboard/      # Módulo de Composição de Leitura (BFF Interno Read-Only)
├── finance/        # Ledger Append-Only, Projeções CQRS, High Watermark e Fechamento D+0
├── inventory/      # Catálogo de Produtos, Baixas Atômicas em Lote Ordenado e Alertas Anti-Spam
└── shared/         # Transactional Outbox, Configurações de Banco, RabbitMQ e Segurança
```

---

## 🚀 Endpoints Principais

### Checkout & Webhooks
* `GET /vendas/aberta` - Consulta da venda ativa (`ROLE_ADMIN`); retorna 204 quando não houver venda aberta.
* `POST /vendas/{id}/cancelar` - Cancelamento de venda concluída (`ROLE_ADMIN`), com estorno interno e devolução de estoque. Retorna `ProblemDetail` (RFC 7807) com HTTP 409 quando a venda está em estado incompatível ou HTTP 503 quando a flag está desabilitada.
* `POST /vendas` - Abertura de venda e reserva atômica de itens em lote ordenado (`produtoId ASC`).
* `GET /vendas/{id}/status` - Long Polling não-bloqueante (`DeferredResult`) do status da venda para PDVs em checkout.
* `POST /pagamentos/intencao/{vendaId}` - Registro de intenção de pagamento com UUID de idempotência.
* `GET /pagamentos/{id}/status` - Consulta do status da intenção ou confirmação de pagamento.
* `POST /pagamentos/webhook/confirmar/{pagamentoId}` - Webhook de confirmação de pagamento recebido de adquirentes com validação de payload via `ConfirmarPagamentoWebhookRequest` (`@Positive valor_recebido`, `@NotBlank nsu`; respostas RFC 7807 em HTTP 400).
* `POST /pagamentos/webhook/falhar/{pagamentoId}` - Webhook para sinalização de recusa ou falha de processamento na operadora.

Todos os endpoints `/vendas/**` exigem JWT com `ROLE_ADMIN`. O perfil `ROLE_USER` recebe 403.

### Catálogo de Produtos & Auditoria de Estoque
* `POST /produtos` - Cadastro de novos produtos no catálogo (`ROLE_ADMIN`).
* `GET /produtos` - Listagem paginada do catálogo de produtos com suporte a busca por nome ou termo genérico.
* `GET /produtos/{id}` - Consulta detalhada de produto por identificador único.
* `GET /produtos/ean/{ean}` - Busca otimizada de produto por código de barras EAN (8 a 14 dígitos).
* `GET /movimentacoes` - Trilha histórica de auditoria de movimentações de estoque (entradas, saídas, reservas e estornos).

### Dashboard & Analytics (BFF Interno)
* `GET /dashboard/stats` - Visão consolidada de indicadores de faturamento, ticket médio e produtos em baixa para o dashboard gerencial.

---

## 📖 Documentação Detalhada

* 📑 **[Visão Geral da Arquitetura](../../docs/architecture/system-overview.md)**
* 🛒 **[Deep Dive: Bounded Context de Checkout](../../docs/domains/checkout.md)**
* 💰 **[Deep Dive: Bounded Context Financeiro](../../docs/domains/finance.md)**
* 📦 **[Deep Dive: Bounded Context de Estoque](../../docs/domains/inventory.md)**

### ADRs Relevantes
* [ADR-0001: Monólito Modular](../../docs/adr/0001-modular-monolith-bounded-contexts.md)
* [ADR-0002: Concorrência Híbrida & Estoque Atômico](../../docs/adr/0002-atomic-sql-inventory-and-hybrid-locking.md)
* [ADR-0003: Long Polling Não-Bloqueante](../../docs/adr/0003-async-long-polling-pos-deferred-result.md)
* [ADR-0004: Transactional Outbox com SKIP LOCKED](../../docs/adr/0004-transactional-outbox-with-skip-locked.md)
* [ADR-0005: Ledger Financeiro Append-Only](../../docs/adr/0005-append-only-financial-ledger-and-idempotency.md)
* [ADR-0006: Projeções CQRS e Auto-Cura](../../docs/adr/0006-cqrs-asynchronous-projections-and-self-healing-retry.md)
* [ADR-0007: Fechamento de Caixa D+0 com High Watermark](../../docs/adr/0007-d0-daily-cash-closing-and-high-watermark.md)
* [ADR-0010: Dashboard como Módulo de Composição de Leitura](../../docs/adr/0010-dashboard-read-only-composition-module.md)
* [ADR-0011: Open Host Service Síncrono de Comandos e Catálogo (Checkout → Inventory)](../../docs/adr/0011-synchronous-open-host-service-checkout-inventory.md)

---

## 🛠️ Execução e Variáveis de Ambiente

```properties
server.port=8083
spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:petshop_db}
spring.datasource.username=${DB_USER:postgres}
spring.datasource.password=${DB_PASS:postgres}
jwt.public.key=${JWT_PUBLIC_KEY}
spring.rabbitmq.host=${RABBITMQ_HOST:localhost}
```


## Cancelamento e conciliação operacional

Conforme a [ADR-0012](../../docs/adr/0012-idempotent-payment-pipeline-and-single-phase-sale-reversal.md), o cancelamento recompõe o estoque, marca pagamentos regulares como `ESTORNADO` e publica o estorno contábil. O reembolso real no adquirente pertence ao backlog `DEBT-PAY-001`; a resposta de sucesso da API não comprova devolução de dinheiro ao cliente.

`FEATURES_CANCELAMENTO_VENDA_CONCLUIDA_ENABLED=false` é o padrão da aplicação, do Compose e do exemplo de ambiente. Para ativar em ambiente com pagamentos reais, os PRs E1–E4 do [DEBT-ARCH-004](../../docs/debt/DEBT-ARCH-004/README.md) devem estar entregues e validados com testes de integração, e o procedimento abaixo deve ter sido validado pela operação. Alterar a variável exige reiniciar/recriar o serviço para aplicar a configuração. Voltar a `false` bloqueia novos cancelamentos com HTTP 503, sem desfazer os já concluídos.

Antes do deploy da [E5](../../docs/debt/DEBT-ARCH-004/E5.md), confirmar que SPA e PDV migraram o tratamento de HTTP 409 e 503 de `$.mensagem` para `$.detail`, `$.title`, `$.status` e `$.type`. O [contrato de cancelamento](../../docs/domains/checkout.md#7-contrato-http-de-cancelamento-de-venda-concluída) descreve os campos de cada resposta.

### Procedimento manual no adquirente

1. Designar um responsável e registrar cada pendência em controle operacional com `vendaId`, `pagamentoId`, referência/NSU, método, valor efetivamente recebido, data e motivo. Levantar pagamentos `ESTORNADO` e pagamentos com `excedente = true`; um mesmo pagamento deve ter uma única pendência, mesmo que apareça nos dois grupos.
2. Consultar a transação no portal do adquirente e conferir valor capturado e reembolsos anteriores. Para excedentes, conferir o valor recebido a maior sem reabrir a venda nem estornar novamente pagamentos já conciliados. Divergências ficam pendentes para investigação.
3. Solicitar o reembolso devido pelo canal autorizado do adquirente apenas após confirmar que ele ainda não ocorreu. Em caso de timeout ou resposta ambígua, consultar o status antes de repetir, evitando reembolso duplicado.
4. Registrar protocolo, valor, operador, data e comprovante externo. Conferir a liquidação no extrato do adquirente e encerrar a pendência apenas após confirmação; rejeições permanecem abertas com responsável e próxima ação.
5. Conciliar diariamente os pagamentos estornados e excedentes com o controle operacional e o extrato. Preservar a trilha contábil append-only; não alterar registros de pagamentos ou lançamentos por SQL para simular reembolso. Suspender novos cancelamentos pela flag se houver divergências sem controle.

### Verificação local sem pagamentos reais

Com `auth-service` e `inv-service` locais e a flag em `false`, obter tokens pelo fluxo de login para contas de teste `ADMIN` e `USER`. Consultar `GET /vendas/aberta` e chamar `POST /vendas/{id}/cancelar`: esperar 200/204 e 503 para `ROLE_ADMIN`, respectivamente, e 403 para `ROLE_USER` em ambos. Não usar transações reais nesta verificação.

## Contratos HTTP de catálogo e movimentações

Os GETs de produtos retornam `ProdutoResumoResponse`: `id`, `codigoBarras`, `nome`, `unidadeMedida`, `estoqueDisponivel` e `precoVenda`. A listagem envolve esses itens em `Page`; as consultas por ID e EAN retornam um item. `estoqueMinimo`, `estoqueReservado` e `version` não são expostos. O POST mantém `ProdutoResponse`, incluindo o nome `quantidadeEstoque`.

`GET /movimentacoes` retorna `Page<MovimentacaoResponse>` com itens planos: `id`, `produtoId`, `produtoNome`, `codigoBarras`, `vendaId`, `tipo`, `quantidade`, `dataHora` e `observacao`. `dataHora` usa string ISO-8601 sem fuso (ex.: `2026-11-16T10:00:00`). Não há objeto `produto` aninhado. A projeção JPQL e sua `countQuery` explícita dispensam inicialização de entidades durante a serialização, mantendo `spring.jpa.open-in-view=false`.

| Regra | `/produtos` | `/movimentacoes` |
| :--- | :--- | :--- |
| Página padrão / negativa | `0` / normalizada para `0` | `0` / normalizada para `0` |
| Tamanho padrão / menor que 1 | `10` | `20` |
| Tamanho acima de 50 | Normalizado para `50` | Normalizado para `50` |
| Ordenação | Fixa `id ASC`, inclusive na busca por `nome` ou `busca`; ignora `sort` | Padrão `dataHora DESC`; aceita `id`, `dataHora`, `tipo`, `quantidade` |
| Desempate | `id ASC` | Acrescenta `id ASC` quando `id` não foi solicitado |
| Ordenação inválida | Não há ordenação dinâmica | Propriedade fora da lista ou `ignorecase` retorna HTTP 400 `ProblemDetail`, título `Regra de Negócio Violada` |

Exemplo: `/movimentacoes?page=0&size=2&sort=dataHora,desc` preserva ordem crescente de ID entre horários iguais e informa `totalElements` e `totalPages`.

Antes do deploy, alinhar com SPA/PDV a retirada dos campos internos de produtos e a adoção das movimentações planas. Após a migração dos clientes, rollback requer coordenação com esses consumidores ou compatibilidade temporária no servidor.
