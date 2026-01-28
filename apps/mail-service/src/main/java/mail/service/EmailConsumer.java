package mail.service;

import mail.message.EstoqueBaixoMessage;
import mail.message.PasswordResetMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailConsumer {

    @Value("${frontend.base-url}")
    private String frontendBaseUrl;

    @Value("${initial.admin.email}")
    private String adminEmail;

    private final Logger logger = LoggerFactory.getLogger(EmailConsumer.class);


    private final JavaMailSender mailSender;

    public EmailConsumer(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @RabbitListener(queues = mail.config.RabbitMQConfig.QUEUE_NAME)
    public void receivePasswordResetMessage(PasswordResetMessage message) {
        logger.info("Recebido pedido de reset para: {}", message.getEmail());

        try {
            sendEmail(message);
        } catch (Exception e) {
            logger.error("Erro ao enviar email de redefinição de senha: {}", e.getMessage());
            throw e;
        }
    }

    private void sendEmail(PasswordResetMessage dados) {
        SimpleMailMessage email = new SimpleMailMessage();
        email.setTo(dados.getEmail());
        email.setSubject("Recuperação de Senha - PetShop");


        String linkRecuperacao = frontendBaseUrl + "/redefinir-senha?token=" + dados.getToken();

        String texto = String.format("""
            Olá, %s!
            
            Você solicitou a redefinição de senha. Clique no link abaixo para criar uma nova senha:
            
            %s
            
            Se você não solicitou isso, ignore este e-mail.
            (O link expira em 30 minutos)
            """,
                dados.getNomeUsuario() != null ? dados.getNomeUsuario() : "Usuário",
                linkRecuperacao);

        email.setText(texto);

        mailSender.send(email);
        logger.info("Email enviado com sucesso!");
    }

    @RabbitListener(queues = mail.config.RabbitMQConfig.LOW_STOCK_QUEUE)
    public void receiveLowStockMessage(EstoqueBaixoMessage message) {
        logger.info("Recebido pedido de estoque: {}", message.nomeProduto());

        SimpleMailMessage email = new SimpleMailMessage();
        email.setTo(adminEmail);
        email.setSubject("ALERTA: Estoque Baixo - " + message.nomeProduto());

        String texto = String.format("""
            Atenção, Gerente!
            
            O produto '%s' atingiu o nível crítico.
            
            Estoque Atual: %s
            Mínimo Definido: %s
            
            Providencie a reposição imediatamente.
            """,
                message.nomeProduto(),
                message.estoqueAtual(),
                message.estoqueMinimo());

        email.setText(texto);

        try {
            mailSender.send(email);
            logger.info("Email de estoque baixo enviado com sucesso!");
        } catch (Exception e) {
            logger.error("❌ Falha ao enviar email de estoque (Tentando novamente...): ", e);
            throw e;
        }
    }
}