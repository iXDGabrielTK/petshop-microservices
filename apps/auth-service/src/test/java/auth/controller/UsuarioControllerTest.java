package auth.controller;

import auth.dto.request.UserSettingsRequest;
import auth.dto.response.UserResponse;
import auth.model.Theme;
import auth.model.Usuario;
import auth.service.UsuarioService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UsuarioController — Testes Unitários de Perfil e Preferências")
class UsuarioControllerTest {

    @Mock
    private UsuarioService usuarioService;

    @InjectMocks
    private UsuarioController usuarioController;

    @Test
    @DisplayName("me() deve extrair o claim 'sub' do JWT e retornar dados do usuário autenticado")
    void meDeveRetornarDadosDoUsuarioAutenticado() {
        // Given
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaimAsString("sub")).thenReturn("logado@petshop.com");

        UserResponse expectedResponse = new UserResponse(
                10L,
                "Usuario Logado",
                "logado@petshop.com",
                Set.of("ROLE_USER"),
                "LIGHT"
        );
        when(usuarioService.obterDadosUsuarioLogado("logado@petshop.com")).thenReturn(expectedResponse);

        // When
        ResponseEntity<UserResponse> response = usuarioController.me(jwt);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expectedResponse);
        verify(usuarioService).obterDadosUsuarioLogado("logado@petshop.com");
    }

    @Test
    @DisplayName("updateSettings() deve delegar atualização para UsuarioService e retornar 204 No Content com Usuario")
    void updateSettingsDeveAtualizarPreferenciasERetornar204() {
        // Given
        Usuario usuario = new Usuario("Lucas", "lucas@petshop.com", "hash");
        ReflectionTestUtils.setField(usuario, "id", 25L);

        UserSettingsRequest request = new UserSettingsRequest(Theme.DARK);

        // When
        ResponseEntity<Void> response = usuarioController.updateSettings(request, usuario);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(usuarioService).updateSettings(25L, request);
    }

    @Test
    @DisplayName("updateSettings() deve extrair user_id do JWT quando autenticado via Bearer Token")
    void updateSettingsComJwtComUserId() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaims()).thenReturn(java.util.Map.of("user_id", 88L));

        UserSettingsRequest request = new UserSettingsRequest(Theme.DARK);

        ResponseEntity<Void> response = usuarioController.updateSettings(request, jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(usuarioService).updateSettings(88L, request);
    }

    @Test
    @DisplayName("updateSettings() deve buscar por email quando JWT não possuir claim user_id")
    void updateSettingsComJwtSemUserId() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaims()).thenReturn(java.util.Collections.emptyMap());
        when(jwt.getClaimAsString("sub")).thenReturn("lucas@petshop.com");

        UserSettingsRequest request = new UserSettingsRequest(Theme.SYSTEM);

        ResponseEntity<Void> response = usuarioController.updateSettings(request, jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(usuarioService).updateSettingsByEmail("lucas@petshop.com", request);
    }

    @Test
    @DisplayName("updateSettings() deve extrair id de UserDetailsImpl quando autenticado via Sessão")
    void updateSettingsComUserDetailsImpl() {
        auth.security.user.UserDetailsImpl userDetails = new auth.security.user.UserDetailsImpl(
                55L, "Admin", "admin@petshop.com", "hash", java.util.Collections.emptyList()
        );

        UserSettingsRequest request = new UserSettingsRequest(Theme.LIGHT);

        ResponseEntity<Void> response = usuarioController.updateSettings(request, userDetails);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(usuarioService).updateSettings(55L, request);
    }

    @Test
    @DisplayName("updateSettings() deve lançar IllegalArgumentException quando principal for de tipo desconhecido")
    void updateSettingsComPrincipalInvalido() {
        UserSettingsRequest request = new UserSettingsRequest(Theme.DARK);

        assertThatThrownBy(() -> usuarioController.updateSettings(request, "string-desconhecida"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Principal de autenticação não suportado");
    }

    @Test
    @DisplayName("updateSettings() deve lançar IllegalArgumentException quando principal for nulo")
    void updateSettingsComPrincipalNulo() {
        UserSettingsRequest request = new UserSettingsRequest(Theme.DARK);

        assertThatThrownBy(() -> usuarioController.updateSettings(request, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Principal de autenticação não suportado");
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("Testes com MockMvc e Bean Validation (@Valid)")
    class MockMvcValidationTests {

        private org.springframework.test.web.servlet.MockMvc mockMvc;
        private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

        @org.junit.jupiter.api.BeforeEach
        void setUpMockMvc() {
            mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                    .standaloneSetup(usuarioController)
                    .setControllerAdvice(new common.exception.GlobalExceptionHandler())
                    .build();
        }

        @Test
        @DisplayName("PATCH /usuarios/settings deve retornar 400 Bad Request quando tema for nulo")
        void updateSettingsDeveRetornar400QuandoTemaNulo() throws Exception {
            String jsonSemTema = "{\"theme\": null}";

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/usuarios/settings")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(jsonSemTema))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.title").value("Dados Inválidos"));
        }
    }
}
