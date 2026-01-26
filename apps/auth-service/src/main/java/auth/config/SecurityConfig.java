package auth.config;

import auth.security.user.UserDetailsImpl;
import auth.security.user.UserDetailsImplMixin;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${jwt.public.key:}")
    private String publicKeyString;

    @Value("${jwt.private.key:}")
    private String privateKeyString;

    // --- 1. CONFIGURAÇÃO MODERNA DO AUTH SERVER (SEM DEPRECATED) ---
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {

        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                new OAuth2AuthorizationServerConfigurer();

        http
                .securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .with(authorizationServerConfigurer, authorizationServer ->
                        authorizationServer
                                .oidc(Customizer.withDefaults())
                );

        http
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
                        )
                )
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));

        return http.build();
    }

    // --- 2. FILTRO PADRÃO (LOGIN, PÁGINAS PÚBLICAS) ---
    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests((authorize) -> authorize
                        .requestMatchers(
                                "/assets/**", "/webjars/**", "/images/**", "/css/**", "/js/**", "/favicon.ico",
                                "/*.png", "/*.gif", "/*.svg", "/*.jpg", "/*.html", "/*.css", "/*.js"
                        ).permitAll()
                        .requestMatchers("/actuator/**", "/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/usuarios/register", "/usuarios/forgot-password", "/error").permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .permitAll()
                        .successHandler(customAuthenticationSuccessHandler())
                );

        return http.build();
    }

    // --- BEANS ESSENCIAIS ---

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().build();
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
    }

    @Bean
    public OAuth2AuthorizationService authorizationService(JdbcTemplate jdbcTemplate, RegisteredClientRepository registeredClientRepository) {
        JdbcOAuth2AuthorizationService service = new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
        JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper rowMapper =
                new JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper(registeredClientRepository);

        ObjectMapper objectMapper = new ObjectMapper();
        ClassLoader classLoader = JdbcOAuth2AuthorizationService.class.getClassLoader();
        List<Module> modules = SecurityJackson2Modules.getModules(classLoader);
        objectMapper.registerModules(modules);

        // Mantendo seu Mixin para serialização funcionar
        objectMapper.addMixIn(UserDetailsImpl.class, UserDetailsImplMixin.class);

        rowMapper.setObjectMapper(objectMapper);
        service.setAuthorizationRowMapper(rowMapper);
        return service;
    }

    @Bean
    public OAuth2AuthorizationConsentService authorizationConsentService(JdbcTemplate jdbcTemplate, RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
        return context -> {

            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {

                Authentication authentication = context.getPrincipal();

                Set<String> authorities = authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toSet());

                context.getClaims().claim("roles", authorities);

                Object principal = authentication.getPrincipal();

                if (principal instanceof UserDetailsImpl userDetails) {
                    context.getClaims().claim("name", userDetails.getNome());
                    context.getClaims().claim("user_id", userDetails.getId());
                    context.getClaims().claim("email", userDetails.getUsername());
                }
            }
        };
    }

    // --- CRIPTOGRAFIA ---

    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        RSAKey rsaKey;
        if (publicKeyString != null && !publicKeyString.isBlank() &&
                privateKeyString != null && !privateKeyString.isBlank()) {
            try {
                RSAPublicKey publicKey = parsePublicKey(publicKeyString);
                RSAPrivateKey privateKey = parsePrivateKey(privateKeyString);
                rsaKey = new RSAKey.Builder(publicKey)
                        .privateKey(privateKey)
                        .keyID("auth-key-id")
                        .build();
            } catch (Exception e) {
                throw new IllegalStateException("Erro ao carregar chaves RSA", e);
            }
        } else {
            System.out.println("⚠️ AVISO: Usando chaves RSA geradas em memória");
            KeyPair keyPair = generateRsaKey();
            RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
            RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
            rsaKey = new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(UUID.randomUUID().toString())
                    .build();
        }
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationSuccessHandler customAuthenticationSuccessHandler() {
        return (request, response, authentication) -> {
            RequestCache requestCache = new HttpSessionRequestCache();
            SavedRequest savedRequest = requestCache.getRequest(request, response);

            if (savedRequest == null) {
                response.sendRedirect("http://localhost:5173/dashboard");
                return;
            }

            // Redireciona para a URL original (agora correta, vinda do front)
            String targetUrl = savedRequest.getRedirectUrl();
            requestCache.removeRequest(request, response);
            response.sendRedirect(targetUrl);
        };
    }

    // --- HELPERS ---

    private static KeyPair generateRsaKey() {
        KeyPair keyPair;
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            keyPair = keyPairGenerator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return keyPair;
    }

    private RSAPublicKey parsePublicKey(String key) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(key);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(decoded));
    }

    private RSAPrivateKey parsePrivateKey(String key) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(key);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }
}