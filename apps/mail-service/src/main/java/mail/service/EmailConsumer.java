package mail.service;

import mail.dto.message.PasswordResetMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailConsumer {

    @Autowired
    private JavaMailSender mailSender;

    @RabbitListener(queues = mail.config.RabbitMQConfig.QUEUE_NAME)
    public void receivePasswordResetMessage(PasswordResetMessage message) {
        System.out.println("📨 Recebido pedido de reset para: " + message.getEmail());

        try {
            sendEmail(message);
        } catch (Exception e) {
            System.err.println("❌ Erro ao enviar email: " + e.getMessage());
            throw e;
        }
    }

    private void sendEmail(PasswordResetMessage dados) {
        SimpleMailMessage email = new SimpleMailMessage();
        email.setTo(dados.getEmail());
        email.setSubject("Recuperação de Senha - PetShop");

        String texto = String.format("Olá, %s!\n\nUse o token abaixo para redefinir sua senha:\n%s\n\nVálido por 30 minutos.",
                dados.getNomeUsuario() != null ? dados.getNomeUsuario() : "Usuário",
                dados.getToken());

        email.setText(texto);

        mailSender.send(email);
        System.out.println("✅ Email enviado com sucesso!");
    }
}