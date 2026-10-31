# 🔐 Auth Service (OAuth2 & OpenID Connect 1.0)

O **Auth Service** é o provedor de identidade centralizado do sistema, implementado com **Spring Authorization Server**.

* **Porta Padrão:** `8081`
* **Stack:** Java 21, Spring Boot 3.4.1, Spring Authorization Server, PostgreSQL 15, RSA 2048-bit, RabbitMQ.

---

## 🚀 Endpoints Principais

### Fluxo OAuth2 / OIDC
* `GET /oauth2/authorize` - Fluxo de Autorização OIDC com tela de consentimento e login.
* `POST /oauth2/token` - Emissão e renovação de tokens (Access Token JWT e Refresh Token).
* `GET /oauth2/jwks` - Conjunto público de chaves criptográficas (JWKS) para validação distribuída.

### Gestão de Contas & Handshake Frontend
* `POST /usuarios/register` - Cadastro de novos operadores com sanitização HTML contra XSS.
* `GET /usuarios/me` - Handshake de sessão do frontend após F5 (suporta JWT e Sessão de forma polimórfica; erros semânticos HTTP 401 e HTTP 403 com RFC 7807).
* `PATCH /usuarios/settings` - Atualização de preferências visuais do usuário (`theme`: `LIGHT`, `DARK`, `SYSTEM`; suporte polimórfico a JWT e Sessão; validação `@Valid` com HTTP 400).
* `POST /usuarios/forgot-password` - Solicitação de recuperação de senha com proteção anti-enumeração de contas (resposta neutra HTTP 200; isento de CSRF).
* `POST /usuarios/reset-password` - Redefinição pública de senha via token efêmero (`permitAll()`; isento de CSRF).

---

## 📖 Documentação Detalhada

* 📑 **[Visão Geral da Arquitetura](../../docs/architecture/system-overview.md)**
* 🔐 **[Deep Dive: Segurança, Gestão de Identidade & Contratos RFC 7807](../../docs/domains/security.md)**
* 📑 **[ADR-0009: Arquitetura de Autenticação com Spring Authorization Server e RSA](../../docs/adr/0009-centralized-oauth2-oidc-and-stateless-resource-servers.md)**

---

## 🛠️ Variáveis de Ambiente Críticas

```properties
server.port=8081
JWT_PRIVATE_KEY="-----BEGIN RSA PRIVATE KEY..."
JWT_PUBLIC_KEY="-----BEGIN PUBLIC KEY..."
FRONTEND_BASE_URL="http://localhost:3000"
INITIAL_ADMIN_EMAIL="admin@petshop.com"
INITIAL_ADMIN_PASSWORD="admin_super_secret"
```
