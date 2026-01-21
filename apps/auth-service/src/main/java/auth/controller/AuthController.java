package auth.controller;

import auth.dto.request.*;
import auth.dto.response.*;
import auth.model.Usuario;
import auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;

@RestController
@RequestMapping("/usuarios")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
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