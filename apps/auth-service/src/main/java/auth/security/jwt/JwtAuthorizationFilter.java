package auth.security.jwt;

import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class JwtAuthorizationFilter extends OncePerRequestFilter {

    private final JwtTokenValidator tokenValidator;
    private final TokenBlacklistService blacklistService;

    public JwtAuthorizationFilter(JwtTokenValidator tokenValidator,
                                  TokenBlacklistService blacklistService) {
        this.tokenValidator = tokenValidator;
        this.blacklistService = blacklistService;
    }

    /**
     * Filtra requisições para autorizar usuários via JWT.
     *<p>
     * Fluxo:
     * 1. Lê o header "Authorization" e valida o formato "Bearer &lt;token&gt;".
     * 2. Verifica se o token está na blacklist (revogado) — responde 401 se estiver.
     * 3. Valida o token e a "fingerprint" associada ao token — responde 401 em falha.
     * 4. Extrai username e roles do token, converte roles para GrantedAuthority e popula
     *    o SecurityContext com uma UsernamePasswordAuthenticationToken.
     * 5. Continua a cadeia de filtros em caso de sucesso.
     *<p>
     * Respostas HTTP:
     * - 401 Unauthorized quando token ausente/invalidado/revogado/fingerprint inválida.
     * - Para token expirado responde 401 com JSON {"message": "Token expired"}.
     *<p>
     * Observações de segurança:
     * - Não logar o conteúdo do token nem a senha.
     * - Tratar mensagens de erro de forma genérica para evitar vazamento de informações.
     *
     * @param request  requisição HTTP que pode conter o header Authorization
     * @param response resposta HTTP usada para enviar erros quando necessário
     * @param filterChain cadeia de filtros a ser continuada em caso de autorização bem-sucedida
     * @throws ServletException em caso de erro de servlet durante o filtro
     * @throws IOException em caso de erro de I/O durante o filtro
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);

        if (blacklistService.isBlacklisted(token)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token revoked");
            return;
        }

        try {
            tokenValidator.validateTokenOrThrow(token);

            if (!tokenValidator.validateFingerprint(token)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid fingerprint");
                return;
            }

            String username = tokenValidator.extractUsername(token);
            List<String> roles = tokenValidator.extractRoles(token);

            var authorities = roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role.replace("ROLE_", "")))
                    .collect(Collectors.toList());

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);

            SecurityContextHolder.getContext().setAuthentication(authentication);

            filterChain.doFilter(request, response);

        } catch (ExpiredJwtException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"message\": \"Token expired\"}");
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }
}