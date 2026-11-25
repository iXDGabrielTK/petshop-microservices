/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
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
import java.util.Set;
import java.util.UUID;

@Configuration
public class InitialSeedConfig {

    @Value("${initial.admin.email}")
    private String initialAdminEmail;

    @Value("${initial.admin.password}")
    private String initialAdminPassword;

    @Value("${auth.oauth2.client.redirect-uris}")
    private Set<String> redirectUris;

    @Bean
    public CommandLineRunner run(RegisteredClientRepository clientRepository,
                                 UsuarioRepository usuarioRepository,
                                 RoleRepository roleRepository,
                                 PasswordEncoder passwordEncoder) {
        return args -> {

            RegisteredClient existing = clientRepository.findByClientId("petshop-client");
            if (existing == null) {
                RegisteredClient oidcClient = RegisteredClient.withId(UUID.randomUUID().toString())
                        .clientId("petshop-client")
                        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)

                        .redirectUris(uris -> uris.addAll(redirectUris))

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
            } else if (!existing.getRedirectUris().equals(redirectUris)) {
                clientRepository.save(RegisteredClient.from(existing).redirectUris(uris -> {
                    uris.clear();
                    uris.addAll(redirectUris);
                }).build());
            }

            if (usuarioRepository.findByEmail(initialAdminEmail).isEmpty()) {

                Role roleAdmin = roleRepository.findByNome("ADMIN")
                        .orElseGet(() -> roleRepository.save(new Role(null, "ADMIN")));

                Usuario admin = new Usuario(
                        "Administrador",
                        initialAdminEmail,
                        passwordEncoder.encode(initialAdminPassword)
                );

                admin.adicionarRole(roleAdmin);

                usuarioRepository.save(admin);
                System.out.println("✅ Usuário Admin criado com sucesso.");
            } else {
                System.out.println("ℹ️ Usuário Admin já existe. Pulando criação.");
            }
        };
    }
}
