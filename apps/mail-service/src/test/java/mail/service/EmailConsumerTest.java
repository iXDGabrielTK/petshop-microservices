/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package mail.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import mail.message.EstoqueBaixoMessage;
import mail.message.PasswordResetMessage;
import mail.repository.ProcessedEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EmailConsumerTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(EmailConsumer.class);
    private final ListAppender<ILoggingEvent> eventos = new ListAppender<>();

    @BeforeEach
    void capturarEventos() {
        eventos.start();
        logger.addAppender(eventos);
    }

    @AfterEach
    void liberarEventos() {
        logger.detachAppender(eventos);
        eventos.stop();
    }

    @ParameterizedTest
    @CsvSource(value = {"a@example.test,***@example.test", "NULL,***", "sem-dominio,***",
            "a@@example.test,***", "ana@,***"}, nullValues = "NULL")
    void mascaraDestinatarioNoLogMesmoComNomeAusente(String destinatario, String mascara) {
        JavaMailSender sender = mock(JavaMailSender.class);
        EmailConsumer consumer = new EmailConsumer(sender, mock(ProcessedEventRepository.class));
        consumer.receivePasswordResetMessage(new PasswordResetMessage(1, null, destinatario, "token", null));

        assertThat(eventos.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Recebido pedido v1 de reset para: " + mascara);
        var email = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(email.capture());
        assertThat(email.getValue().getText()).startsWith("Olá ,\n");
    }

    @Test
    void aceitaNomeDeProdutoAusenteSemInjetarTextoNoAssunto() {
        JavaMailSender sender = mock(JavaMailSender.class);
        EmailConsumer consumer = new EmailConsumer(sender, mock(ProcessedEventRepository.class));
        ReflectionTestUtils.setField(consumer, "adminEmail", "admin@example.test");
        consumer.receiveLowStockMessage(new EstoqueBaixoMessage(1, null, null, BigDecimal.ONE, BigDecimal.TEN));

        var email = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(email.capture());
        assertThat(email.getValue().getSubject()).isEqualTo("[v1] ALERTA: Estoque Baixo - ");
        assertThat(email.getValue().getText()).startsWith("Produto: \n");
    }

    @Test
    void sanitizaSomenteOsCamposInterpoladosEMascaraDestinatarioNoLog() {
        JavaMailSender sender = mock(JavaMailSender.class);
        EmailConsumer consumer = new EmailConsumer(sender, mock(ProcessedEventRepository.class));
        ReflectionTestUtils.setField(consumer, "adminEmail", "admin@example.test");
        ReflectionTestUtils.setField(consumer, "frontendBaseUrl", "https://loja.example");
        consumer.receiveLowStockMessage(new EstoqueBaixoMessage(1, null, "Ração\r\n\tseca", BigDecimal.ONE, BigDecimal.TEN));
        consumer.receivePasswordResetMessage(new PasswordResetMessage(1, "evento\r\nreset", "alice@example.test", "token", "Ana\r\nSilva"));

        var emails = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, times(2)).send(emails.capture());
        var estoque = emails.getAllValues().get(0);
        assertThat(estoque.getSubject()).isEqualTo("[v1] ALERTA: Estoque Baixo - Ração seca");
        assertThat(estoque.getText()).startsWith("Produto: Ração seca\nEstoque Atual:");
        var reset = emails.getAllValues().get(1);
        assertThat(reset.getTo()).containsExactly("alice@example.test");
        assertThat(reset.getText()).startsWith("Olá Ana Silva,\n").contains("https://loja.example/redefinir-senha?token=token");
        assertThat(eventos.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "Recebido alerta v1 de estoque: Ração seca",
                "Recebido pedido v1 de reset para: a***@example.test",
                "✅ Evento evento reset processado e salvo.");
    }
}
