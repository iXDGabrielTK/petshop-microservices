package auth.config;

import auth.model.Role;
import auth.model.Usuario;
import auth.repository.RoleRepository;
import auth.repository.UsuarioRepository;
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

    @Bean
    public CommandLineRunner run(RegisteredClientRepository clientRepository,
                                 UsuarioRepository usuarioRepository,
                                 RoleRepository roleRepository,
                                 PasswordEncoder passwordEncoder) {
        return args -> {

            if (clientRepository.findByClientId("petshop-client") == null) {
                RegisteredClient oidcClient = RegisteredClient.withId(UUID.randomUUID().toString())
                        .clientId("petshop-client")
                        .clientSecret(passwordEncoder.encode("secret123"))
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)

                        .redirectUri("https://oauth.pstmn.io/v1/callback")
                        .redirectUri("http://127.0.0.1:8080/authorized")

                        .scope(OidcScopes.OPENID)
                        .scope(OidcScopes.PROFILE)
                        .scope("pets:read")
                        .scope("pets:write")

                        .tokenSettings(TokenSettings.builder()
                                .accessTokenTimeToLive(Duration.ofMinutes(30))
                                .refreshTokenTimeToLive(Duration.ofDays(1))
                                .build())

                        .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false).build())
                        .build();

                clientRepository.save(oidcClient);
                System.out.println("✅ Cliente OAuth2 'petshop-client' criado.");
            }

            if (usuarioRepository.findByEmail("admin@petshop.com").isEmpty()) {
                Role roleAdmin = roleRepository.findByNome("ADMIN")
                        .orElseGet(() -> roleRepository.save(new Role(null, "ADMIN")));

                Usuario admin = new Usuario();
                admin.setNome("Administrador");
                admin.setEmail("admin@petshop.com");
                admin.setSenha(passwordEncoder.encode("admin123"));
                admin.setRoles(Collections.singleton(roleAdmin));

                usuarioRepository.save(admin);
                System.out.println("✅ Usuário 'admin@petshop.com' criado.");
            }
        };
    }
}