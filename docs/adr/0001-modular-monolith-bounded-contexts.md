# ADR-0001: Adoção de Monólito Modular com Bounded Contexts no `inv-service`

* **Status:** Aceito
* **Data:** 2026-08-28
* **Autores:** Equipe de Engenharia
* **Bounded Contexts Afetados:** Checkout, Finance, Inventory, Shared
* **Serviços:** `apps/inv-service`

---

## 1. Contexto e Problema (Context & Problem Statement)

O sistema do Pet Shop necessita de operações altamente integradas entre controle de estoque, fluxo de caixa/contabilidade e terminal de ponto de venda (checkout). 

A abordagem tradicional de microsserviços distribuídos em excesso (nano-serviços) traria:
1. Overhead de rede e serialização (latência desnecessária entre operações síncronas de venda e reserva).
2. Complexidade operacional com múltiplos pipelines, bancos isolados exigindo transações distribuídas (2PC/Saga complexa) para operações que ocorrem no mesmo instante de negócio.
3. Risco de consistência e aumento de falhas parciais em ambiente de baixo tráfego inicial.

Por outro lado, um monólito clássico sem barreiras arquiteturais ("Big Ball of Mud") com pacotes genéricos como `model`, `service` e `repository` degrada rapidamente a manutenibilidade, permitindo acoplamento indevido entre regras financeiras e controllers de PDV.

---

## 2. Drivers de Decisão (Decision Drivers / Forces)

* **Baixa Latência no PDV:** Operações de abertura de venda e reserva de estoque precisam responder em poucos milissegundos.
* **Integridade Transacional ACID:** A capacidade de coordenar reserva de estoque, gravação de venda e emissão de eventos dentro da mesma transação de banco de dados sem 2PC (Two-Phase Commit).
* **Alta Coesão e Baixo Acoplamento:** As regras do motor contábil/financeiro não devem vazar para a lógica de checkout ou inventário.
* **Manutenibilidade e Evolução Independente:** Facilidade para extrair um módulo (ex: `finance`) para um microsserviço isolado no futuro, caso a escala justifique.

---

## 3. Opções Consideradas (Considered Options)

1. **Opção 1: Microsserviços 100% Granulares:** Separar `checkout-service`, `finance-service` e `inventory-service` em três repositórios/processos distintos com bancos de dados próprios e comunicação gRPC/REST.
2. **Opção 2: Monólito Tradicional em Camadas:** Um único projeto fatiado por camadas técnicas (`controller`, `service`, `repository`, `entity`).
3. **Opção 3: Monólito Modular (Modular Monolith / Bounded Contexts DDD):** Um único artefato de execução (`apps/inv-service`) estruturado internamente por Bounded Contexts estritos (`checkout`, `finance`, `inventory`, `shared`), com isolamento de pacotes e comunicação assíncrona por eventos internos (`ApplicationEventPublisher`) ou mensageria.

---

## 4. Decisão Tomada e Justificativa (Decision Outcome)

Decidimos adotar a **Opção 3: Monólito Modular com Bounded Contexts**, porque:
* Permite executar transações locais confiáveis no PostgreSQL ([`V1__Schema_Inicial.sql`](../../apps/inv-service/src/main/resources/db/migration/V1__Schema_Inicial.sql)) enquanto garante limites arquiteturais claros entre os domínios.
* A comunicação entre domínios no mesmo processo ocorre via casos de uso bem definidos ou eventos de integração (`LancamentoRegistradoEvent`, `VendaConcluidaEvent`).
* Mantém a stack simplificada para desenvolvimento local e testes de integração com Testcontainers.

### Estrutura de Pacotes Adotada
```plaintext
apps/inv-service/src/main/java/inv/
├── checkout/       # Domínio de Vendas, Pagamentos e Long Polling PDV
├── finance/        # Motor Contábil: Ledger Append-Only, CQRS e Fechamento D+0
├── inventory/      # Gestão de Produtos, Baixa Atômica e Alertas de Estoque
└── shared/         # Transversal: Outbox, Configurações RabbitMQ/Segurança e Eventos Compartilhados
```

### Consequências e Trade-offs

* **Positivas (+):**
  * Desempenho máximo sem saltos de rede entre estoque e checkout.
  * Facilidade de refatoração e testes de ponta a ponta em memória.
  * Redução drástica do custo de infraestrutura (menos containers JVM rodando simultaneamente).
* **Negativas / Desafios (-):**
  * Exige disciplina rígida da equipe de engenharia para não realizar injeções diretas indevidas entre repositories de domínios diferentes.
  * O deploy do `inv-service` atualiza todos os subdomínios simultaneamente.

---

## 5. Prós e Contras das Opções Analisadas

### Opção 1: Microsserviços 100% Granulares
* **Prós:** Deploy isolado e escalabilidade granular por serviço.
* **Contras:** Latência de rede, necessidade de Sagas distribuídas, monitoramento mais complexo e custo de infraestrutura elevado.

### Opção 2: Monólito Tradicional em Camadas
* **Prós:** Simplicidade de escrita inicial.
* **Contras:** Alto risco de espaguete arquitetural; regras contábeis misturadas com regras de estoque em services gigantes.

### Opção 3: Monólito Modular (Escolhida)
* **Prós:** Organização de DDD, limites limpos, transações locais e facilidade para desacoplamento futuro.
* **Contras:** Compartilha o ciclo de vida de deploy e recursos de CPU/Memória da mesma JVM.

---

## 6. Estratégia de Validação & Testes

* **Testes de Arquitetura (ArchUnit):** Especificado em [`ArquiteturaTest.java`](../../apps/inv-service/src/test/java/inv/architect/ArquiteturaTest.java) com 3 regras isoladas para validação de fronteiras DDD.

> [!WARNING]
> **Aviso de Governança & Débito Técnico [DEBT-ARCH-001]:**
> * **Status Atual:** 
>   1. `VendaPagaEvent` foi migrado com sucesso para `inv.shared.event`, eliminando as violações do `VendaPagaEventListener`.
>   2. A regra de isolamento do `Checkout` (`checkoutNaoDeveDependerDeFinance`) está **ativa e 100% verde no CI**, garantindo a integridade do domínio de vendas.
>   3. As 22 violações restantes são de responsabilidade exclusiva do **`DashboardService`** (que injeta `VendaRepository` e `ProdutoRepository`).
> * **Medida Mitigatória:** As regras que envolvem `Finance` foram anotadas individualmente com `@ArchIgnore`, mantendo a suíte verde no CI sem desproteger o `Checkout`.
> * **Item de Acompanhamento (Tracking Item):**
>   * **`[DEBT-ARCH-001]`**: Refatorar o `DashboardService` (desacoplando agregação executiva de modelos transacionais crus para modelos de leitura CQRS ou camada de aplicação/BFF).
>   * **Responsável:** Time Core/Backend.
>   * **Prazo/Sprint:** Sprint 24 (Data Alvo: 15/10/2026).

* **Testes de Integração de Módulo (Planejados / Backlog):** Proposta de testes utilizando Spring Boot `@SpringBootTest` com fatiamento de contexto parcial (`@ContextConfiguration`) para validar a inicialização isolada de cada Bounded Context. *(Nota de Auditoria: Este teste de contexto parcial ainda não existe no repositório; o único `@SpringBootTest` atualmente implementado no `inv-service` é o `OutboxConcurrencyManualRunner`).*

---

## 7. Referências e Links Relevantes

* [Estrutura do Inv-Service](../../apps/inv-service/src/main/java/inv)
* [Documentação do Domínio Checkout](../domains/checkout.md)
* [Documentação do Domínio Finance](../domains/finance.md)
* [Documentação do Domínio Inventory](../domains/inventory.md)
