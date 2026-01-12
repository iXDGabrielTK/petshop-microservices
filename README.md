# 🐾 Sistema de Microsserviços - Pet Shop & Auth

---

![Java](https://img.shields.io/badge/Java-21-blue?style=flat&logo=openjdk&logoColor=white)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-Messaging-orange?style=flat&logo=rabbitmq&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.1-brightgreen?style=flat&logo=springboot&logoColor=white)
![Spring Cloud Gateway](https://img.shields.io/badge/Spring%20Gateway-Stable-green?style=flat&logo=spring&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-Enabled-2496ED?style=flat&logo=docker&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-336791?style=flat&logo=postgresql&logoColor=white)
![Security](https://img.shields.io/badge/Spring%20Security-BCrypt%20%7C%20JWT-red?style=flat&logo=springsecurity&logoColor=white)
![License](https://img.shields.io/badge/license-MIT-lightgrey)
![Prometheus](https://img.shields.io/badge/Prometheus-Monitoring-E6522C?style=flat&logo=prometheus&logoColor=white)
![Grafana](https://img.shields.io/badge/Grafana-Dashboards-F46800?style=flat&logo=grafana&logoColor=white)

Este projeto é um sistema distribuído baseado em **microsserviços** para gerenciamento de um **Pet Shop**. O objetivo é demonstrar uma arquitetura robusta, segura e escalável utilizando Java e Docker.

## 🏛️ Arquitetura do Sistema

O sistema segue o padrão de **Arquitetura de Microsserviços**, onde a autenticação é desacoplada das regras de negócio.
```mermaid
graph LR
    User(["User / Front-end"])
    
    subgraph Docker["Docker Compose Environment"]
        direction TB
        
        %% Gateway
        Gateway["⛩️ API Gateway<br/>(Porta 8080)<br/>[Spring 3.4]"]
        
        %% Serviços
        Auth["🐶 Auth Service<br/>(Porta 8081)<br/>[Spring 4.0]"]
        Mail["📨 Mail Service<br/>(Porta 8082)<br/>[Consumer]"]
        Pet["🐾 Pet Service<br/>(Em Breve)"]
        
        %% Bancos & Infra
        AuthDB[("Auth DB")]
        Redis[("Redis<br/>(Rate Limit)")]
        Rabbit[("RabbitMQ")]
        
        %% Fluxos
        User -->|"HTTPS / JSON"| Gateway
        Gateway -->|"Roteamento &<br/>Rate Limit"| Auth
        Gateway -.-> Pet
        
        %% Comunicação Interna
        Auth -->|"Publish"| Rabbit
        Rabbit -->|"Consume"| Mail
        
        %% Persistência
        Auth <--> AuthDB
        Gateway <--> Redis
    end

    %% Estilização
    classDef gateway fill:#e16b16,stroke:#fff,stroke-width:2px,color:white;
    classDef service fill:#2da44e,stroke:#fff,stroke-width:2px,color:white;
    classDef infra fill:#0366d6,stroke:#fff,stroke-width:2px,color:white;
    
    class Gateway gateway;
    class Auth,Mail,Pet service;
    class AuthDB,Redis,Rabbit infra;
    
```
## 🚀 Tecnologias & Patterns
* **Core:** Java 21, Spring Boot 4.0.1 (Services) e 3.4.1 (Gateway).

* **API Gateway:** Spring Cloud Gateway, Rate Limiting (Redis) e Roteamento Dinâmico..

* **Mensageria:** RabbitMQ (AMQP), Topic Exchange.

* **Segurança:** Spring Security, JWT (Access + Refresh Token), BCrypt (Cost 12), Google Guava (Blacklist).

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
Responsável pela identidade e segurança de todo o ecossistema.
* **Porta:** `8081`
* **Features:**
    * Autenticação via **JWT (Access + Refresh Token)**.
    * Recuperação de Senha via **E-mail (Token temporário)**.
    * Sistema de **Blacklist** para Logout seguro.
    * Senhas criptografadas com **BCrypt**.
    * Proteção contra **XSS (Cross-Site Scripting)** usando sanitização de HTML.
    * Validação de **Fingerprint** no token.

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

1.  **Clone o repositório:**
    ```bash
    git clone [https://github.com/iXDGabrielTK/petshop-microservices.git](https://github.com/iXDGabrielTK/petshop-microservices.git)
    cd petshop-microservices
    ```

2.  **Gere os executáveis (.jar):**
    * No IntelliJ: Aba Maven > `auth-service` > `Lifecycle` > `clean` e `package`.
    * Ou via terminal na pasta do serviço:
        ```bash
        cd apps/auth-service
        ./mvnw clean package
        ```

3.  **Suba os containers:**
    Na raiz do projeto (onde está o `docker-compose.yml`):
    ```bash
    docker-compose up --build
    ```

4.  **Acesse a Documentação Unificada:**
    http://localhost:8080/swagger-ui/index.html

---

## 🧪 Payloads para Teste (JSON)

### 1. Registrar Usuário (POST /usuarios/register)
**Segurança:** A senha deve ter min 8 caracteres, maiúscula, minúscula, número e especial.
```json
{
  "nome": "Seu Nome",
  "email": "teste@email.com",
  "senha": "SenhaForte123!"
}
```

### 2. Login (POST /usuarios/login)
```json
{
  "email": "teste@email.com",
  "senha": "SenhaForte123!"
}
```
### 3. Refresh Token (POST /usuarios/refresh-token)
```json
{
  "refreshToken": "COLE_O_TOKEN_DE_REFRESH_AQUI"
}
```

### 4. Logout (POST /usuarios/logout)
```json
{
  "refreshToken": "COLE_O_TOKEN_DE_REFRESH_AQUI"
}
```

### 5. Recuperar Senha - Solicitação (POST /usuarios/forgot-password)
```json
{
  "email": "teste@email.com"
}
```

### 6. Recuperar Senha - Reset (POST /usuarios/reset-password)
```json
{
  "token": "COLE_O_TOKEN_RECEBIDO_NO_EMAIL",
  "newPassword": "NovaSenhaForte123!"
}
```

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
```
[x] Auth Service: Login, Registro, JWT, Refresh Token, Logout.

[x] Segurança: Criptografia de senhas, proteção XSS e Recuperação de Senha.

[x] Docker: Containerização do Banco e API.

[x] Mensageria: Integração com RabbitMQ (Producer/Consumer).

[x] Resiliência: Implementação de DLQ (Dead Letter Queue) e Retries.

[x] Observabilidade Completa:
    [x] Métricas (Prometheus/Grafana)
    [x] Logs Centralizados (Loki/Promtail)
[x] Mail Service: Microserviço dedicado para notificações.

[ ] Pet Service: CRUD de Pets e vínculo com usuário logado.

[ ] Agendamento: Lógica de horários para Banho e Tosa.

[ ] Front-end: Interface em React.
```

## 📄 Licença

Este projeto está sob a licença MIT - veja o arquivo [LICENSE](LICENSE) para detalhes.

---

## 📬 Contato
Gostou do projeto? Entre em contato!

* **LinkedIn:** https://www.linkedin.com/in/gabriel-tanaka-b1669b175/

* **Email:** gabrielferraritanaka@gmail.com