package auth.service;

import auth.config.RabbitMQConfig;
import auth.dto.message.PasswordResetMessage;
import auth.dto.request.ForgotPasswordRequest;
import auth.dto.request.RegisterRequest;
import auth.dto.request.ResetPasswordRequest;
import auth.model.PasswordResetToken;
import auth.model.Role;
import auth.model.Usuario;
import auth.repository.PasswordResetTokenRepository;
import auth.repository.RoleRepository;
import auth.repository.UsuarioRepository;
import common.exception.BusinessException;
import common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService — Testes Unitários de Autenticação e Gestão de Contas")
class AuthServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private PasswordResetTokenRepository tokenRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    @Nested
    @DisplayName("register(RegisterRequest)")
    class RegisterTests {

        @Test
        @DisplayName("Deve registrar novo usuário com sucesso quando role USER já existe")
        void deveRegistrarUsuarioComSucessoQuandoRoleExiste() {
            // Given
            RegisterRequest request = new RegisterRequest();
            request.setNome("Gabriel");
            request.setEmail("gabriel@petshop.com");
            request.setSenha("Password@123");

            Role roleUser = new Role(1L, "USER");

            when(usuarioRepository.findByEmail("gabriel@petshop.com")).thenReturn(Optional.empty());
            when(passwordEncoder.encode("Password@123")).thenReturn("$2a$12$encodedPassword");
            when(roleRepository.findByNome("USER")).thenReturn(Optional.of(roleUser));
            when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            Usuario result = authService.register(request);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getNome()).isEqualTo("Gabriel");
            assertThat(result.getEmail()).isEqualTo("gabriel@petshop.com");
            assertThat(result.getSenha()).isEqualTo("$2a$12$encodedPassword");
            assertThat(result.getRoles()).contains(roleUser);

            verify(usuarioRepository).findByEmail("gabriel@petshop.com");
            verify(passwordEncoder).encode("Password@123");
            verify(roleRepository).findByNome("USER");
            verify(roleRepository, never()).save(any());
            verify(usuarioRepository).save(any(Usuario.class));
        }

        @Test
        @DisplayName("Deve criar role USER sob demanda se não existir e salvar novo usuário")
        void deveCriarRoleUserSobDemandaSeNaoExistir() {
            // Given
            RegisterRequest request = new RegisterRequest();
            request.setNome("Ana");
            request.setEmail("ana@petshop.com");
            request.setSenha("Password@123");

            Role roleCriada = new Role(2L, "USER");

            when(usuarioRepository.findByEmail("ana@petshop.com")).thenReturn(Optional.empty());
            when(passwordEncoder.encode("Password@123")).thenReturn("$2a$12$encodedPassword");
            when(roleRepository.findByNome("USER")).thenReturn(Optional.empty());
            when(roleRepository.save(any(Role.class))).thenReturn(roleCriada);
            when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // When
            Usuario result = authService.register(request);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getRoles()).contains(roleCriada);
            verify(roleRepository).save(argThat(r -> "USER".equals(r.getNome())));
            verify(usuarioRepository).save(any(Usuario.class));
        }

        @Test
        @DisplayName("Deve lançar BusinessException quando email já estiver cadastrado")
        void deveLancarBusinessExceptionQuandoEmailJaCadastrado() {
            // Given
            RegisterRequest request = new RegisterRequest();
            request.setNome("Carlos");
            request.setEmail("carlos@petshop.com");
            request.setSenha("Password@123");

            Usuario usuarioExistente = new Usuario("Carlos Existente", "carlos@petshop.com", "hash");
            when(usuarioRepository.findByEmail("carlos@petshop.com")).thenReturn(Optional.of(usuarioExistente));

            // When / Then
            assertThatThrownBy(() -> authService.register(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Email já cadastrado");

            verify(usuarioRepository).findByEmail("carlos@petshop.com");
            verifyNoInteractions(passwordEncoder);
            verifyNoInteractions(roleRepository);
            verify(usuarioRepository, never()).save(any());
        }

        @Test
        @DisplayName("Deve lançar BusinessException quando request ou email forem nulos")
        void deveLancarBusinessExceptionQuandoRequestOuEmailNulo() {
            assertThatThrownBy(() -> authService.register(null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Dados de registro inválidos");

            RegisterRequest reqSemEmail = new RegisterRequest();
            reqSemEmail.setNome("Sem Email");
            assertThatThrownBy(() -> authService.register(reqSemEmail))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Dados de registro inválidos");
        }
    }

    @Nested
    @DisplayName("forgotPassword(ForgotPasswordRequest)")
    class ForgotPasswordTests {

        @Test
        @DisplayName("Deve gerar novo token e enviar mensagem ao RabbitMQ quando usuário não possuía token anterior")
        void deveGerarNovoTokenEEnviarMensagemAoRabbitMQ() {
            // Given
            ForgotPasswordRequest request = new ForgotPasswordRequest();
            request.setEmail("usuario@petshop.com");

            Usuario usuario = new Usuario("Usuario Teste", "usuario@petshop.com", "hash");
            ReflectionTestUtils.setField(usuario, "id", 10L);

            when(usuarioRepository.findByEmail("usuario@petshop.com")).thenReturn(Optional.of(usuario));
            when(tokenRepository.findByUsuario(usuario)).thenReturn(Optional.empty());

            // When
            authService.forgotPassword(request);

            // Then
            ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
            verify(tokenRepository).save(tokenCaptor.capture());
            PasswordResetToken savedToken = tokenCaptor.getValue();
            assertThat(savedToken.getToken()).isNotBlank();
            assertThat(savedToken.getUsuario()).isEqualTo(usuario);
            assertThat(savedToken.isExpired()).isFalse();

            ArgumentCaptor<PasswordResetMessage> messageCaptor = ArgumentCaptor.forClass(PasswordResetMessage.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(RabbitMQConfig.EXCHANGER_NAME),
                    eq(RabbitMQConfig.ROUTING_KEY),
                    messageCaptor.capture()
            );

            PasswordResetMessage message = messageCaptor.getValue();
            assertThat(message.getVersion()).isEqualTo(1);
            assertThat(message.getEmail()).isEqualTo("usuario@petshop.com");
            assertThat(message.getNomeUsuario()).isEqualTo("Usuario Teste");
            assertThat(message.getToken()).isEqualTo(savedToken.getToken());
            assertThat(message.getEventId()).isNotBlank();
        }

        @Test
        @DisplayName("Deve atualizar token existente e enviar mensagem quando usuário já possuía token")
        void deveAtualizarTokenExistenteEEnviarMensagem() {
            // Given
            ForgotPasswordRequest request = new ForgotPasswordRequest();
            request.setEmail("usuario@petshop.com");

            Usuario usuario = new Usuario("Usuario Teste", "usuario@petshop.com", "hash");
            PasswordResetToken tokenExistente = new PasswordResetToken("token-antigo", usuario);

            when(usuarioRepository.findByEmail("usuario@petshop.com")).thenReturn(Optional.of(usuario));
            when(tokenRepository.findByUsuario(usuario)).thenReturn(Optional.of(tokenExistente));

            // When
            authService.forgotPassword(request);

            // Then
            verify(tokenRepository).save(tokenExistente);
            assertThat(tokenExistente.getToken()).isNotEqualTo("token-antigo");

            ArgumentCaptor<PasswordResetMessage> messageCaptor = ArgumentCaptor.forClass(PasswordResetMessage.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(RabbitMQConfig.EXCHANGER_NAME),
                    eq(RabbitMQConfig.ROUTING_KEY),
                    messageCaptor.capture()
            );
            assertThat(messageCaptor.getValue().getToken()).isEqualTo(tokenExistente.getToken());
        }

        @Test
        @DisplayName("Deve lançar ResourceNotFoundException quando email não for encontrado")
        void deveLancarResourceNotFoundExceptionQuandoEmailNaoEncontrado() {
            // Given
            ForgotPasswordRequest request = new ForgotPasswordRequest();
            request.setEmail("inexistente@petshop.com");

            when(usuarioRepository.findByEmail("inexistente@petshop.com")).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> authService.forgotPassword(request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Email não encontrado");

            verifyNoInteractions(tokenRepository);
            verifyNoInteractions(rabbitTemplate);
        }

        @Test
        @DisplayName("Deve lançar BusinessException quando request ou email forem nulos")
        void deveLancarBusinessExceptionQuandoRequestOuEmailNulo() {
            assertThatThrownBy(() -> authService.forgotPassword(null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Email obrigatório");

            ForgotPasswordRequest reqSemEmail = new ForgotPasswordRequest();
            assertThatThrownBy(() -> authService.forgotPassword(reqSemEmail))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Email obrigatório");
        }
    }

    @Nested
    @DisplayName("resetPassword(ResetPasswordRequest)")
    class ResetPasswordTests {

        @Test
        @DisplayName("Deve resetar a senha com sucesso quando o token for válido e senhas conferirem")
        void deveResetarSenhaComSucesso() {
            // Given
            ResetPasswordRequest request = new ResetPasswordRequest();
            request.setToken("valid-token-123");
            request.setNewPassword("NovaSenha@123");
            request.setConfirmPassword("NovaSenha@123");

            Usuario usuario = new Usuario("Usuario Teste", "usuario@petshop.com", "senha-antiga-hash");
            PasswordResetToken resetToken = new PasswordResetToken("valid-token-123", usuario);

            when(tokenRepository.findByToken("valid-token-123")).thenReturn(Optional.of(resetToken));
            when(passwordEncoder.encode("NovaSenha@123")).thenReturn("$2a$12$novaSenhaHash");

            // When
            authService.resetPassword(request);

            // Then
            assertThat(usuario.getSenha()).isEqualTo("$2a$12$novaSenhaHash");
            verify(usuarioRepository).save(usuario);
            verify(tokenRepository).delete(resetToken);
        }

        @Test
        @DisplayName("Deve lançar ResourceNotFoundException quando token não existir")
        void deveLancarResourceNotFoundExceptionQuandoTokenNaoExiste() {
            // Given
            ResetPasswordRequest request = new ResetPasswordRequest();
            request.setToken("token-inexistente");
            request.setNewPassword("NovaSenha@123");
            request.setConfirmPassword("NovaSenha@123");

            when(tokenRepository.findByToken("token-inexistente")).thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> authService.resetPassword(request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Token inválido");

            verify(usuarioRepository, never()).save(any());
            verify(tokenRepository, never()).delete(any());
        }

        @Test
        @DisplayName("Deve lançar BusinessException quando senhas não conferem sem deletar token")
        void deveLancarBusinessExceptionQuandoSenhasNaoConferem() {
            // Given
            ResetPasswordRequest request = new ResetPasswordRequest();
            request.setToken("valid-token-123");
            request.setNewPassword("NovaSenha@123");
            request.setConfirmPassword("OutraSenha@456");

            Usuario usuario = new Usuario("Usuario Teste", "usuario@petshop.com", "hash");
            PasswordResetToken resetToken = new PasswordResetToken("valid-token-123", usuario);

            when(tokenRepository.findByToken("valid-token-123")).thenReturn(Optional.of(resetToken));

            // When / Then
            assertThatThrownBy(() -> authService.resetPassword(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("As senhas não conferem.");

            verify(usuarioRepository, never()).save(any());
            verify(tokenRepository, never()).delete(any());
            verifyNoInteractions(passwordEncoder);
        }

        @Test
        @DisplayName("Deve deletar token e lançar BusinessException quando token estiver expirado")
        void deveDeletarTokenELancarBusinessExceptionQuandoTokenExpirado() {
            // Given
            ResetPasswordRequest request = new ResetPasswordRequest();
            request.setToken("expired-token");
            request.setNewPassword("NovaSenha@123");
            request.setConfirmPassword("NovaSenha@123");

            Usuario usuario = new Usuario("Usuario Teste", "usuario@petshop.com", "hash");
            PasswordResetToken resetToken = new PasswordResetToken("expired-token", usuario);
            // Simular data de expiração no passado
            ReflectionTestUtils.setField(resetToken, "expiryDate", LocalDateTime.now().minusMinutes(5));

            when(tokenRepository.findByToken("expired-token")).thenReturn(Optional.of(resetToken));

            // When / Then
            assertThatThrownBy(() -> authService.resetPassword(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Token expirado");

            verify(tokenRepository).delete(resetToken);
            verify(usuarioRepository, never()).save(any());
            verifyNoInteractions(passwordEncoder);
        }

        @Test
        @DisplayName("Deve lançar BusinessException quando request ou token forem nulos")
        void deveLancarBusinessExceptionQuandoRequestOuTokenNulo() {
            assertThatThrownBy(() -> authService.resetPassword(null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Dados de redefinição de senha inválidos");

            ResetPasswordRequest reqSemToken = new ResetPasswordRequest();
            assertThatThrownBy(() -> authService.resetPassword(reqSemToken))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Dados de redefinição de senha inválidos");
        }

        @Test
        @DisplayName("Deve lançar BusinessException quando newPassword ou confirmPassword forem nulos sem lançar NPE")
        void deveLancarBusinessExceptionQuandoSenhaForNulaSemLancarNpe() {
            ResetPasswordRequest request = new ResetPasswordRequest();
            request.setToken("valid-token");
            request.setNewPassword(null);
            request.setConfirmPassword("Confirmar@123");

            Usuario usuario = new Usuario("Usuario Teste", "usuario@petshop.com", "hash");
            PasswordResetToken resetToken = new PasswordResetToken("valid-token", usuario);
            when(tokenRepository.findByToken("valid-token")).thenReturn(Optional.of(resetToken));

            assertThatThrownBy(() -> authService.resetPassword(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("As senhas não conferem.");
        }
    }
}
