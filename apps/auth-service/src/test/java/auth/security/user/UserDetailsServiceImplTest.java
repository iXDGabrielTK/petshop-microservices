package auth.security.user;

import auth.model.Role;
import auth.model.Usuario;
import auth.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserDetailsServiceImpl — Testes Unitários de Autenticação Spring Security")
class UserDetailsServiceImplTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @InjectMocks
    private UserDetailsServiceImpl userDetailsService;

    @Test
    @DisplayName("Deve carregar UserDetails com sucesso prefixando roles com 'ROLE_'")
    void deveCarregarUserDetailsComSucesso() {
        // Given
        Usuario usuario = new Usuario("Admin Teste", "admin@petshop.com", "senha-criptografada");
        ReflectionTestUtils.setField(usuario, "id", 1L);
        usuario.adicionarRole(new Role(1L, "ADMIN"));
        usuario.adicionarRole(new Role(2L, "GERENTE"));

        when(usuarioRepository.findByEmail("admin@petshop.com")).thenReturn(Optional.of(usuario));

        // When
        UserDetails userDetails = userDetailsService.loadUserByUsername("admin@petshop.com");

        // Then
        assertThat(userDetails).isNotNull();
        assertThat(userDetails.getUsername()).isEqualTo("admin@petshop.com");
        assertThat(userDetails.getPassword()).isEqualTo("senha-criptografada");
        assertThat(userDetails.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_GERENTE");

        assertThat(userDetails.isAccountNonExpired()).isTrue();
        assertThat(userDetails.isAccountNonLocked()).isTrue();
        assertThat(userDetails.isCredentialsNonExpired()).isTrue();
        assertThat(userDetails.isEnabled()).isTrue();

        assertThat(userDetails).isInstanceOf(UserDetailsImpl.class);
        UserDetailsImpl impl = (UserDetailsImpl) userDetails;
        assertThat(impl.getId()).isEqualTo(1L);
        assertThat(impl.getNome()).isEqualTo("Admin Teste");

        verify(usuarioRepository).findByEmail("admin@petshop.com");
    }

    @Test
    @DisplayName("Deve carregar UserDetails com coleção de authorities vazia quando usuário não possuir roles")
    void deveCarregarUserDetailsSemRoles() {
        // Given
        Usuario usuario = new Usuario("Sem Roles", "semroles@petshop.com", "senha123");
        ReflectionTestUtils.setField(usuario, "id", 2L);

        when(usuarioRepository.findByEmail("semroles@petshop.com")).thenReturn(Optional.of(usuario));

        // When
        UserDetails userDetails = userDetailsService.loadUserByUsername("semroles@petshop.com");

        // Then
        assertThat(userDetails).isNotNull();
        assertThat(userDetails.getAuthorities()).isEmpty();
    }

    @Test
    @DisplayName("Deve lançar UsernameNotFoundException quando usuário não for encontrado")
    void deveLancarUsernameNotFoundExceptionQuandoNaoEncontrado() {
        // Given
        when(usuarioRepository.findByEmail("desconhecido@petshop.com")).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("desconhecido@petshop.com"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("Usuário não encontrado com o login: desconhecido@petshop.com");

        verify(usuarioRepository).findByEmail("desconhecido@petshop.com");
    }
}
