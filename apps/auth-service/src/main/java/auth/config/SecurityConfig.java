package auth.config;

import auth.security.filter.CookieRefreshTokenRequestFilter;
import auth.security.filter.RefreshTokenCookieFilter;
import auth.security.user.UserDetailsImpl;
import auth.security.user.UserDetailsImplMixin;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import common.security.RsaKeyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
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

    @Value("${frontend.base-url}")
    private String frontendBaseUrl;

    @Value("${cors.allowed-methods}")
    private String corsAllowedMethods;

    @Value("${cors.allowed-headers}")
    private String corsAllowedHeaders;

    @Value("${cors.allow-credentials}")
    private Boolean corsAllowCredentials;

    private final CookieRefreshTokenRequestFilter cookieRefreshTokenRequestFilter;
    private final RefreshTokenCookieFilter refreshTokenCookieFilter;

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    public SecurityConfig(CookieRefreshTokenRequestFilter cookieRefreshTokenRequestFilter, RefreshTokenCookieFilter refreshTokenCookieFilter) {
        this.cookieRefreshTokenRequestFilter = cookieRefreshTokenRequestFilter;
        this.refreshTokenCookieFilter = refreshTokenCookieFilter;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {

        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                new OAuth2AuthorizationServerConfigurer();

        http
                .securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .with(authorizationServerConfigurer, authorizationServer ->
                        authorizationServer
                                .oidc(Customizer.withDefaults())
                );

        http.with(authorizationServerConfigurer, authorizationServer ->
                authorizationServer.oidc(Customizer.withDefaults())
        );

        // --- FILTROS CORRETAMENTE POSICIONADOS ---

        // 1. INPUT: Converte Cookie -> Parâmetro
        // Roda ANTES do SecurityContextHolderFilter para normalizar a request cedo.
        http.addFilterBefore(cookieRefreshTokenRequestFilter, SecurityContextHolderFilter.class);

        // 2. OUTPUT: Envelopa a Resposta (Wrapper)
        // Roda DEPOIS do SecurityContextHolderFilter.
        // Como o SecurityContext já rodou, a ordem fica garantida:
        // [CookieFilter] -> [ContextFilter] -> [ResponseFilter] -> ... -> [TokenEndpoint]
        http.addFilterAfter(refreshTokenCookieFilter, SecurityContextHolderFilter.class);

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
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers(
                                "/usuarios/register",
                                "/oauth2/**",
                                "/api/**",
                                "/actuator/**"
                        )
                )

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
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        config.addAllowedOrigin(frontendBaseUrl);
        config.setAllowedMethods(Arrays.asList(corsAllowedMethods.split(",")));
        config.setAllowedHeaders(Arrays.asList(corsAllowedHeaders.split(",")));
        config.setAllowCredentials(corsAllowCredentials);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

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

            RSAPublicKey publicKey = RsaKeyUtils.parsePublicKey(publicKeyString);
            RSAPrivateKey privateKey = RsaKeyUtils.parsePrivateKey(privateKeyString);

            rsaKey = new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID("auth-key-id")
                    .build();

        } else {
            log.warn("Usando chaves RSA geradas em memória");

            KeyPair keyPair = RsaKeyUtils.generateKeyPair();

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
                response.sendRedirect(frontendBaseUrl);
                return;
            }

            String targetUrl = savedRequest.getRedirectUrl();
            requestCache.removeRequest(request, response);
            response.sendRedirect(targetUrl);
        };
    }

}