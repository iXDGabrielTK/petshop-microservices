package auth.controller;

import auth.dto.request.UserSettingsRequest;
import auth.dto.response.UserResponse;
import auth.model.Usuario;
import auth.security.user.UserDetailsImpl;
import auth.service.UsuarioService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

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

    /**
     * Atualiza as configurações do usuário autenticado.
     * Endpoint usado pelo Frontend para salvar preferências do usuário.
     * Suporta autenticação tanto via JWT (Resource Server) quanto via Session (UserDetailsImpl).
     */
    @PatchMapping("/settings")
    public ResponseEntity<Void> updateSettings(
            @Valid @RequestBody UserSettingsRequest request,
            @AuthenticationPrincipal Object principal
    ) {
        if (principal instanceof Jwt jwt) {
            Object userIdClaim = jwt.getClaims().get("user_id");
            if (userIdClaim instanceof Number number) {
                usuarioService.updateSettings(number.longValue(), request);
            } else {
                usuarioService.updateSettingsByEmail(jwt.getClaimAsString("sub"), request);
            }
        } else if (principal instanceof UserDetailsImpl userDetails) {
            usuarioService.updateSettings(userDetails.getId(), request);
        } else if (principal instanceof Usuario usuario) {
            usuarioService.updateSettings(usuario.getId(), request);
        } else {
            throw new IllegalArgumentException("Principal de autenticação não suportado");
        }

        return ResponseEntity.noContent().build();
    }

    /**
     * Sobrecarga para compatibilidade retroativa com testes legados ou chamadores diretos.
     */
    public ResponseEntity<Void> updateSettings(UserSettingsRequest request, Usuario usuario) {
        return updateSettings(request, (Object) usuario);
    }

}