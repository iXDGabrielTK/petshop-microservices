package auth.service;

import auth.dto.request.UserSettingsRequest;
import auth.dto.response.UserResponse;
import auth.model.Role;
import auth.model.Theme;
import auth.model.Usuario;
import auth.repository.UsuarioRepository;
import common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UsuarioService — Testes Unitários de Gerenciamento de Usuários")
class UsuarioServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @InjectMocks
    private UsuarioService usuarioService;

    @Nested
    @DisplayName("obterDadosUsuarioLogado(String email)")
    class ObterDadosUsuarioLogadoTests {

        @Test
        @DisplayName("Deve retornar UserResponse com roles e tema quando usuário existir")
        void deveRetornarUserResponseQuandoUsuarioExiste() {
            // Given
            Usuario usuario = new Usuario("Maria Silva", "maria@petshop.com", "hash");
            ReflectionTestUtils.setField(usuario, "id", 42L);
            usuario.setTheme(Theme.DARK);
            usuario.adicionarRole(new Role(1L, "ADMIN"));
            usuario.adicionarRole(new Role(2L, "USER"));

            when(usuarioRepository.findByEmail("maria@petshop.com")).thenReturn(Optional.of(usuario));

            // When
            UserResponse response = usuarioService.obterDadosUsuarioLogado("maria@petshop.com");

            // Then
            assertThat(response).isNotNull();
            assertThat(response.id()).isEqualTo(42L);
            assertThat(response.name()).isEqualTo("Maria Silva");
            assertThat(response.email()).isEqualTo("maria@petshop.com");
            assertThat(response.roles()).containsExactlyInAnyOrder("ADMIN", "USER");
            assertThat(response.theme()).isEqualTo("DARK");

            verify(usuarioRepository).findByEmail("maria@petshop.com");
        }

        @Test
        @DisplayName("Deve retornar UserResponse com conjunto de roles vazio quando usuário não tiver roles")
        void deveRetornarUserResponseSemRolesQuandoUsuarioNaoTiverRoles() {
            // Given
            Usuario usuario = new Usuario("Sem Roles", "semroles@petshop.com", "hash");
            ReflectionTestUtils.setField(usuario, "id", 100L);

            when(usuarioRepository.findByEmail("semroles@petshop.com")).thenReturn(Optional.of(usuario));

            // When
            UserResponse response = usuarioService.obterDadosUsuarioLogado("semroles@petshop.com");

            // Then
            assertThat(response).isNotNull();
            assertThat(response.roles()).isEmpty();
            assertThat(response.theme()).isEqualTo("LIGHT");
        }

        @Test
        @DisplayName("Deve lançar ResourceNotFoundException quando usuário não for encontrado pelo email")
        void deveLancarResourceNotFoundExceptionQuandoUsuarioNaoEncontrado() {
            // Given
            when(usuarioRepository.findByEmail("inexistente@petshop.com")).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> usuarioService.obterDadosUsuarioLogado("inexistente@petshop.com"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Usuário não encontrado");

            verify(usuarioRepository).findByEmail("inexistente@petshop.com");
        }
    }

    @Nested
    @DisplayName("updateSettings(Long userId, UserSettingsRequest request)")
    class UpdateSettingsTests {

        @Test
        @DisplayName("Deve atualizar o tema do usuário com sucesso")
        void deveAtualizarTemaComSucesso() {
            // Given
            Usuario usuario = new Usuario("Lucas", "lucas@petshop.com", "hash");
            ReflectionTestUtils.setField(usuario, "id", 15L);
            usuario.setTheme(Theme.LIGHT);

            when(usuarioRepository.findById(15L)).thenReturn(Optional.of(usuario));

            UserSettingsRequest request = new UserSettingsRequest(Theme.DARK);

            // When
            usuarioService.updateSettings(15L, request);

            // Then
            assertThat(usuario.getTheme()).isEqualTo(Theme.DARK);
            verify(usuarioRepository).findById(15L);
        }

        @Test
        @DisplayName("Não deve alterar tema se request.theme for nulo")
        void naoDeveAlterarTemaSeRequestThemeForNulo() {
            // Given
            Usuario usuario = new Usuario("Lucas", "lucas@petshop.com", "hash");
            ReflectionTestUtils.setField(usuario, "id", 15L);
            usuario.setTheme(Theme.LIGHT);

            when(usuarioRepository.findById(15L)).thenReturn(Optional.of(usuario));

            UserSettingsRequest request = new UserSettingsRequest(null);

            // When
            usuarioService.updateSettings(15L, request);

            // Then
            assertThat(usuario.getTheme()).isEqualTo(Theme.LIGHT);
            verify(usuarioRepository).findById(15L);
        }

        @Test
        @DisplayName("Não deve lançar erro se request for nulo em updateSettings")
        void naoDeveLancarErroSeRequestForNulo() {
            Usuario usuario = new Usuario("Lucas", "lucas@petshop.com", "hash");
            ReflectionTestUtils.setField(usuario, "id", 15L);
            when(usuarioRepository.findById(15L)).thenReturn(Optional.of(usuario));

            usuarioService.updateSettings(15L, null);

            assertThat(usuario.getTheme()).isEqualTo(Theme.LIGHT);
        }

        @Test
        @DisplayName("Deve lançar ResourceNotFoundException quando usuário não existir")
        void deveLancarResourceNotFoundExceptionQuandoUsuarioNaoExiste() {
            // Given
            when(usuarioRepository.findById(999L)).thenReturn(Optional.empty());
            UserSettingsRequest request = new UserSettingsRequest(Theme.DARK);

            // When / Then
            assertThatThrownBy(() -> usuarioService.updateSettings(999L, request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Usuário não encontrado");

            verify(usuarioRepository).findById(999L);
        }
    }

    @Nested
    @DisplayName("updateSettingsByEmail(String email, UserSettingsRequest request)")
    class UpdateSettingsByEmailTests {

        @Test
        @DisplayName("Deve atualizar o tema do usuário por email com sucesso")
        void deveAtualizarTemaPorEmailComSucesso() {
            Usuario usuario = new Usuario("Lucas", "lucas@petshop.com", "hash");
            usuario.setTheme(Theme.LIGHT);
            when(usuarioRepository.findByEmail("lucas@petshop.com")).thenReturn(Optional.of(usuario));

            UserSettingsRequest request = new UserSettingsRequest(Theme.DARK);
            usuarioService.updateSettingsByEmail("lucas@petshop.com", request);

            assertThat(usuario.getTheme()).isEqualTo(Theme.DARK);
            verify(usuarioRepository).findByEmail("lucas@petshop.com");
        }

        @Test
        @DisplayName("Deve lançar ResourceNotFoundException quando email não existir")
        void deveLancarResourceNotFoundExceptionQuandoEmailNaoExiste() {
            when(usuarioRepository.findByEmail("inexistente@petshop.com")).thenReturn(Optional.empty());
            UserSettingsRequest request = new UserSettingsRequest(Theme.DARK);

            assertThatThrownBy(() -> usuarioService.updateSettingsByEmail("inexistente@petshop.com", request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Usuário não encontrado");
        }

        @Test
        @DisplayName("Não deve alterar tema se request for nulo em updateSettingsByEmail")
        void naoDeveAlterarTemaSeRequestNulo() {
            Usuario usuario = new Usuario("Lucas", "lucas@petshop.com", "hash");
            usuario.setTheme(Theme.LIGHT);
            when(usuarioRepository.findByEmail("lucas@petshop.com")).thenReturn(Optional.of(usuario));

            usuarioService.updateSettingsByEmail("lucas@petshop.com", null);

            assertThat(usuario.getTheme()).isEqualTo(Theme.LIGHT);
        }
    }
}
