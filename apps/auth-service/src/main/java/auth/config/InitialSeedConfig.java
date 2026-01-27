package auth.config;

import auth.model.Role;
import auth.model.Usuario;
import auth.repository.RoleRepository;
import auth.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

@Configuration
public class InitialSeedConfig {

    @Value("${initial.client.secret}")
    private String initialClientSecret;

    @Value("${initial.admin.password}") // Valor padrão apenas para DEV
    private String initialAdminPassword;

    @Bean
    public CommandLineRunner run(RegisteredClientRepository clientRepository,
                                 UsuarioRepository usuarioRepository,
                                 RoleRepository roleRepository,
                                 PasswordEncoder passwordEncoder) {
        return args -> {

            if (clientRepository.findByClientId("petshop-client") == null) {
                RegisteredClient oidcClient = RegisteredClient.withId(UUID.randomUUID().toString())
                        .clientId("petshop-client")
                        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)

                        .redirectUri("https://oauth.pstmn.io/v1/callback")
                        .redirectUri("http://localhost:5173/authorized")

                        .scope(OidcScopes.OPENID)
                        .scope(OidcScopes.PROFILE)
                        .scope("pets:read")
                        .scope("pets:write")

                        .tokenSettings(TokenSettings.builder()
                                .accessTokenTimeToLive(Duration.ofMinutes(30))
                                .refreshTokenTimeToLive(Duration.ofDays(1))
                                .reuseRefreshTokens(false)
                                .build())

                        .clientSettings(ClientSettings.builder()
                                .requireProofKey(true)
                                .requireAuthorizationConsent(false)
                                .build())
                        .build();

                clientRepository.save(oidcClient);
                System.out.println("✅ Cliente OAuth2 'petshop-client' Público criado.");
            }

            Usuario admin = usuarioRepository.findByEmail(initialClientSecret)
                    .orElse(new Usuario());

            Role roleAdmin = roleRepository.findByNome("ADMIN")
                    .orElseGet(() -> roleRepository.save(new Role(null, "ADMIN")));

            admin.setNome("Administrador");
            admin.setEmail(initialClientSecret);
            if (admin.getId() == null) {
                admin.setSenha(passwordEncoder.encode(initialAdminPassword));
            }
            admin.setRoles(Collections.singleton(roleAdmin));

            usuarioRepository.save(admin);
            System.out.println("✅ Usuário Admin garantido no banco.");
        };
    }
}