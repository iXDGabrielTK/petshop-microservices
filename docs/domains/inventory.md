# 📦 Bounded Context: Inventory (Controle de Estoque & Catálogo)

O módulo **Inventory** é responsável pelo catálogo de produtos, rastreabilidade física de mercadorias, prevenção de *overselling* (vendas a descoberto) via **SQL Atômico** e alertas de estoque baixo orientados a eventos com proteção anti-spam.

---

## 1. Glossário da Linguagem Ubíqua (Ubiquitous Language)

| Termo | Definição no Domínio |
| :--- | :--- |
| **Produto** | Entidade de catálogo com identificação por código de barras, unidade de medida, preço e regras de estoque. |
| **Estoque Disponível** | Quantidade física de produto livre para novas compras nos caixas de PDV. |
| **Estoque Reservado** | Quantidade alocada para vendas em andamento que ainda aguardam liquidação de pagamento. |
| **Estoque Mínimo** | Ponto de reposição (*Reorder Point*); limiar crítico que dispara alertas automáticos para compras/administração. |
| **Movimentação de Estoque** | Registro de auditoria (`movimentacoes_estoque`) com tipo (`RESERVA`, `SAIDA`, `CANCELAMENTO_RESERVA`, `ENTRADA`, `AJUSTE`) e timestamp. |
| **Transição de Limiar (Anti-Spam)** | Regra que dispara o evento de alerta apenas quando o saldo cruza o limite mínimo pela primeira vez (\(Antes > Minimo\) e \(Depois \le Minimo\)), evitando spams de e-mail em vendas subsequentes. |

---

## 2. Ciclo de Vida do Estoque e Operações Atômicas

```mermaid
stateDiagram-v2
    direction LR
    state "Estoque Disponível (Livre)" as Livre
    state "Estoque Reservado (Em Checkout)" as Reservado
    state "Baixa Física Definitiva" as Vendido

    [*] --> Livre: Entrada de Mercadoria (Compra / Cadastro)
    Livre --> Reservado: Início da Venda (reservarEstoqueAtomo)
    Reservado --> Vendido: Pagamento Confirmado (confirmarBaixaEstoqueAtomo)
    Reservado --> Livre: Venda Expirada / Cancelada (estornarReservaEstoqueAtomo)
    Vendido --> [*]
```

### Comportamento das Queries SQL Nativas ([`ProdutoRepository.java`](../../apps/inv-service/src/main/java/inv/inventory/infrastructure/persistence/ProdutoRepository.java#L27-L50))

```mermaid
classDiagram
    class OperacoesEstoque {
        +reservarEstoqueAtomo(id, qtd) : UPDATE produtos SET disponivel = disponivel - qtd, reservado = reservado + qtd WHERE disponivel >= qtd RETURNING disponivel
        +confirmarBaixaEstoqueAtomo(id, qtd) : UPDATE produtos SET reservado = reservado - qtd WHERE reservado >= qtd RETURNING reservado
        +estornarReservaEstoqueAtomo(id, qtd) : UPDATE produtos SET reservado = reservado - qtd, disponivel = disponivel + qtd WHERE reservado >= qtd RETURNING disponivel
    }
```

---

## 3. Lógica de Alerta Anti-Spam para Estoque Baixo

Para evitar que múltiplos caixas vendendo o mesmo produto disparem dezenas de e-mails para o administrador quando o estoque já está abaixo do mínimo, o [`EstoqueService`](../../apps/inv-service/src/main/java/inv/inventory/usecase/EstoqueService.java#L93-L105) aplica uma **verificação de transição de estado**:

```mermaid
flowchart TD
    Inicio([Reserva de Estoque Executada]) --> Checagem{Saldo Anterior > Mínimo E Novo Saldo <= Mínimo?}
    Checagem -- SIM (Momento da Quebra) --> Disparo[Publica EstoqueAtingiuMinimoEvent -> Outbox -> RabbitMQ]
    Checagem -- NÃO (Já estava abaixo ou ainda está acima) --> Silencio[Ignora envio de alerta - Sem Spam]
    Disparo --> Fim([Fim])
    Silencio --> Fim
```

---

## 4. Modelo Físico de Dados

```mermaid
erDiagram
    PRODUTOS ||--o{ MOVIMENTACOES_ESTOQUE : registra
    PRODUTOS ||--o{ ITENS_VENDA : referencia

    PRODUTOS {
        bigint id PK
        varchar codigo_barras UK
        varchar nome
        numeric estoque_minimo
        varchar unidade_medida
        numeric estoque_disponivel
        numeric estoque_reservado
        numeric preco_venda
        bigint version
    }

    MOVIMENTACOES_ESTOQUE {
        bigint id PK
        bigint produto_id FK
        bigint venda_id FK
        varchar tipo "RESERVA, SAIDA, CANCELAMENTO_RESERVA, ENTRADA"
        numeric quantidade
        timestamp data_hora
        varchar observacao
    }
```

---

## 5. ADRs Vinculados a Este Domínio

* 📑 **[ADR-0002: Controle Híbrido de Concorrência e Estoque Atômico](../adr/0002-atomic-sql-inventory-and-hybrid-locking.md)**
* 📑 **[ADR-0004: Transactional Outbox Pattern com SKIP LOCKED](../adr/0004-transactional-outbox-with-skip-locked.md)**
* 📑 **[ADR-0008: Consumidor Idempotente e Evolução de Schemas](../adr/0008-distributed-idempotent-consumer-and-schema-evolution.md)**
