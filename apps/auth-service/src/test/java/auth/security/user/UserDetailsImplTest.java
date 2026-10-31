package auth.security.user;

import auth.model.Role;
import auth.model.Usuario;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UserDetailsImpl — Testes de Domínio de Segurança e Serialização Jackson")
class UserDetailsImplTest {

    @Test
    @DisplayName("Deve inicializar corretamente via construtor a partir de Usuario")
    void deveInicializarViaUsuario() {
        Usuario usuario = new Usuario("Roberto", "roberto@petshop.com", "senhaSegura");
        ReflectionTestUtils.setField(usuario, "id", 55L);
        usuario.adicionarRole(new Role(1L, "USER"));

        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        UserDetailsImpl details = new UserDetailsImpl(usuario, authorities);

        assertThat(details.getId()).isEqualTo(55L);
        assertThat(details.getNome()).isEqualTo("Roberto");
        assertThat(details.getUsername()).isEqualTo("roberto@petshop.com");
        assertThat(details.getPassword()).isEqualTo("senhaSegura");
        assertThat(details.getAuthorities()).isEqualTo(authorities);
        assertThat(details.isAccountNonExpired()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(details.isCredentialsNonExpired()).isTrue();
        assertThat(details.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("Deve permitir setters de username e password")
    void devePermitirSettersDeUsernameEPassword() {
        UserDetailsImpl details = new UserDetailsImpl();
        details.setId(10L);
        details.setNome("Nome");
        details.setUsername("user@petshop.com");
        details.setPassword("novasenha");
        details.setAuthorities(List.of(new SimpleGrantedAuthority("ROLE_USER")));

        assertThat(details.getId()).isEqualTo(10L);
        assertThat(details.getNome()).isEqualTo("Nome");
        assertThat(details.getUsername()).isEqualTo("user@petshop.com");
        assertThat(details.getPassword()).isEqualTo("novasenha");
    }

    @Test
    @DisplayName("Deve serializar e desserializar com sucesso usando Jackson e UserDetailsImplMixin")
    void deveSerializarEDesserializarComJackson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModules(SecurityJackson2Modules.getModules(getClass().getClassLoader()));
        mapper.addMixIn(UserDetailsImpl.class, UserDetailsImplMixin.class);

        var authorities = new java.util.ArrayList<org.springframework.security.core.GrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        UserDetailsImpl original = new UserDetailsImpl(99L, "Carlos", "carlos@petshop.com", "secret", authorities);

        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("@class");
        assertThat(json).contains("auth.security.user.UserDetailsImpl");
        assertThat(json).contains("carlos@petshop.com");

        UserDetailsImpl deserialized = mapper.readValue(json, UserDetailsImpl.class);
        assertThat(deserialized.getId()).isEqualTo(99L);
        assertThat(deserialized.getNome()).isEqualTo("Carlos");
        assertThat(deserialized.getUsername()).isEqualTo("carlos@petshop.com");
        assertThat(deserialized.getPassword()).isEqualTo("secret");
        assertThat(deserialized.getAuthorities())
                .extracting(auth -> auth.getAuthority())
                .containsExactly("ROLE_USER");
    }
}
