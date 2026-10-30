package auth.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PasswordResetToken — Testes de Domínio e Ciclo de Vida do Token")
class PasswordResetTokenTest {

    @Test
    @DisplayName("Deve inicializar token com validade de aproximadamente 30 minutos e estado não expirado")
    void deveInicializarTokenComValidadeFutura() {
        Usuario usuario = new Usuario("Gabriel", "gabriel@petshop.com", "senha");
        PasswordResetToken resetToken = new PasswordResetToken("token-123", usuario);

        assertThat(resetToken.getToken()).isEqualTo("token-123");
        assertThat(resetToken.getUsuario()).isEqualTo(usuario);
        assertThat(resetToken.getExpiryDate()).isAfter(LocalDateTime.now().plusMinutes(29));
        assertThat(resetToken.getExpiryDate()).isBefore(LocalDateTime.now().plusMinutes(31));
        assertThat(resetToken.isExpired()).isFalse();
    }

    @Test
    @DisplayName("isExpired() deve retornar true quando a data de expiração estiver no passado")
    void isExpiredDeveRetornarTrueQuandoExpirado() {
        Usuario usuario = new Usuario("Gabriel", "gabriel@petshop.com", "senha");
        PasswordResetToken resetToken = new PasswordResetToken("token-123", usuario);

        ReflectionTestUtils.setField(resetToken, "expiryDate", LocalDateTime.now().minusSeconds(1));

        assertThat(resetToken.isExpired()).isTrue();
    }

    @Test
    @DisplayName("atualizarToken() deve atualizar a string do token e renovar a expiração para +30 minutos")
    void atualizarTokenDeveRenovarTokenEValidade() {
        Usuario usuario = new Usuario("Gabriel", "gabriel@petshop.com", "senha");
        PasswordResetToken resetToken = new PasswordResetToken("token-antigo", usuario);
        ReflectionTestUtils.setField(resetToken, "expiryDate", LocalDateTime.now().minusMinutes(10));

        resetToken.atualizarToken("token-novo-xyz");

        assertThat(resetToken.getToken()).isEqualTo("token-novo-xyz");
        assertThat(resetToken.isExpired()).isFalse();
        assertThat(resetToken.getExpiryDate()).isAfter(LocalDateTime.now().plusMinutes(29));
    }

    @Test
    @DisplayName("equals e hashCode devem seguir a identidade por id")
    void equalsEHashCode() {
        PasswordResetToken token1 = new PasswordResetToken("t1", null);
        PasswordResetToken token2 = new PasswordResetToken("t2", null);

        ReflectionTestUtils.setField(token1, "id", 1L);
        ReflectionTestUtils.setField(token2, "id", 1L);

        assertThat(token1).isEqualTo(token2);
        assertThat(token1.hashCode()).isEqualTo(token2.hashCode());

        ReflectionTestUtils.setField(token2, "id", 2L);
        assertThat(token1).isNotEqualTo(token2);
        assertThat(token1).isNotEqualTo(null);
        assertThat(token1).isNotEqualTo("outra-classe");
    }
}
