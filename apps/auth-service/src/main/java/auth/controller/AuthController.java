package auth.controller;

import auth.dto.request.*;
import auth.dto.response.*;
import auth.model.Usuario;
import auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;
import java.net.URI;

import java.util.Map;

@RestController
@RequestMapping("/usuarios")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Processa a tentativa de login.
     *<p>
     * Recebe um LoginRequest validado, delega a autenticação para AuthService e
     * retorna um LoginResponse com os tokens em caso de sucesso.
     *<p>
     * Em caso de falha retorna 401 (Unauthorized). Atenção: não registrar senhas
     * em logs — apenas o email é registrado para auditoria.
     *
     * @param loginRequest dados de autenticação (email e senha)
     * @return ResponseEntity contendo LoginResponse em sucesso ou status apropriado em erro
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        logger.info("🔐 Tentativa de login: {}", loginRequest.getEmail());
        LoginResponse response = authService.login(loginRequest);

        return ResponseEntity.ok(response);
    }

    /**
     * Atualiza tokens usando um refresh token.
     *<p>
     * Recebe um RefreshTokenRequest (normalmente contendo o refresh token) e delega
     * a validação/geração de novos tokens para AuthService.
     *<p>
     * Respostas:
     * - 200 OK com um mapa contendo os novos tokens em caso de sucesso.
     * - 400 Bad Request quando os parâmetros são inválidos (p.ex. token ausente ou mal formado).
     * - 401 Unauthorized quando o refresh token for inválido ou expirado.
     *<p>
     * Observações de segurança:
     * - Não registar o conteúdo do token em logs.
     * - Tratar mensagens de erro de forma genérica para não vazar informação de segurança.
     *
     * @param request dados para refresh de token
     * @return ResponseEntity com tokens ou mensagem de erro apropriada
     */
    @PostMapping("/refresh-token")
    public ResponseEntity<TokenResponse> refreshToken(@RequestBody RefreshTokenRequest request) {
        Map<String, String> tokens = authService.refreshToken(request);

        TokenResponse response = new TokenResponse(
                tokens.get("access_token"),
                tokens.get("refresh_token")
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Efetua o logout do usuário.
     *<p>
     * A função tenta extrair o Authorization header (se presente) e também aceita um
     * corpo opcional (`LogoutRequest`) para suportar diferentes mecanismos de logout.
     *<p>
     * Respostas:
     * - 200 OK com um mapa contendo informações sobre o logout (p.ex. confirmação, tokens revogados).
     * - 500 Internal Server Error em caso de erro inesperado no servidor.
     *<p>
     * Observações:
     * - Não registrar tokens completos em logs. Se necessário, registrar apenas indicadores (ex.: hash ou parte).
     * - O header Authorization pode ser nulo quando o logout for acionado via corpo (p.ex. revogação por ID).
     *
     * @param request HttpServletRequest para acessar headers (Authorization)
     * @param logoutRequest corpo opcional com dados de logout
     * @return ResponseEntity com resultado do logout ou status de erro
     */
    @PostMapping("/logout")
    public ResponseEntity<LogoutResponse> logout(HttpServletRequest request,
                                    @RequestBody(required = false) LogoutRequest logoutRequest) {
        String authHeader = request.getHeader("Authorization");

        Map<String, Object> result = authService.logout(authHeader, logoutRequest);

        LogoutResponse response = new LogoutResponse(
                (String) result.get("message"),
                (Boolean) result.get("accessTokenRevoked"),
                (Boolean) result.get("refreshTokenRevoked")
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Registra um novo usuário.
     *<p>
     * Recebe um RegisterRequest validado, delega a criação do usuário para AuthService
     * e devolve 201 Created com um corpo contendo mensagem, id e email sanitizado.
     *<p>
     * Respostas:
     * - 201 Created quando o usuário é criado com sucesso. Localização retornada em `Location`.
     * - 400 Bad Request quando dados de entrada são inválidos (ex.: email já usado).
     * - 500 Internal Server Error para erros inesperados.
     *<p>
     * Observações de segurança:
     * - Sanitizar dados sensíveis antes de retorná-los (ex.: HtmlUtils.htmlEscape para email).
     * - Não incluir informações sensíveis como senha no payload de resposta.
     *
     * @param request dados para cadastro do usuário
     * @return ResponseEntity contendo informação do novo usuário ou erro apropriado
     */
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        Usuario usuarioCriado = authService.register(request);

        String emailSeguro = HtmlUtils.htmlEscape(usuarioCriado.getEmail());

        RegisterResponse response = new RegisterResponse(
                "Usuário registrado com sucesso",
                usuarioCriado.getId(),
                emailSeguro
        );

        return ResponseEntity
                .created(URI.create("/usuarios/" + usuarioCriado.getId()))
                .body(response);
    }

    /**
     * Inicia o processo de recuperação de senha.
     *<p>
     * Recebe um ForgotPasswordRequest contendo o email do usuário e delega
     * a geração do token e envio do email para AuthService.
     *<p>
     * Respostas:
     * - 200 OK com mensagem genérica para evitar vazamento de informação sobre existência do email.
     * - 400 Bad Request em caso de erro (ex.: formato inválido).
     *<p>
     * Observações de segurança:
     * - Nunca revelar se o email existe ou não no sistema na resposta.
     * - Tratar erros de forma genérica para não vazar informações sensíveis.
     *
     * @param request dados para recuperação de senha
     * @return ResponseEntity com mensagem genérica ou erro apropriado
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<GenericResponse> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(new GenericResponse("Se o email existir, um link de recuperação foi enviado."));
    }

    /**
     * Reseta a senha do usuário usando um token de recuperação.
     *<p>
     * Recebe um ResetPasswordRequest contendo o token e a nova senha,
     * delega a validação e atualização da senha para AuthService.
     *<p>
     * Respostas:
     * - 200 OK com mensagem de sucesso quando a senha é alterada.
     * - 400 Bad Request em caso de erro (ex.: token inválido ou expirado).
     *<p>
     * Observações de segurança:
     * - Tratar erros de forma genérica para não vazar informações sensíveis.
     *
     * @param request dados para resetar a senha
     * @return ResponseEntity com mensagem de sucesso ou erro apropriado
     */
    @PostMapping("/reset-password")
    public ResponseEntity<GenericResponse> resetPassword(@RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(new GenericResponse("Senha alterada com sucesso!"));
    }
}