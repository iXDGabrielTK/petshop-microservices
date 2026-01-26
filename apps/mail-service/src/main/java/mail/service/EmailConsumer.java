package mail.service;

import mail.message.PasswordResetMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailConsumer {

    @Value("${frontend.base-url}")
    private String frontendBaseUrl;

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
        System.out.println("✅ Email enviado com sucesso!");
    }
}