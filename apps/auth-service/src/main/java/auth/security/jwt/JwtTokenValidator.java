package auth.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.crypto.SecretKey;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class JwtTokenValidator {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenValidator.class);
    private final SecretKey secretKey;

    public JwtTokenValidator(SecretKey secretKey) {
        this.secretKey = secretKey;
    }

    public void validateTokenOrThrow(String token) {
        Claims claims = extractAllClaims(token);
        Date expiration = claims.getExpiration();
        if (expiration == null || expiration.before(new Date())) {
            throw new ExpiredJwtException(null, claims, "Token expirado");
        }
    }

    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    public long extractExpiration(String token) {
        return extractAllClaims(token).getExpiration().getTime();
    }

    /**
     * Extrai o claim "roles" como uma lista de strings.
     * Retorna lista vazia se o claim não existir ou estiver em formato inesperado.
     *
     * @param token token JWT
     * @return lista de roles
     */
    public List<String> extractRoles(String token) {
        Claims claims = extractAllClaims(token);
        Object roles = claims.get("roles");
        if (roles instanceof List<?>) {
            return ((List<?>) roles).stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    /**
     * Valida a fingerprint do token comparando ip e userAgent com a requisição atual.
     * Retorna true quando não há dados de fingerprint ou quando coincidem.
     *
     * @param token token JWT
     * @return true se a fingerprint for válida ou ausente
     */
    public boolean validateFingerprint(String token) {
        try {
            Claims claims = extractAllClaims(token);

            if (!claims.containsKey("ip") && !claims.containsKey("userAgent")) {
                return true;
            }

            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) return true;

            HttpServletRequest request = attributes.getRequest();

            if (claims.containsKey("ip")) {
                String tokenIp = claims.get("ip", String.class);
                String currentIp = getClientIp(request);
                if (!tokenIp.equals(currentIp)) {
                    logger.warn("IP mismatch: token={}, request={}", tokenIp, currentIp);
                    return false;
                }
            }

            if (claims.containsKey("userAgent")) {
                String tokenUserAgent = claims.get("userAgent", String.class);
                String currentUserAgent = request.getHeader("User-Agent");
                if (!Objects.equals(tokenUserAgent, currentUserAgent)) {
                    logger.warn("User-Agent mismatch");
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            logger.error("Fingerprint validation error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Verifica se o token é um refresh token (claim tokenType == "refresh").
     *
     * @param token token JWT
     * @return true se for refresh token
     */
    public boolean isRefreshToken(String token) {
        Claims claims = extractAllClaims(token);
        return "refresh".equals(claims.get("tokenType", String.class));
    }

    /**
     * Parseia e retorna todos os claims do token usando a secretKey.
     *
     * @param token token JWT
     * @return Claims extraídos do token
     */
    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Obtém o IP do cliente, considerando o header X-Forwarded-For quando presente.
     *
     * @param request requisição HTTP atual
     * @return IP do cliente
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}