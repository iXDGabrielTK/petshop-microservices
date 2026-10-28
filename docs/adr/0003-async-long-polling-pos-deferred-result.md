# ADR-0003: Long Polling Não-Bloqueante com `DeferredResult` para Atualização de PDV

* **Status:** Aceito
* **Data:** 2026-08-28
* **Autores:** Equipe de Engenharia
* **Bounded Contexts Afetados:** Checkout
* **Serviços:** `apps/inv-service`

---

## 1. Contexto e Problema (Context & Problem Statement)

No fluxo de checkout do ponto de venda (PDV), o operador inicia uma venda e apresenta ao cliente uma forma de pagamento assíncrona (como QR Code Pix ou Maquininha de Cartão Integrada).

O terminal de PDV precisa ser notificado no instante exato em que o gateway de pagamento processa a transação e envia um webhook de confirmação para a API (`/pagamentos/webhook/confirmar`).

**Problemas das abordagens tradicionais:**
1. **Short Polling Agressivo:** O PDV dispara requisições HTTP a cada 500ms (`GET /vendas/{id}/status`). Isso sobrecarrega a CPU, gera milhares de requisições desnecessárias por minuto e esgota as conexões do servidor.
2. **WebSockets Bidirecionais:** Manter conexões WebSocket stateful exige infraestrutura dedicada, complexidade de reconexão automática, heartbeat constante e dificuldade de roteamento em clusters com balanceadores de carga simples.
3. **Server-Sent Events (SSE):** Embora mais simples que WebSockets, mantém canal contínuo aberto que muitas vezes esgota limites de conexões simultâneas de clientes legados ou gateways de borda.

---

## 2. Drivers de Decisão (Decision Drivers / Forces)

* **Notificação Instantânea (Near Real-Time):** O operador de caixa não pode aguardar segundos para liberar a mercadoria após a aprovação do pagamento.
* **Eficiência de Recursos do Servidor:** Não reter threads do pool do Tomcat/Jetty enquanto o cliente está aguardando a resposta.
* **Simplicidade de Protocolo:** Uso de HTTP REST puro, compatível com qualquer cliente HTTP padrão sem bibliotecas especiais.
* **Tolerância a Timeout:** Se nenhuma mudança de estado ocorrer em 30 segundos, a conexão encerra de forma limpa (HTTP 204 No Content), permitindo que o cliente abra um novo ciclo de espera.

---

## 3. Opções Consideradas (Considered Options)

1. **Opção 1: Short Polling HTTP:** Requisições a cada 1-2 segundos.
   * *Problema:* Desperdício maciço de I/O e latência perceptível no caixa.
2. **Opção 2: WebSockets:** Conexão persistente full-duplex.
   * *Problema:* Complexidade desnecessária para um fluxo unidirecional de curta duração.
3. **Opção 3: HTTP Long Polling Não-Bloqueante com Spring `DeferredResult` e Registro em Memória:**
   * O controller recebe a requisição e retorna um `DeferredResult(30000L)`.
   * A thread do Tomcat é liberada imediatamente de volta ao pool de threads HTTP.
   * A requisição é "estacionada" em um `VendaStatusRegistry` concorrente (`ConcurrentHashMap` + `CopyOnWriteArrayList`).
   * Quando o webhook confirma o pagamento, o evento `VendaAtualizadaEvent` é publicado e o listener completa a requisição estacionada.

---

## 4. Decisão Tomada e Justificativa (Decision Outcome)

Decidimos adotar a **Opção 3: HTTP Long Polling com `DeferredResult`**.

### A. Endpoint com Suporte a Versão e Espera ([`VendaController.java`](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/web/controller/VendaController.java#L55-L80))
```java
@GetMapping("/{id}/status")
public DeferredResult<ResponseEntity<VendaStatusResponse>> consultarStatus(
        @PathVariable Long id,
        @RequestParam(required = false, defaultValue = "0") long sinceVersion,
        @RequestParam(required = false, defaultValue = "false") boolean wait) {

    DeferredResult<ResponseEntity<VendaStatusResponse>> deferredResult = new DeferredResult<>(30000L);
    deferredResult.onTimeout(() -> deferredResult.setResult(ResponseEntity.noContent().build()));

    VendaStatusResponse statusAtual = vendaService.buscarStatusVenda(id);
    if (!wait || statusAtual.version() > sinceVersion) {
        deferredResult.setResult(ResponseEntity.ok(statusAtual));
        return deferredResult;
    }

    registry.estacionarRequisicao(id, deferredResult);
    return deferredResult;
}
```

### B. Registro Seguro de Requisições Estacionadas ([`VendaStatusRegistry.java`](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/web/VendaStatusRegistry.java))
* Utiliza estruturas thread-safe (`ConcurrentHashMap<Long, CopyOnWriteArrayList<DeferredResult>>`).
* Possui callback `onCompletion` para auto-limpeza caso o cliente desconecte ou ocorra timeout.

### C. Despertar Reativo Pós-Commit ([`VendaAtualizadaListener.java`](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/messaging/VendaAtualizadaListener.java))
* Disparado estritamente após o commit no banco (`@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`).
* Recupera todas as conexões estacionadas daquela venda, preenche o payload e responde aos clientes simultaneamente.

### Consequências e Trade-offs

* **Positivas (+):**
  * Resposta em milissegundos assim que o webhook é persistido.
  * Baixo consumo de memória e threads JVM.
  * Compatibilidade com qualquer cliente HTTP (Postman, Frontend React, terminal POS C# ou Java).
* **Negativas / Desafios (-):**
  * As requisições ficam estacionadas na memória da instância JVM que as recebeu. Em ambiente de múltiplas instâncias atrás de balanceador, exige *sticky sessions* ou propagação via RabbitMQ / Redis PubSub caso o webhook atinja outra instância.

---

## 5. Prós e Contras das Opções Analisadas

| Característica | Short Polling | WebSockets | Long Polling DeferredResult (Adotada) |
| :--- | :--- | :--- | :--- |
| **Latência de Resposta** | 500ms - 2s | < 50ms | **< 50ms** |
| **Consumo de Threads** | Alto (requisições repetidas) | Baixo | **Mínimo (Non-blocking)** |
| **Complexidade Cliente** | Baixíssima | Média/Alta | **Baixíssima (HTTP GET)** |
| **Overhead de Infra** | Alto (I/O inútil) | Alto (Conexões ativas) | **Baixíssimo** |

---

## 6. Estratégia de Validação & Testes

* **Teste Assíncrono com MockMvc:**
  1. Chamar `GET /vendas/1/status?wait=true&sinceVersion=1` e validar que a requisição entra em estado assíncrono pendente.
  2. Em outra thread, invocar a confirmação de pagamento da venda.
  3. Validar se o `DeferredResult` é resolvido instantaneamente com status `CONCLUIDA` e HTTP 200.
* **Teste de Timeout Gracioso:**
  * Chamar com `wait=true` sem atualizar a venda e aguardar expirar o tempo limite. Validar retorno HTTP 204 No Content.

---

## 7. Referências e Links Relevantes

* [VendaController](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/web/controller/VendaController.java)
* [VendaStatusRegistry](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/web/VendaStatusRegistry.java)
* [VendaAtualizadaListener](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/messaging/VendaAtualizadaListener.java)
