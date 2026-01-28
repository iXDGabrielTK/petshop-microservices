package inv.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxScheduler {

    private final OutboxProcessor outboxProcessor;


    public OutboxScheduler(OutboxProcessor outboxProcessor) {
        this.outboxProcessor = outboxProcessor;
    }

    @Scheduled(fixedDelay = 2000) // Pode diminuir o delay se quiser mais tempo real
    public void processOutbox() {
        boolean processed;
        do {
            try {
                // Processa uma mensagem por vez, em transações isoladas
                processed = outboxProcessor.processNext();
            } catch (Exception e) {
                // Se der erro em uma mensagem, logamos e paramos o loop atual
                // para não ficar "marretando" o banco em loop infinito se o Rabbit cair
                processed = false;
            }
        } while (processed);
        // O loop continua enquanto houver mensagens sendo processadas com sucesso.
        // Quando retornar false (fila vazia), o scheduler dorme até o próximo ciclo.
    }
}