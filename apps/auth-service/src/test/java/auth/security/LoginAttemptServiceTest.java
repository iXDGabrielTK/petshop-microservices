/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LoginAttemptService — Testes Unitários de Lockout contra Brute Force")
class LoginAttemptServiceTest {

    private MutableClock clock;
    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-25T12:00:00Z"));
        service = new LoginAttemptService(5, Duration.ofMinutes(15), 100, clock);
    }

    @Test
    @DisplayName("Deve bloquear após 5 falhas consecutivas para a mesma combinação de username e IP")
    void deveBloquearAposCincoFalhas() {
        for (int i = 0; i < 4; i++) {
            service.recordFailure("admin@petshop.com", "10.0.0.1");
            assertThat(service.isBlocked("admin@petshop.com", "10.0.0.1")).isFalse();
        }

        service.recordFailure("admin@petshop.com", "10.0.0.1");
        assertThat(service.isBlocked("admin@petshop.com", "10.0.0.1")).isTrue();
    }

    @Test
    @DisplayName("Deve normalizar espaços e caixa do username para compartilhar o mesmo bloqueio")
    void deveNormalizarUsername() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("  Admin@Petshop.COM ", "10.0.0.1");
        }

        assertThat(service.isBlocked("admin@petshop.com", "10.0.0.1")).isTrue();
        assertThat(service.isBlocked("ADMIN@PETSHOP.COM", " 10.0.0.1 ")).isTrue();
    }

    @Test
    @DisplayName("Deve isolar bloqueio por IP sem afetar outros endereços")
    void deveIsolarBloqueioPorIp() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("admin@petshop.com", "10.0.0.1");
        }

        assertThat(service.isBlocked("admin@petshop.com", "10.0.0.1")).isTrue();
        assertThat(service.isBlocked("admin@petshop.com", "10.0.0.2")).isFalse();
    }

    @Test
    @DisplayName("Deve limpar contador de falhas após autenticação bem-sucedida")
    void deveLimparContadorAposSucesso() {
        for (int i = 0; i < 4; i++) {
            service.recordFailure("user@petshop.com", "10.0.0.1");
        }

        service.recordSuccess("user@petshop.com", "10.0.0.1");

        for (int i = 0; i < 4; i++) {
            service.recordFailure("user@petshop.com", "10.0.0.1");
            assertThat(service.isBlocked("user@petshop.com", "10.0.0.1")).isFalse();
        }
    }

    @Test
    @DisplayName("Deve desbloquear automaticamente após expiração da duração do lockout e reiniciar janela")
    void deveDesbloquearAposExpiracao() {
        for (int i = 0; i < 5; i++) {
            service.recordFailure("user@petshop.com", "10.0.0.1");
        }
        assertThat(service.isBlocked("user@petshop.com", "10.0.0.1")).isTrue();

        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        assertThat(service.isBlocked("user@petshop.com", "10.0.0.1")).isTrue();

        clock.advance(Duration.ofSeconds(1));
        assertThat(service.isBlocked("user@petshop.com", "10.0.0.1")).isFalse();

        service.recordFailure("user@petshop.com", "10.0.0.1");
        assertThat(service.isBlocked("user@petshop.com", "10.0.0.1")).isFalse();
    }

    @Test
    @DisplayName("Deve respeitar limite máximo de entradas e priorizar retenção de chaves bloqueadas")
    void deveRespeitarLimiteMaximoDeEntradas() {
        LoginAttemptService bounded = new LoginAttemptService(5, Duration.ofMinutes(15), 3, clock);

        for (int i = 0; i < 5; i++) {
            bounded.recordFailure("atacante@petshop.com", "192.168.1.10");
        }
        assertThat(bounded.isBlocked("atacante@petshop.com", "192.168.1.10")).isTrue();

        bounded.recordFailure("normal1@petshop.com", "192.168.1.11");
        bounded.recordFailure("normal2@petshop.com", "192.168.1.12");
        bounded.recordFailure("normal3@petshop.com", "192.168.1.13");

        assertThat(bounded.trackedEntriesCount()).isEqualTo(3);
        assertThat(bounded.isBlocked("atacante@petshop.com", "192.168.1.10")).isTrue();
    }

    @Test
    @DisplayName("Deve validar parâmetros inválidos no construtor")
    void deveValidarParametrosInvalidos() {
        assertThatThrownBy(() -> new LoginAttemptService(0, Duration.ofMinutes(15), 10, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginAttemptService(5, Duration.ZERO, 10, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginAttemptService(5, Duration.ofMinutes(15), 0, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginAttemptService(5, Duration.ofMinutes(15), 10, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        private MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        void advance(Duration duration) {
            current.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
