package auth.controller;

import auth.dto.request.ForgotPasswordRequest;
import auth.dto.request.RegisterRequest;
import auth.dto.request.ResetPasswordRequest;
import auth.dto.response.GenericResponse;
import auth.dto.response.RegisterResponse;
import auth.model.Usuario;
import auth.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController — Testes Unitários dos Endpoints de Autenticação")
class AuthControllerTest {

    @Mock
    private AuthService authService;

    @InjectMocks
    private AuthController authController;

    @Test
    @DisplayName("register() deve retornar 201 Created com header Location e email sanitizado contra XSS")
    void registerDeveRetornar201CreatedComLocationEEmailSanitizado() {
        // Given
        RegisterRequest request = new RegisterRequest();
        request.setNome("Gabriel");
        request.setEmail("gabriel<test>@petshop.com");
        request.setSenha("SenhaForte@123");

        Usuario usuarioCriado = new Usuario("Gabriel", "gabriel<test>@petshop.com", "hash");
        ReflectionTestUtils.setField(usuarioCriado, "id", 77L);

        when(authService.register(request)).thenReturn(usuarioCriado);

        // When
        ResponseEntity<RegisterResponse> response = authController.register(request);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create("/usuarios/77"));

        RegisterResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isEqualTo(77L);
        assertThat(body.message()).isEqualTo("Usuário registrado com sucesso");
        // Verifica que caracteres como < e > foram sanitizados por HtmlUtils.htmlEscape
        assertThat(body.email()).isEqualTo("gabriel&lt;test&gt;@petshop.com");

        verify(authService).register(request);
    }

    @Test
    @DisplayName("forgotPassword() deve delegar para o serviço e retornar 200 OK com mensagem genérica")
    void forgotPasswordDeveRetornar200Ok() {
        // Given
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        request.setEmail("contato@petshop.com");

        // When
        ResponseEntity<GenericResponse> response = authController.forgotPassword(request);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Se o email existir, um link de recuperação foi enviado.");

        verify(authService).forgotPassword(request);
    }

    @Test
    @DisplayName("forgotPassword() deve retornar 200 OK genérico mesmo quando AuthService lançar ResourceNotFoundException (Anti-enumeração)")
    void forgotPasswordDeveRetornar200OkMesmoQuandoEmailNaoExistir() {
        // Given
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        request.setEmail("inexistente@petshop.com");

        doThrow(new common.exception.ResourceNotFoundException("Email não encontrado"))
                .when(authService).forgotPassword(request);

        // When
        ResponseEntity<GenericResponse> response = authController.forgotPassword(request);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Se o email existir, um link de recuperação foi enviado.");
    }

    @Test
    @DisplayName("resetPassword() deve delegar para o serviço e retornar 200 OK com mensagem de confirmação")
    void resetPasswordDeveRetornar200Ok() {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setToken("token-valido-123");
        request.setNewPassword("NovaSenha@123");
        request.setConfirmPassword("NovaSenha@123");

        // When
        ResponseEntity<GenericResponse> response = authController.resetPassword(request);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Senha alterada com sucesso!");

        verify(authService).resetPassword(request);
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("Testes com MockMvc e Bean Validation (@Valid)")
    class MockMvcValidationTests {

        private org.springframework.test.web.servlet.MockMvc mockMvc;
        private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

        @org.junit.jupiter.api.BeforeEach
        void setUpMockMvc() {
            mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                    .standaloneSetup(authController)
                    .setControllerAdvice(new common.exception.GlobalExceptionHandler())
                    .build();
        }

        @Test
        @DisplayName("POST /usuarios/register deve retornar 400 Bad Request quando dados forem inválidos")
        void registerDeveRetornar400QuandoInvalido() throws Exception {
            RegisterRequest invalidReq = new RegisterRequest();
            invalidReq.setNome("A"); // curto demais
            invalidReq.setEmail("nao-email");
            invalidReq.setSenha("fraca");

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/usuarios/register")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidReq)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.title").value("Dados Inválidos"));
        }

        @Test
        @DisplayName("POST /usuarios/forgot-password deve retornar 400 Bad Request quando email for inválido")
        void forgotPasswordDeveRetornar400QuandoEmailInvalido() throws Exception {
            ForgotPasswordRequest invalidReq = new ForgotPasswordRequest();
            invalidReq.setEmail("email-invalido");

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/usuarios/forgot-password")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidReq)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.title").value("Dados Inválidos"));
        }

        @Test
        @DisplayName("POST /usuarios/reset-password deve retornar 400 Bad Request quando campos forem brancos")
        void resetPasswordDeveRetornar400QuandoCamposBrancos() throws Exception {
            ResetPasswordRequest invalidReq = new ResetPasswordRequest();
            invalidReq.setToken("");
            invalidReq.setNewPassword("");
            invalidReq.setConfirmPassword("");

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/usuarios/reset-password")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidReq)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.title").value("Dados Inválidos"));
        }

        @Test
        @DisplayName("POST /usuarios/register deve retornar 201 Created quando dados forem válidos")
        void registerDeveRetornar201QuandoValido() throws Exception {
            RegisterRequest req = new RegisterRequest();
            req.setNome("Gabriel Valido");
            req.setEmail("gabriel.valido@petshop.com");
            req.setSenha("Forte@1234");

            Usuario usuario = new Usuario("Gabriel Valido", "gabriel.valido@petshop.com", "hash");
            ReflectionTestUtils.setField(usuario, "id", 99L);
            when(authService.register(any(RegisterRequest.class))).thenReturn(usuario);

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/usuarios/register")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Location", "/usuarios/99"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.id").value(99))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.email").value("gabriel.valido@petshop.com"));
        }
    }
}
