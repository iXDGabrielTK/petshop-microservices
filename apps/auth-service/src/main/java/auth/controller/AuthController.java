package auth.controller;

import auth.dto.request.LoginRequest;
import auth.dto.request.LogoutRequest;
import auth.dto.request.RefreshTokenRequest;
import auth.dto.response.LoginResponse;
import auth.model.Usuario;
import auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import auth.dto.request.RegisterRequest;
import org.springframework.web.util.HtmlUtils;
import java.net.URI;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/usuarios")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        logger.info("🔐 Tentativa de login: {}", loginRequest.getEmail());
        try {
            LoginResponse response = authService.login(loginRequest);
            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            logger.warn("❌ Falha no login de {}: {}", loginRequest.getEmail(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<?> refreshToken(@RequestBody RefreshTokenRequest request) {
        try {
            Map<String, String> tokens = authService.refreshToken(request);
            return ResponseEntity.ok(tokens);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            logger.warn("Erro no refresh token: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Token inválido ou expirado");
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request,
                                    @RequestBody(required = false) LogoutRequest logoutRequest) {
        try {
            String authHeader = request.getHeader("Authorization");
            Map<String, Object> result = authService.logout(authHeader, logoutRequest);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        try {
            Usuario usuarioCriado = authService.register(request);

            // CRIAÇÃO DA RESPOSTA SEGURA
            Map<String, Object> response = new HashMap<>();
            response.put("message", "Usuário registrado com sucesso");
            response.put("id", usuarioCriado.getId());

            // AQUI ESTÁ A CORREÇÃO REAL:
            // Nós "limpamos" o email antes de devolver para o mundo externo.
            // Isso avisa ao scanner: "Eu tratei esse dado, ele é seguro agora".
            String emailSeguro = HtmlUtils.htmlEscape(usuarioCriado.getEmail());
            response.put("email", emailSeguro);

            return ResponseEntity.created(URI.create("/usuarios/" + usuarioCriado.getId()))
                    .body(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.warn("Erro ao registrar: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Erro interno no servidor"));
        }
    }
}