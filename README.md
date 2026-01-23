# 🐾 Sistema de Microsserviços - Pet Shop & Auth

---

![Java](https://img.shields.io/badge/Java-21-blue?style=flat&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.1-brightgreen?style=flat&logo=springboot&logoColor=white)
![OAuth2](https://img.shields.io/badge/Spring_Auth_Server-OAuth2_%7C_OIDC-green?style=flat&logo=springsecurity&logoColor=white)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-Messaging-orange?style=flat&logo=rabbitmq&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-Enabled-2496ED?style=flat&logo=docker&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-336791?style=flat&logo=postgresql&logoColor=white)
![Prometheus](https://img.shields.io/badge/Prometheus-Monitoring-E6522C?style=flat&logo=prometheus&logoColor=white)
![Grafana](https://img.shields.io/badge/Grafana-Dashboards-F46800?style=flat&logo=grafana&logoColor=white)

Este projeto é um sistema distribuído baseado em **microsserviços** para gerenciamento de um **Pet Shop**. O objetivo é demonstrar uma arquitetura robusta, segura e escalável utilizando Java e Docker.

## 🏛️ Arquitetura do Sistema

O sistema segue o padrão de **Arquitetura de Microsserviços**, onde a autenticação é desacoplada das regras de negócio.
```mermaid
graph LR
    User["User / Front-end"]

    subgraph Docker["Ambiente Docker"]
        direction TB

        Auth["Auth Service<br>(Authorization Server / IdP)<br>[Spring Authorization Server]"]
        Gateway["API Gateway<br>(OAuth2 Resource Server)<br>[JWT + RSA]"]
        Pet["Pet Service<br>(OAuth2 Resource Server)"]
        Mail["Mail Service<br>(Consumer)"]

        AuthDB["PostgreSQL<br>(Users, Clients, Tokens)"]
        Redis["Redis<br>(Rate Limit)"]
        Rabbit["RabbitMQ"]

        User -- "1. Authorization Code Flow" --> Auth
        Auth -- "2. JWT Assinado" --> User

        User -- "3. Request + Bearer Token" --> Gateway
        Gateway -- "5. Proxy / Roteamento" --> Pet

        Auth -- "Evento: Reset de Senha" --> Rabbit
        Rabbit --> Mail

        Auth <--> AuthDB
        Gateway <--> Redis
    end

    classDef gateway fill:#e16b16,stroke:#fff,stroke-width:2px,color:white
    classDef auth fill:#800080,stroke:#fff,stroke-width:2px,color:white
    classDef service fill:#2da44e,stroke:#fff,stroke-width:2px,color:white
    classDef infra fill:#0366d6,stroke:#fff,stroke-width:2px,color:white

    class Gateway gateway
    class Auth auth
    class Pet,Mail service
    class AuthDB,Redis,Rabbit infra

```
## 🚀 Tecnologias & Patterns
* **Core:** Java 21, Spring Boot 3.4.1.

* **API Gateway:** Spring Cloud Gateway, Rate Limiting (Redis) e Roteamento Dinâmico..

* **Mensageria:** RabbitMQ (AMQP), Topic Exchange.

* **Segurança (Modernizada):** 
  * **Spring Authorization Server:** Implementação de OAuth2.1 e OpenID Connect 1.0.
  * **Assinatura RSA:** Chaves assimétricas (Pública/Privada) persistidas para assinatura de tokens.
  * **Persistência JDBC:** Tokens, Clients e Consentimentos salvos no PostgreSQL (não perde login ao reiniciar).
  * **Resource Server:** Validação de JWT nos microsserviços.
* **Observabilidade:** 
  * **Métricas:** Prometheus e Grafana.
  * **Logs:** Grafana Loki e Promtail (Logs estruturados em JSON).
* **Infraestrutura:** Docker, Docker Compose.

* **Banco de Dados:** PostgreSQL 15.

* **Documentação:** Swagger/OpenAPI (/swagger-ui.html).

* **Qualidade:** Tratamento de XSS (Sanitização de HTML), Validação de Fingerprint (IP/User-Agent).

---

## 🏛️ Arquitetura dos Serviços

### 1. ⛩️ API Gateway (Borda)
   O ponto de entrada único do sistema.

* **Porta:** `8080` 

* **Features:**
  * **Rate Limiting:** Proteção contra DDOS usando Redis (Bucket Token Algorithm).

  * **Roteamento:** Direciona /usuarios para o Auth Service e /swagger-ui para documentação.

  * **Segurança:** Filtros globais de header e proteção de rotas.

### 2. 🔐 Auth Service (Rodando)
O coração da segurança. Não é apenas uma API de usuários, mas um servidor OAuth2 completo.
* **Porta:** `8081`
* **Endpoints OAuth2:**
    * `/oauth2/authorize` - Autorização.
    * `/oauth2/token` - Emissão de Tokens (Access + Refresh).
    * `/oauth2/jwks` - Chaves Públicas (RSA) para validação de JWT.
* **Endpoints de Gestão:** Registro de usuário, recuperação de senha.

* **Segurança:** Chaves RSA 2048-bit carregadas via variáveis de ambiente.

### 3. 📨 Mail Service (Consumer)
   Responsável pelo envio de notificações transacionais.

* **Porta:** `8082`

* **Features:** Ouve a fila auth.v1.password-reset.send-email e dispara e-mails via SMTP (Mailtrap).

* **Resiliência:** Configurado com Retries Automáticos e Dead Letter Queue (DLQ). 

### 4. 🐰 RabbitMQ (Broker)
   O coração da comunicação assíncrona.

* **Porta AMQP:** `5672`

* **Dashboard:** `15672` 

### 5. 🔭 Observabilidade (Infra)
Stack completa de monitoramento rodando em containers.

* **Grafana:** http://localhost:3000 (Dashboards e Logs)

* **Prometheus:** http://localhost:9090 (Métricas)

* **Loki:** Agregador de Logs centralizado.

### 6. 🐾 Pet Service (Próximo Passo)
Responsável pelo core business (regras de negócio).
* **Porta:** `8082` (Previsto)
* **Funcionalidades:** Cadastro de pets, agendamento de serviços (banho/tosa).

---

## 🛠️ Como Rodar o Projeto

### Pré-requisitos
* Docker e Docker Compose instalados.
* Java 21 (Opcional, apenas se quiser rodar fora do Docker).

### Passo a Passo

1. **Clone o repositório:**
    ```bash
    git clone [https://github.com/iXDGabrielTK/petshop-microservices.git](https://github.com/iXDGabrielTK/petshop-microservices.git)
    cd petshop-microservices
    ```

2. **Gere os executáveis (.jar):**
    * No IntelliJ: Aba Maven > `auth-service` > `Lifecycle` > `clean` e `package`.
    * Ou via terminal na pasta do serviço:
        ```bash
        cd apps/auth-service
        ./mvnw clean package
        ```
3. **Configuração de Segurança**
    * **Crie um arquivo chamado `.env`  na raiz.**
    * Gere as chaves **RSA** e converta o conteúdo **PEM** para **Base64** (linha única).
    * **Preencha o arquivo:**
    ```env
    # .env (Exemplo)
    DB_HOST=postgres
    DB_PORT=5432
    DB_USER=postgres
    DB_PASS=admin
    DB_NAME=auth_db
   
    # RabbitMQ
    RABBITMQ_DEFAULT_USER=guest
    RABBITMQ_DEFAULT_PASS=guest
   
    # Chaves RSA em Base64 (Sem quebras de linha!)
    JWT_PRIVATE_KEY=MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAJD...
    JWT_PUBLIC_KEY=MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAkO...

    # Mailtrap
    MAILTRAP_HOST=smtp.mailtrap.io
    MAILTRAP_PORT=2525
    MAILTRAP_USER=seu_user
    MAILTRAP_PASS=sua_senha
   
    # Redis
    SPRING_DATA_REDIS_HOST=petshop-redis
    SPRING_DATA_REDIS_PORT=6379
    
    # Outras Configurações
    SERVER_FORWARD_HEADERS_STRATEGY=native
    GRAFANA_ADMIN_PASSWORD=admin
    ```
4. **Suba os containers:**
    Na raiz do projeto (onde está o `docker-compose.yml`):
    ```bash
    docker-compose up --build
    ```

5. **Acesse a Documentação Unificada:**
    http://localhost:8080/swagger-ui/index.html

---

## 🧪 Payloads e Fluxos de Autenticação (OAuth2)

> ⚠️ O projeto utiliza **OAuth2 com JWT**.  
> Não existe mais login via endpoint REST (`/usuarios/login`).
> A autenticação é feita exclusivamente pelo Authorization Server.

---

## 🔐 1. Obter Token – Client Credentials Flow

Fluxo utilizado para:
- Testes no Postman
- Comunicação máquina-a-máquina
- Endpoints sem usuário final

### Endpoint
POST http://localhost:8081/oauth2/token

### Autenticação
**Basic Auth**
```bash
Username: petshop-client
Password: secret123
```
### Body (x-www-form-urlencoded)
```bash
grant_type=client_credentials
scope=pets:read
```
### Resposta (exemplo)
```json
{
  "access_token": "SEU_ACCESS_TOKEN_AQUI",
  "token_type": "Bearer",
  "expires_in": 3600,
  "scope": "pets:read"
}
```
📌 Utilize o token nos endpoints protegidos:

`Authorization: Bearer SEU_ACCESS_TOKEN_AQUI`

## 🔑 2. Login de Usuário – Authorization Code Flow (Front-end)

Este fluxo é utilizado pelo Front-end e ocorre via redirecionamento no navegador.

### 2.1 Acessar no navegador
```
http://localhost:8081/oauth2/authorize?response_type=code&client_id=petshop-client&scope=openid profile&redirect_uri=http://127.0.0.1:8080/authorized
```

### 2.2 Login
Faça login com um usuário cadastrado (ex: `user1` / `password1`)

```bash
Email: admin@petshop.com
Senha: admin123
```

### 2.3 Callback com Authorization Code
Após o login, o usuário será redirecionado para:

```
http://127.0.0.1:8080/authorized?code=AUTHORIZATION_CODE
```

### 2.4 Trocar Authorization Code por Tokens
Faça uma requisição POST para:
```
POST http://localhost:8081/oauth2/token
```
### Autenticação
**Basic Auth**

```bash
Username: petshop-client
Password: secret123
```

### Body (x-www-form-urlencoded)
```bash
grant_type=authorization_code
code=AUTHORIZATION_CODE
redirect_uri=http://127.0.0.1:8080/authorized
```

### Resposta (exemplo)
```json
{
  "access_token": "SEU_ACCESS_TOKEN_AQUI",
  "refresh_token": "SEU_REFRESH_TOKEN_AQUI",
  "token_type": "Bearer",
  "expires_in": 3600,
  "scope": "openid profile"
}
```

## 👤 3. Endpoints de Usuário (REST)
### 3.1 Registrar Novo Usuário

```
POST /usuarios/register
```

```json
{
  "nome": "Seu Nome",
  "email": "teste@email.com",
  "senha": "SenhaForte123!"
}
```

#### 🔐 Regras da senha

* Mínimo 8 caracteres

* Letra maiúscula

* Letra minúscula

* Número

* Caractere especial

3.2 Recuperar Senha – Solicitação (Público)

```
POST /usuarios/forgot-password
```

```json
{
  "email": "teste@email.com"
}
```

📌 Um email será enviado com instruções para redefinição da senha.
---

### 🚫 Endpoints Removidos
Os endpoints abaixo não existem mais e não devem ser utilizados:

* ❌ POST /usuarios/login

* ❌ POST /usuarios/refresh-token

* ❌ POST /usuarios/logout

---

## 📂 Estrutura do Projeto
```
petshop-microservices/
├── apps/
│   ├── auth-service/       # Microsserviço de Autenticação
│   │   ├── src/main/java/auth/
│   │   │   ├── config/     # SecurityConfig
│   │   │   ├── controller/ # Endpoints
│   │   │   ├── security/   # Lógica JWT e Filtros
│   │   │   └── service/    # Regras de Negócio
│   │   └── Dockerfile
│   │
│   ├── mail-service/       # [Consumer] Envio de E-mails
│   │   ├── src/main/java/mail/
│   │   │   ├── config/  
│   │   │   ├── message/  
│   │   │   └── service/
│   │   └── Dockerfile
│   │ 
│   ├── api-gateway/        # API Gateway com Spring Cloud Gateway
│   │   ├── src/main/java/gateway/
│   │   │   └──config/     # Configurações do Gateway
│   │   └── Dockerfile
│   │
│   ├── common-lib/   # Biblioteca comum (DTOs, Utils, Exceptions)
│   │   ├── src/main/java/common/
│   │   │   └── exception/  # Exceções personalizadas
│   │   └── Dockerfile
│   │
│   └── pet-service/        # (Em construção...)
│
├── infra/                  # Configurações de Observabilidade
│   ├── prometheus/
│   ├── grafana/
│   └── promtail/
│
└── docker-compose.yml      # Orquestração dos containers
```

## 📊 Observabilidade e Monitoramento

O projeto possui uma stack completa de monitoramento configurada via Docker.

| Ferramenta     | URL                                              | Credenciais (Padrão) | Descrição                              |
|:---------------|:-------------------------------------------------|:---------------------|:---------------------------------------|
| **Grafana**    | [http://localhost:3000](http://localhost:3000)   | `admin` / `admin`    | Visualização de métricas e Dashboards. |
| **Prometheus** | [http://localhost:9090](http://localhost:9090)   | N/A                  | Coletor de métricas (Time Series DB).  |
| **RabbitMQ**   | [http://localhost:15672](http://localhost:15672) | `guest` / `guest`    | Gestão de filas e exchanges.           |

### Dashboards Recomendados (Grafana)
Para visualizar os dados, importe os seguintes IDs no Grafana:
* **Spring Boot Statistics:** ID `11378` ou `19004` (Métricas de JVM, CPU, Requisições HTTP e Erros).
* **RabbitMQ Overview:** ID `4279` (Métricas de Filas, Conexões e Consumidores).

---

## 🗺️ Roadmap (Próximos Passos)

* [x] Auth Service: Login, Registro, JWT, Refresh Token, Logout.

* [x] Segurança: Criptografia de senhas, proteção XSS e Recuperação de Senha.

* [x] Docker: Containerização do Banco e API.

* [x] Mensageria: Integração com RabbitMQ (Producer/Consumer).

* [x] Resiliência: Implementação de DLQ (Dead Letter Queue) e Retries.

* [x] Observabilidade Completa:
    *[x] Métricas (Prometheus/Grafana)
    *[x] Logs Centralizados (Loki/Promtail)
* [x] Mail Service: Microserviço dedicado para notificações.

* [ ] Pet Service: CRUD de Pets e vínculo com usuário logado.

* [ ] Agendamento: Lógica de horários para Banho e Tosa.

* [ ] Front-end: Interface em React.
---
## 📄 Licença

Este projeto está sob a licença MIT - veja o arquivo [LICENSE](LICENSE) para detalhes.

---

## 📬 Contato
Gostou do projeto? Entre em contato!

* **LinkedIn:** https://www.linkedin.com/in/gabriel-tanaka-b1669b175/

* **Email:** gabrielferraritanaka@gmail.com