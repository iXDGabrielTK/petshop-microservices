# ⛩️ API Gateway (Borda & Rate Limiting)

O **API Gateway** é o ponto de entrada único para o ecossistema Pet Shop, gerenciando roteamento dinâmico, proteção de borda e sanitização de tráfego.

* **Porta Padrão:** `8080`
* **Stack:** Java 21, Spring Boot 3.4.1, Spring Cloud Gateway, Redis (Token Bucket Rate Limiter).

---

## 🚀 Features & Mecanismos de Borda

* **Token Bucket Rate Limiter:** Proteção contra ataques de força bruta e DoS por IP via Redis.
  * Suporte a proxies reversos através dos cabeçalhos `X-Forwarded-For` e `X-Real-IP`.
  * Particionamento preventivo contra DoS (`anon_<hash>`) para clientes anônimos sem IP identificável.
* **Precedência e Isolamento de Rotas:**
  * `order: 1` — Rota de Webhook de Pagamento (`/pagamentos/webhook/**`) com prioridade e **isenta de rate limit**, evitando descarte de notificações de parceiros de pagamento (precede a rota genérica `order: 2` para resolver a sobreposição com `/pagamentos/**`).
  * `order: 2` — Rota de Negócio do `inv-service` (`/produtos/**`, `/vendas/**`, `/movimentacoes/**`, `/dashboard/**`, `/pagamentos/**`) protegida por Rate Limiting.
  * `order: 0` — Rota de Identidade do `auth-service` (`/oauth2/**`, `/usuarios/**`) protegida por Rate Limiting.
* **Telemetria de Atividade Resiliente no Redis:**
  * Expurgo assíncrono em segundo plano (`ActivityPurgeScheduler`) a cada 5 minutos, removendo registros com mais de 1 hora.
  * Circuito não-bloqueante no `ActivityFilter` (`onErrorResume`) com supressão de tempestade de logs (máximo de 1 aviso a cada 10s sob falha do Redis).
* **Headers Globais:** Injeção de `X-Request-Id` e propagação de contexto de rastreabilidade.

---

## 📖 Documentação Detalhada

* 📑 **[Visão Geral da Arquitetura](../../docs/architecture/system-overview.md)**
* 🔐 **[Deep Dive: Segurança, Gateway & Requisitos de Proxy Reverso](../../docs/domains/security.md)**
* 📑 **[ADR-0009: Arquitetura de Autenticação com Spring Authorization Server e RSA](../../docs/adr/0009-centralized-oauth2-oidc-and-stateless-resource-servers.md)**
