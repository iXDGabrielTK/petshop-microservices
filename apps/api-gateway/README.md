# ⛩️ API Gateway (Borda & Rate Limiting)

O **API Gateway** é o ponto de entrada único para o ecossistema Pet Shop, gerenciando roteamento dinâmico, proteção de borda e sanitização de tráfego.

* **Porta Padrão:** `8080`
* **Stack:** Java 21, Spring Boot 3.4.1, Spring Cloud Gateway, Redis (Token Bucket Rate Limiter).

---

## 🚀 Features & Mecanismos de Borda

* **Token Bucket Rate Limiter:** Proteção contra ataques de força bruta e DoS por IP de origem via Redis.
* **Roteamento Inteligente:** Encaminhamento de rotas `/oauth2/**` e `/usuarios/**` para o `auth-service` e `/vendas/**`, `/produtos/**` para o `inv-service`.
* **Headers Globais:** Injeção de `X-Request-Id` e propagação de contexto de rastreabilidade.

---

## 📖 Documentação Detalhada

* 📑 **[Visão Geral da Arquitetura](../../docs/architecture/system-overview.md)**
* 🔐 **[Deep Dive: Segurança & Gateway](../../docs/domains/security.md)**
* 📑 **[ADR-0009: Arquitetura de Autenticação com Spring Authorization Server e RSA](../../docs/adr/0009-centralized-oauth2-oidc-and-stateless-resource-servers.md)**
