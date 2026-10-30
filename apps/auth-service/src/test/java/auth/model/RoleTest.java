package auth.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Role — Testes de Entidade de Domínio")
class RoleTest {

    @Test
    @DisplayName("Deve retornar autoridade correspondente ao nome")
    void deveRetornarAutoridade() {
        Role role = new Role(1L, "ADMIN");
        assertThat(role.getId()).isEqualTo(1L);
        assertThat(role.getNome()).isEqualTo("ADMIN");
        assertThat(role.getAuthority()).isEqualTo("ADMIN");

        Role role2 = new Role("USER");
        assertThat(role2.getNome()).isEqualTo("USER");
        assertThat(role2.getAuthority()).isEqualTo("USER");
    }

    @Test
    @DisplayName("equals e hashCode devem seguir contrato de identidade por id")
    void equalsEHashCode() {
        Role r1 = new Role(1L, "ADMIN");
        Role r2 = new Role(1L, "OUTRO");

        assertThat(r1).isEqualTo(r2);
        assertThat(r1.hashCode()).isEqualTo(r2.hashCode());

        ReflectionTestUtils.setField(r2, "id", 2L);
        assertThat(r1).isNotEqualTo(r2);
        assertThat(r1).isNotEqualTo(null);
        assertThat(r1).isNotEqualTo("string");
    }
}
