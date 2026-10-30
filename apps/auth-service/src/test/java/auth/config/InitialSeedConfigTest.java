package auth.config;

import auth.model.Role;
import auth.model.Usuario;
import auth.repository.RoleRepository;
import auth.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InitialSeedConfig — Testes Unitários de Inicialização do Banco e Clientes OAuth2")
class InitialSeedConfigTest {

    @Mock
    private RegisteredClientRepository clientRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private InitialSeedConfig seedConfig;

    @BeforeEach
    void setUp() {
        seedConfig = new InitialSeedConfig();
        ReflectionTestUtils.setField(seedConfig, "initialAdminEmail", "admin@petshop.com");
        ReflectionTestUtils.setField(seedConfig, "initialAdminPassword", "AdminPassword@123");
    }

    @Test
    @DisplayName("Deve semear cliente OAuth2 e usuário Admin quando base estiver vazia")
    void deveSemearClienteEAdminQuandoNaoExistirem() throws Exception {
        // Given
        when(clientRepository.findByClientId("petshop-client")).thenReturn(null);
        when(usuarioRepository.findByEmail("admin@petshop.com")).thenReturn(Optional.empty());
        when(roleRepository.findByNome("ADMIN")).thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(i -> i.getArgument(0));
        when(passwordEncoder.encode("AdminPassword@123")).thenReturn("$2a$12$adminEncoded");

        CommandLineRunner runner = seedConfig.run(clientRepository, usuarioRepository, roleRepository, passwordEncoder);

        // When
        runner.run();

        // Then
        ArgumentCaptor<RegisteredClient> clientCaptor = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(clientRepository).save(clientCaptor.capture());
        RegisteredClient client = clientCaptor.getValue();
        assertThat(client.getClientId()).isEqualTo("petshop-client");
        assertThat(client.getScopes()).contains("openid", "profile", "pets:read", "pets:write");

        ArgumentCaptor<Usuario> userCaptor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).save(userCaptor.capture());
        Usuario admin = userCaptor.getValue();
        assertThat(admin.getEmail()).isEqualTo("admin@petshop.com");
        assertThat(admin.getNome()).isEqualTo("Administrador");
        assertThat(admin.getSenha()).isEqualTo("$2a$12$adminEncoded");
        assertThat(admin.getRoles()).extracting(Role::getNome).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("Não deve recriar cliente nem admin quando já existirem")
    void naoDeveRecriarQuandoJaExistirem() throws Exception {
        // Given
        RegisteredClient existingClient = mock(RegisteredClient.class);
        Usuario existingAdmin = new Usuario("Admin", "admin@petshop.com", "hash");

        when(clientRepository.findByClientId("petshop-client")).thenReturn(existingClient);
        when(usuarioRepository.findByEmail("admin@petshop.com")).thenReturn(Optional.of(existingAdmin));

        CommandLineRunner runner = seedConfig.run(clientRepository, usuarioRepository, roleRepository, passwordEncoder);

        // When
        runner.run();

        // Then
        verify(clientRepository, never()).save(any());
        verify(usuarioRepository, never()).save(any());
        verifyNoInteractions(roleRepository);
        verifyNoInteractions(passwordEncoder);
    }
}
