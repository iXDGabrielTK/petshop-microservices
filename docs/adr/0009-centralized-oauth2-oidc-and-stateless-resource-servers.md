# ADR-0009: Arquitetura de Autenticação com Spring Authorization Server (OIDC) e Resource Servers Stateless com Assinatura RSA

* **Status:** Aceito
* **Data:** 2026-08-28
* **Autores:** Equipe de Engenharia
* **Bounded Contexts Afetados:** Auth, Security, Gateway, Shared
* **Serviços:** `apps/auth-service`, `apps/api-gateway`, `apps/inv-service`

---

## 1. Contexto e Problema (Context & Problem Statement)

Em um ecossistema de microsserviços com múltiplos tipos de clientes (Front-end SPA, PDV Desktop e integrações externas), a segurança e o gerenciamento de identidade exigem:
1. Ponto centralizado de login e consentimento sem que credenciais de usuário trafeguem pelos microsserviços de negócio.
2. Validação de identidade de alta performance nos Resource Servers (`inv-service`), evitando chamadas HTTP síncronas de validação de token a cada requisição (*Token Introspection* / gargalo de rede).
3. Capacidade de emitir tokens assinados criptograficamente com suporte a rotação periódica de chaves sem interrupção de serviço.

---

## 2. Drivers de Decisão (Decision Drivers / Forces)

* **Padrões de Mercado Abertos:** Adoção estrita dos padrões **OAuth 2.1** e **OpenID Connect 1.0 (OIDC)**.
* **Validação Criptográfica Stateless (Zero Network Overhead):** O `inv-service` deve validar a autenticidade e permissões do JWT localmente em memória usando a Chave Pública RSA, sem consultar o banco de dados de autenticação a cada chamada.
* **Segurança Criptográfica Assimétrica (RSA 2048-bit):** Apenas o `auth-service` possui acesso à Chave Privada para assinar tokens; todos os outros componentes conhecem apenas a Chave Pública.
* **Suporte a Rotação de Chaves (Key Rotation):** Exposição padronizada de chaves via JWKS (`/oauth2/jwks`) com identificador de chave (`kid`).

---

## 3. Opções Consideradas (Considered Options)

1. **Opção 1: Criptografia Simétrica Compartilhada (HMAC-SHA256):** Todos os serviços compartilham a mesma chave secreta em variáveis de ambiente.
   * *Problema:* Se a chave vazar em qualquer serviço downstream, um invasor pode forjar tokens arbitrários para qualquer parte do sistema.
2. **Opção 2: Tokens de Referência Opacos com Introspecção Síncrona:** O gateway valida cada token fazendo HTTP POST no `auth-service`.
   * *Problema:* Cria um ponto único de falha e adiciona 20-50ms de latência em cada requisição de API.
3. **Opção 3: Spring Authorization Server com Assinatura RSA Assimétrica + Resource Servers Stateless (Adotada):**
   * O `auth-service` emite JWTs assinados com chave privada RSA 2048-bit.
   * Expõe o endpoint público `/oauth2/jwks`.
   * Os microsserviços atuam como *Resource Servers* stateless via `NimbusJwtDecoder`, decodificando e validando claims (`roles`, `user_id`, `sub`) diretamente com a chave pública.

---

## 4. Decisão Tomada e Justificativa (Decision Outcome)

Decidimos adotar a **Opção 3: Spring Authorization Server com Assinatura RSA Assimétrica**.

### A. Emissão de Tokens no Auth Service ([`SecurityConfig.java`](../../apps/auth-service/src/main/java/auth/config/SecurityConfig.java#L258-L289))
* Chaves RSA são injetadas via variáveis de ambiente (`JWT_PUBLIC_KEY`, `JWT_PRIVATE_KEY`) ou geradas com fallback seguro em ambiente local de desenvolvimento.
* Customização de claims inclui `roles`, `user_id`, `name` e `email` diretamente no payload do Access Token.

### B. Validação nos Resource Servers ([`SecurityConfig.java` do inv-service](../../apps/inv-service/src/main/java/inv/shared/config/SecurityConfig.java#L48-L72))
```java
@Bean
public JwtDecoder jwtDecoder() {
    RSAPublicKey publicKey = RsaKeyUtils.parsePublicKey(publicKeyString);
    return NimbusJwtDecoder.withPublicKey(publicKey).build();
}
```
* **Performance:** Validação matemática direta em CPU (\(O(1)\)), sem I/O de rede e sem consultas ao banco de dados.

### C. Estratégia de Rotação de Chaves (Key Rotation)
1. O endpoint público `GET /oauth2/jwks` expõe o conjunto de chaves ativas contendo o cabeçalho `kid` (Key ID).
2. Durante uma rotação de chaves:
   * Uma nova chave privada/pública é gerada e associada a um novo `kid`.
   * O JWKS expõe temporariamente ambas as chaves públicas durante a **janela de sobreposição** (tempo de expiração dos tokens antigos, ex: 1 hora).
   * Tokens antigos continuam válidos até expirarem naturalmente, e novos tokens já são assinados com a nova chave.

---

## 5. Prós e Contras das Opções Analisadas

| Característica | HMAC Simétrico | Introspecção Síncrona | RSA Assimétrico Stateless (Adotada) |
| :--- | :--- | :--- | :--- |
| **Segurança da Chave** | Baixa (Compartilhada) | Alta (Centralizada) | **Altíssima (Privada isolada)** |
| **Latência por Request** | Baixa | Alta (Chamada HTTP) | **Baixíssima (In-memory CPU)** |
| **Ponto Único de Falha** | Não | Sim (Auth Server) | **Não (Resource Server autônomo)** |
| **Conformidade OIDC** | Parcial | Parcial | **Total (RFC 7517 / RFC 7519)** |

---

## 6. Estratégia de Validação & Testes

* **Teste de Validação de Token Stateless:**
  1. Gerar um JWT assinado pelo `auth-service` com a role `ADMIN`.
  2. Submeter requisição para `POST /vendas` no `inv-service` com o header `Authorization: Bearer <token>`.
  3. Validar se a autenticação é aceita (HTTP 200) e as authorities são extraídas corretamente sem nenhuma comunicação de rede com o `auth-service`.
* **Teste de Rejeição de Assinatura Forjada:**
  * Submeter uma requisição com token adulterado ou assinado por par de chaves inválido e validar resposta HTTP 401 Unauthorized.

---

## 7. Referências e Links Relevantes

* [SecurityConfig do Auth Service](../../apps/auth-service/src/main/java/auth/config/SecurityConfig.java)
* [SecurityConfig do Inv Service](../../apps/inv-service/src/main/java/inv/shared/config/SecurityConfig.java)
* [Documentação do Domínio Security](../domains/security.md)
* [Spring Authorization Server Reference Guide](https://docs.spring.io/spring-authorization-server/reference/)
