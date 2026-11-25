/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.config;

import auth.controller.LoginController;
import auth.model.Role;
import auth.model.Usuario;
import auth.security.LoginAttemptService;
import auth.security.filter.CookieRefreshTokenRequestFilter;
import auth.security.filter.RefreshTokenCookieFilter;
import auth.security.handler.CookieClearingLogoutHandler;
import auth.security.listener.LoginAttemptEventListener;
import auth.security.user.UserDetailsImpl;
import common.security.RsaKeyUtils;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(classes = LoginSecurityFilterTest.TestContextConfig.class)
@WebAppConfiguration
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("LoginSecurityFilterTest — CSP em GET /login, Ausência de CDN e Brute Force Lockout")
class LoginSecurityFilterTest {

    private static final String EXPECTED_CSP =
            "default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'";

    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({
            SecurityConfig.class,
            LoginAttemptService.class,
            LoginAttemptEventListener.class,
            CookieRefreshTokenRequestFilter.class,
            RefreshTokenCookieFilter.class,
            CookieClearingLogoutHandler.class,
            LoginController.class
    })
    static class TestContextConfig {

        @Bean
        ClassLoaderTemplateResolver thymeleafTemplateResolver() {
            ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
            resolver.setPrefix("templates/");
            resolver.setSuffix(".html");
            resolver.setTemplateMode("HTML");
            resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
            return resolver;
        }

        @Bean
        SpringTemplateEngine thymeleafTemplateEngine(ClassLoaderTemplateResolver templateResolver) {
            SpringTemplateEngine engine = new SpringTemplateEngine();
            engine.setTemplateResolver(templateResolver);
            return engine;
        }

        @Bean
        ViewResolver thymeleafViewResolver(SpringTemplateEngine templateEngine) {
            ThymeleafViewResolver resolver = new ThymeleafViewResolver();
            resolver.setTemplateEngine(templateEngine);
            resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
            return resolver;
        }
    }

    private static final KeyPair KEY_PAIR = RsaKeyUtils.generateKeyPair();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String pubKey = Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded());
        String privKey = Base64.getEncoder().encodeToString(KEY_PAIR.getPrivate().getEncoded());
        registry.add("jwt.public.key", () -> pubKey);
        registry.add("jwt.private.key", () -> privKey);
        registry.add("frontend.base-url", () -> "http://localhost:5173");
        registry.add("cors.allowed-methods", () -> "GET,POST,PUT,DELETE,OPTIONS");
        registry.add("cors.allowed-headers", () -> "Authorization,Content-Type");
        registry.add("cors.allow-credentials", () -> "true");
        registry.add("auth.cookie.secure", () -> "true");
        registry.add("auth.cookie.same-site", () -> "Lax");
        registry.add("auth.login.lockout.max-attempts", () -> "5");
        registry.add("auth.login.lockout.duration-minutes", () -> "15");
        registry.add("auth.login.lockout.max-entries", () -> "100");
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private OAuth2AuthorizationService authorizationService;

    private MockMvc mockMvc;

    @BeforeAll
    void setUpOnce() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter((Filter) context.getBean("springSecurityFilterChain"))
                .build();
    }

    @BeforeEach
    void resetState() {
        Mockito.reset(userDetailsService);
        loginAttemptService.recordSuccess("cliente@petshop.com", "10.10.10.1");
        loginAttemptService.recordSuccess("cliente@petshop.com", "10.10.10.2");
        loginAttemptService.recordSuccess("inexistente@petshop.com", "10.10.10.3");
    }

    @Test
    @DisplayName("GET /login deve retornar CSP restritivo, usar CSS local e não conter CDN ou scripts inline")
    void getLoginDeveAplicarCspEServirCssLocalSemCdn() throws Exception {
        MvcResult result = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", EXPECTED_CSP))
                .andReturn();

        String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).doesNotContain("cdn.tailwindcss.com");
        assertThat(html).doesNotContain("<script");
        assertThat(html).contains("/css/login.css");

        ClassPathResource cssResource = new ClassPathResource("static/css/login.css");
        assertThat(cssResource.exists()).isTrue();
        assertThat(cssResource.contentLength()).isGreaterThan(0);
    }

    @Test
    @DisplayName("POST /login deve bloquear após 5 falhas antes de consultar UserDetailsService mesmo com senha correta")
    void deveBloquearLoginAposCincoFalhas() throws Exception {
        Usuario usuario = new Usuario("Cliente", "cliente@petshop.com", passwordEncoder.encode("SenhaForte@123"));
        usuario.adicionarRole(new Role(1L, "USER"));
        when(userDetailsService.loadUserByUsername("cliente@petshop.com"))
                .thenReturn(new UserDetailsImpl(usuario, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/login")
                            .with(csrf())
                            .with(req -> {
                                req.setRemoteAddr("10.10.10.1");
                                return req;
                            })
                            .param("username", "cliente@petshop.com")
                            .param("password", "SenhaErrada"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?error"));
        }

        // 6a tentativa com a senha CORRETA deve ser bloqueada no filtro antes do UserDetailsService
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .with(req -> {
                            req.setRemoteAddr("10.10.10.1");
                            return req;
                        })
                        .param("username", "cliente@petshop.com")
                        .param("password", "SenhaForte@123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));

        verify(userDetailsService, times(5)).loadUserByUsername("cliente@petshop.com");

        // Outro IP não deve ser afetado pelo bloqueio do primeiro IP
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .with(req -> {
                            req.setRemoteAddr("10.10.10.2");
                            return req;
                        })
                        .param("username", "cliente@petshop.com")
                        .param("password", "SenhaForte@123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:5173"));
    }

    @Test
    @DisplayName("POST /login deve responder da mesma forma para e-mail inexistente sem vazar existência da conta")
    void deveBloquearEmailInexistenteSemVazarExistencia() throws Exception {
        when(userDetailsService.loadUserByUsername(anyString()))
                .thenThrow(new UsernameNotFoundException("não encontrado"));

        for (int i = 0; i < 6; i++) {
            mockMvc.perform(post("/login")
                            .with(csrf())
                            .with(req -> {
                                req.setRemoteAddr("10.10.10.3");
                                return req;
                            })
                            .param("username", "inexistente@petshop.com")
                            .param("password", "QualquerSenha"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?error"));
        }

        verify(userDetailsService, times(5)).loadUserByUsername("inexistente@petshop.com");
    }
}
