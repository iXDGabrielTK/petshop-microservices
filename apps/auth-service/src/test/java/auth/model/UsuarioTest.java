package auth.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Usuario — Testes de Entidade de Domínio")
class UsuarioTest {

    @Test
    @DisplayName("Deve inicializar com valores padrão corretos (theme LIGHT, roles vazias)")
    void deveInicializarComValoresPadrao() {
        Usuario usuario = new Usuario("Lucas", "lucas@petshop.com", "senha123");

        assertThat(usuario.getNome()).isEqualTo("Lucas");
        assertThat(usuario.getEmail()).isEqualTo("lucas@petshop.com");
        assertThat(usuario.getSenha()).isEqualTo("senha123");
        assertThat(usuario.getTheme()).isEqualTo(Theme.LIGHT);
        assertThat(usuario.getRoles()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("Deve permitir alterar senha e adicionar roles")
    void deveAlterarSenhaEAdicionarRoles() {
        Usuario usuario = new Usuario("Lucas", "lucas@petshop.com", "senhaAntiga");
        usuario.alterarSenha("senhaNovaCodificada");
        assertThat(usuario.getSenha()).isEqualTo("senhaNovaCodificada");

        Role roleAdmin = new Role(1L, "ADMIN");
        usuario.adicionarRole(roleAdmin);

        assertThat(usuario.getRoles()).containsExactly(roleAdmin);
    }

    @Test
    @DisplayName("equals e hashCode devem seguir contrato de identidade por id")
    void equalsEHashCode() {
        Usuario u1 = new Usuario("Lucas", "lucas@petshop.com", "senha");
        Usuario u2 = new Usuario("Lucas Outro", "outro@petshop.com", "senha");

        // Sem ID
        assertThat(u1).isNotEqualTo(u2);

        ReflectionTestUtils.setField(u1, "id", 10L);
        ReflectionTestUtils.setField(u2, "id", 10L);
        assertThat(u1).isEqualTo(u2);
        assertThat(u1.hashCode()).isEqualTo(u2.hashCode());

        ReflectionTestUtils.setField(u2, "id", 20L);
        assertThat(u1).isNotEqualTo(u2);
        assertThat(u1).isNotEqualTo(null);
        assertThat(u1).isNotEqualTo(new Object());
    }
}
