/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package mail.service;

import mail.message.EstoqueBaixoMessage;
import mail.message.PasswordResetMessage;
import mail.repository.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class EmailConsumerTest {

    @Test
    void sanitizaSomenteOsCamposInterpoladosEMascaraDestinatarioNoLog(CapturedOutput output) {
        JavaMailSender sender = mock(JavaMailSender.class);
        EmailConsumer consumer = new EmailConsumer(sender, mock(ProcessedEventRepository.class));
        ReflectionTestUtils.setField(consumer, "adminEmail", "admin@example.test");
        ReflectionTestUtils.setField(consumer, "frontendBaseUrl", "https://loja.example");
        consumer.receiveLowStockMessage(new EstoqueBaixoMessage(1, null, "Ração\r\n\tseca", BigDecimal.ONE, BigDecimal.TEN));
        consumer.receivePasswordResetMessage(new PasswordResetMessage(1, null, "alice@example.test", "token", "Ana\r\nSilva"));

        var emails = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, times(2)).send(emails.capture());
        var estoque = emails.getAllValues().get(0);
        assertThat(estoque.getSubject()).isEqualTo("[v1] ALERTA: Estoque Baixo - Ração seca");
        assertThat(estoque.getText()).startsWith("Produto: Ração seca\nEstoque Atual:");
        var reset = emails.getAllValues().get(1);
        assertThat(reset.getTo()).containsExactly("alice@example.test");
        assertThat(reset.getText()).startsWith("Olá Ana Silva,\n").contains("https://loja.example/redefinir-senha?token=token");
        assertThat(output.getAll()).contains("a***@example.test").doesNotContain("alice@example.test", "Ração\r\n");
    }
}
