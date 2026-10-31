# Changelog

Todas as mudanças relevantes deste projeto são registradas neste arquivo.

O formato é baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/). Este projeto ainda não publica releases nem tags: as entradas são organizadas por PR mergeado em `main`, da mais recente para a mais antiga.

Os detalhes técnicos de cada mudança ficam em [`docs/`](docs/). Este arquivo registra apenas o que mudou, de forma resumida.

## [Unreleased]

### 2026-10-30 · PR #46

#### Added
- Rate limit do Gateway resiste a ataques anônimos distribuídos (clientes sem IP identificável não compartilham mais o mesmo bucket).
- Suporte aos cabeçalhos `X-Forwarded-For` e `X-Real-IP` para operar atrás de Nginx ou Cloudflare.
- Rota de webhook de pagamento com prioridade no Gateway e isenta de rate limit.
- Validação estrita do payload do webhook de pagamento (HTTP 400 para valores inválidos ou `nsu` ausente).
- `/usuarios/me` aceita sessão e JWT de forma transparente.
- Limpeza periódica da telemetria de atividade no Redis, em segundo plano (a cada 5 minutos).
- Métricas do `inv-service` expostas ao Prometheus (`inv-service:8083`).
- Testes unitários para filtros de cookies, rate limiting e controllers de auth e webhook.

#### Changed
- `/usuarios/forgot-password` passa a responder sempre HTTP 200 com mensagem neutra, evitando a descoberta de contas existentes.
- Falhas do Redis na telemetria geram no máximo um aviso a cada 10 segundos, sem afetar o desempenho do Gateway.
- Removidos stubs de servlet artificiais do `common-lib`.

#### Fixed
- `/usuarios/me` retorna HTTP 401 (não autenticado) e HTTP 403 (acesso negado) em vez de erro genérico.
- Nome da variável `JWT_PUBLIC_KEY` no `docker-compose.yml` (diferença de maiúsculas/minúsculas).

### Baseline
- Estado do projeto antes do PR #46: monólito modular com DDD, ledger contábil append-only e outbox com `SKIP LOCKED`. Entradas anteriores não foram retroativamente registradas.
