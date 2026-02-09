package auth.controller;

import auth.dto.response.UserResponse;
import auth.service.UsuarioService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/usuarios")
public class UsuarioController {

    private final UsuarioService usuarioService;

    public UsuarioController(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    /**
     * Retorna os dados do usuário autenticado atual.
     * Endpoint usado pelo Frontend para recuperar sessão após F5.
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        String email = jwt.getClaimAsString("sub");

        UserResponse response = usuarioService.obterDadosUsuarioLogado(email);

        return ResponseEntity.ok(response);
    }
}