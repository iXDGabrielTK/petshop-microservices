# Changelog

Todas as mudanças relevantes deste projeto são registradas neste arquivo.

O formato é baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/). Este projeto ainda não publica releases nem tags: as entradas são organizadas por PR mergeado em `main`, da mais recente para a mais antiga.

Os detalhes técnicos de cada mudança ficam em [`docs/`](docs/). Este arquivo registra apenas o que mudou, de forma resumida.

## [Unreleased]

### 2026-11-19 · PR #62

### Added
- Protocolo de idempotência em 6 etapas em `POST /pagamentos/intencao/{vendaId}` (`PagamentoService.criarIntencaoPagamento`) com `findByIdWithLock` em `vendas`, reconsulta pós-lock, validação de `aceitaPagamento()` e `INSERT ... ON CONFLICT (uuid_referencia) DO NOTHING RETURNING id` (`DEBT-ARCH-004` E3).
- `ConflictException` (HTTP `409`) e `ServiceUnavailableException` (HTTP `503`) na `common-lib`, mapeadas em RFC 7807 (`ProblemDetail`) pelo `GlobalExceptionHandler` (`DEBT-ARCH-004` E3, E5).
- DTOs de leitura no Inventory: `ProdutoResumoResponse` (`estoqueDisponivel`) em `GET /produtos*` e projeção JPQL plana `MovimentacaoResponse` (com `countQuery` explícito e `dataHora` ISO-8601) em `GET /movimentacoes` (`DEBT-ARCH-004` E2).
- Migration `V9__indice_movimentacoes_estoque_saida.sql` criando o índice parcial concorrente `idx_movimentacoes_venda_saida` (`ON movimentacoes_estoque (venda_id) WHERE tipo = 'SAIDA'`) (`DEBT-ARCH-004` E4).
- Parametrização da feature flag `FEATURES_CANCELAMENTO_VENDA_CONCLUIDA_ENABLED` (default `false`) em `application.properties`, `docker-compose.yml` e `.env.example` (`DEBT-ARCH-004` E1).
- Cobertura na cadeia real de filtros JWT (`VendaSecurityFilterTest`, `ProdutoSecurityFilterTest`), testes de contrato/integração sem OSIV (`ProdutoControllerTest`, `MovimentacaoControllerTest`, `InventarioHttpIT`) e testes concorrentes em PostgreSQL para intenção de pagamento e devolução integral (`DEBT-ARCH-004` E1–E4).

### Changed
- Licença do projeto alterada de MIT para `AGPL-3.0-only`, sem exceções adicionais, a partir desta revisão. O histórico até o commit `d31dee741d54fc21adfcfc3b3c9918ae5b248f83` (PR #54) permanece sob MIT; a mudança não revoga as permissões das versões anteriores.
- Imagens passam a incluir os termos do projeto e avisos de terceiros, metadados OCI de licença, origem e revisão, e imagem de runtime fixada por digest.
- **Contratos HTTP de catálogo e movimentações (`DEBT-ARCH-004` E2):** `GET /produtos*` passa a retornar `ProdutoResumoResponse` (omitindo `estoqueMinimo`, `estoqueReservado` e `version`) com ordenação fixa `id ASC`; `GET /movimentacoes` retorna `MovimentacaoResponse` plano com whitelist de ordenação `{id, dataHora, tipo, quantidade}`, desempate `id ASC` e HTTP `400` (`ProblemDetail`) para ordenação inválida ou `ignorecase`. Ambos normalizam `page < 0 -> 0`, `size < 1 -> default` (`10`/`20`) e aplicam teto `size <= 50` (requer alinhamento prévio com SPA/PDV antes do deploy).
- **Padronização RFC 7807 em `POST /vendas/{id}/cancelar` (`DEBT-ARCH-004` E5):** respostas HTTP `409` e `503` delegadas ao `GlobalExceptionHandler` (`ProblemDetail`, removendo os `@ExceptionHandler` locais de `VendaController` e o campo legado `mensagem`), com `CancelamentoVendaConflitoException` e `CancelamentoVendaDesabilitadoException` estendendo `ConflictException` e `ServiceUnavailableException` (requer alinhamento prévio com SPA/PDV antes do deploy).
- **Devolução estritamente integral (`DEBT-ARCH-004` E4):** `EstoqueService.devolverEstoque` audita igualdade de conjuntos de `produtoId` e quantidades exatas contra as movimentações `SAIDA` da venda e valida a quantidade em repetições de `DEVOLUCAO`, com rollback integral do lote em divergências.
- **Semântica HTTP e precisão decimal em `POST /pagamentos/intencao/{vendaId}` (`DEBT-ARCH-004` E3):** retorna HTTP `404` para venda inexistente, HTTP `409` (`ProblemDetail`) para status incompatível ou UUID divergente e HTTP `400` para escala decimal `> 2` (normalizando zeros à direita sem arredondamento).
- **Documentação viva consolidada (`docs/domains/`, `docs/architecture/`, `docs/adr/`):** atualização integral de [`checkout.md`](docs/domains/checkout.md), [`inventory.md`](docs/domains/inventory.md), [`finance.md`](docs/domains/finance.md), [`security.md`](docs/domains/security.md), [`system-overview.md`](docs/architecture/system-overview.md) e das ADRs `0001`, `0002`, `0005`, `0009`, `0010`, `0011` e `0012` refletindo os épicos `DEBT-ARCH-001` a `DEBT-ARCH-004` (`ArquiteturaTest` `R1`–`R15`, `ModulithTest`, migrations `V1`–`V9`, hierarquia global de locks e catálogo RFC 7807).

### Fixed
- Alinhamento da autorização em `VendaController` de `@PreAuthorize("hasAuthority('ADMIN')")` para `@PreAuthorize("hasRole('ADMIN')")`, corrigindo o bloqueio HTTP `403` indevido para administradores autenticados com `"roles": ["ROLE_ADMIN"]` (`DEBT-ARCH-004` E1).
- Eliminação de `LazyInitializationException` em `GET /movimentacoes` sob `spring.jpa.open-in-view=false` via projeção JPQL direta (`DEBT-ARCH-004` E2).
- Correção de condições de corrida, arredondamento silencioso em `NUMERIC(10, 2)` e ausência de verificação de status da venda em `PagamentoService.criarIntencaoPagamento` (`DEBT-ARCH-004` E3).
- Correção de imprecisões factuais na documentação arquitetural (nomes reais de exchanges/filas RabbitMQ `pdv.exchange`, `inventory.v1.events` e `auth.v1.events`, estados reais de `StatusPagamento`, métrica `dashboard.card.latency` e caminho de `DashboardCacheConfig`/`DashboardQueryServiceTest` na `ADR-0010`).

### 2026-10-30 · PR #46

#### Added
- Rate limit do Gateway resiste a ataques anônimos distribuídos (clientes sem IP identificável não compartilham mais o mesmo bucket).
- Suporte aos cabeçalhos `X-Forwarded-For` e `X-Real-IP` para operar atrás de Nginx ou Cloudflare.
- Rota de webhook de pagamento com prioridade no Gateway e isenta de rate limit.
- Validação estrita do payload do webhook de pagamento (HTTP 400 para valores inválidos ou `nsu` ausente).
- Suporte polimórfico a sessões e JWT nos endpoints `/usuarios/me` e `/usuarios/settings`.
- Liberação pública e isenção de CSRF da rota `/usuarios/reset-password` para redefinição de senhas via links de e-mail.
- Limpeza periódica da telemetria de atividade no Redis, em segundo plano (a cada 5 minutos).
- Métricas do `inv-service` expostas ao Prometheus (`inv-service:8083`).
- Testes unitários para filtros de cookies, rate limiting e controllers de auth e webhook.

#### Changed
- `/usuarios/forgot-password` passa a responder sempre HTTP 200 com mensagem neutra, evitando a descoberta de contas existentes (anti-enumeração).
- Isenção de proteção CSRF aplicada a `/usuarios/forgot-password` e `/usuarios/reset-password`.
- Falhas do Redis na telemetria geram no máximo um aviso a cada 10 segundos, sem afetar o desempenho do Gateway.
- Removidos stubs de servlet artificiais do `common-lib`.

#### Fixed
- `/usuarios/me` e `/usuarios/settings` retornam HTTP 401 (não autenticado) e HTTP 403 (acesso negado) em conformidade com RFC 7807 em vez de erro genérico.
- Nome da variável `JWT_PUBLIC_KEY` no `docker-compose.yml` (diferença de maiúsculas/minúsculas).

### Baseline
- Estado do projeto antes do PR #46: monólito modular com DDD, ledger contábil append-only e outbox com `SKIP LOCKED`. Entradas anteriores não foram retroativamente registradas.
