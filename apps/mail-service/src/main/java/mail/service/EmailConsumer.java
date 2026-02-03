package mail.service;

import mail.message.EstoqueBaixoMessage;
import mail.message.PasswordResetMessage;
import mail.model.ProcessedEvent;
import mail.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class EmailConsumer {

    @Value("${frontend.base-url}")
    private String frontendBaseUrl;

    @Value("${initial.admin.email}")
    private String adminEmail;

    private final Logger logger = LoggerFactory.getLogger(EmailConsumer.class);
    private final JavaMailSender mailSender;
    private final ProcessedEventRepository processedEventRepository;

    public EmailConsumer(JavaMailSender mailSender, ProcessedEventRepository processedEventRepository) {
        this.mailSender = mailSender;
        this.processedEventRepository = processedEventRepository;
    }

    // --- LISTENER 1: RESET DE SENHA ---
    @Transactional
    @RabbitListener(queues = mail.config.RabbitMQConfig.QUEUE_NAME)
    public void receivePasswordResetMessage(PasswordResetMessage message) {
        if (isDuplicated(message.getEventId())) return;

        logger.info("Recebido pedido v{} de reset para: {}", message.getVersion(), message.getEmail());

        try {
            switch (message.getVersion()) {
                case 1:
                    processarResetSenhaV1(message);
                    break;
                case 2:
                    logger.warn("Versão 2 de Reset não implementada. Usando fallback V1.");
                    processarResetSenhaV1(message);
                    break;
                default:
                    logger.warn("Versão desconhecida {}. Tentando processar como reset de senha na V1.", message.getVersion());
                    processarResetSenhaV1(message);
            }

            markAsProcessed(message.getEventId(), "PASSWORD_RESET");
        } catch (Exception e) {
            logger.error("Erro ao processar reset v{}: ", message.getVersion(), e);
            throw e; // Nack para retry
        }
    }

    // --- LISTENER 2: ESTOQUE BAIXO ---
    @Transactional
    @RabbitListener(queues = mail.config.RabbitMQConfig.LOW_STOCK_QUEUE)
    public void receiveLowStockMessage(EstoqueBaixoMessage message) {
        if (isDuplicated(message.eventId())) return;

        logger.info("Recebido alerta v{} de estoque: {}", message.version(), message.nomeProduto());

        try {
            switch (message.version()) {
                case 1:
                    processarEstoqueV1(message);
                    break;
                case 2:
                    logger.warn("Versão 2 ainda não implementada, usando fallback V1");
                    processarEstoqueV1(message);
                    break;
                default:
                    logger.warn("Versão desconhecida {}. Tentando processar como low stock na V1.", message.version());
                    processarEstoqueV1(message);
            }

            markAsProcessed(message.eventId(), "STOCK_LOW");
        } catch (Exception e) {
            logger.error("Falha ao processar v{}: ", message.version(), e);
            throw e;
        }
    }

    // --- MÉTODOS AUXILIARES ---

    private boolean isDuplicated(String eventId) {
        if (eventId != null && processedEventRepository.existsByEventId(eventId)) {
            logger.warn("♻️ Evento duplicado ignorado: {}", eventId);
            return true;
        }
        return false;
    }

    private void markAsProcessed(String eventId, String type) {
        if (eventId != null) {
            processedEventRepository.save(new ProcessedEvent(eventId, LocalDateTime.now(), type));
            logger.info("✅ Evento {} processado e salvo.", eventId);
        }
    }

    private void processarEstoqueV1(EstoqueBaixoMessage dados) {
        SimpleMailMessage email = new SimpleMailMessage();
        email.setTo(adminEmail);
        email.setSubject("[v1] ALERTA: Estoque Baixo - " + dados.nomeProduto());
        email.setText("Produto: " + dados.nomeProduto() + "\nEstoque Atual: " + dados.estoqueAtual());
        mailSender.send(email);
    }

    private void processarResetSenhaV1(PasswordResetMessage dados) {
        SimpleMailMessage email = new SimpleMailMessage();
        email.setTo(dados.getEmail());
        email.setSubject("[v1] Recuperação de Senha - PetShop");

        String link = frontendBaseUrl + "/redefinir-senha?token=" + dados.getToken();

        email.setText("Olá " + dados.getNomeUsuario() + ",\n\n" +
                "Recebemos um pedido para redefinir sua senha.\n" +
                "Clique no link abaixo (Válido por 1 hora):\n\n" +
                link + "\n\n" +
                "Se não foi você, ignore este e-mail.");

        mailSender.send(email);
    }
}