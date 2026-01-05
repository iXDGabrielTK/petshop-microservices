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

    // REMOVIDO: UserDetailsServiceImpl (Não precisamos mais ir no banco!)
    public JwtAuthorizationFilter(JwtTokenValidator tokenValidator,
                                  TokenBlacklistService blacklistService) {
        this.tokenValidator = tokenValidator;
        this.blacklistService = blacklistService;
    }

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

        // Verifica Blacklist (Logout)
        if (blacklistService.isBlacklisted(token)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token revoked");
            return;
        }

        try {
            // 1. Valida o token
            tokenValidator.validateTokenOrThrow(token);

            // 2. Valida Fingerprint (IP/UserAgent)
            if (!tokenValidator.validateFingerprint(token)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid fingerprint");
                return;
            }

            // 3. Extrai dados DIRETO DO TOKEN (Sem ir no banco)
            String username = tokenValidator.extractUsername(token);
            List<String> roles = tokenValidator.extractRoles(token);

            // Converte String "ADMIN" para Authority do Spring
            var authorities = roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role.replace("ROLE_", "")))
                    .collect(Collectors.toList());

            // 4. Autentica o usuário no contexto
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