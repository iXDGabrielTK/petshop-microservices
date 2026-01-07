package auth.service;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private final JavaMailSender mailSender;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Async // Executa em segundo plano para não travar a API
    public void sendResetTokenEmail(String toEmail, String token) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(toEmail);
        message.setSubject("Recuperação de Senha");
        message.setText("Para redefinir sua senha, use este token no seu aplicativo:\n\n" + token +
                "\n\n(Válido por 30 minutos)");

        // Em produção, aqui iria o link: https://seusite.com/reset-password?token=XYZ

        mailSender.send(message);
        System.out.println("E-mail de recuperação enviado para: " + toEmail);
    }
}