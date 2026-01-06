package auth.security.jwt;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class JwtTokenService {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenService.class);
    private static final int MIN_SECRET_LENGTH = 64;

    private SecretKey secretKey;

    @Value("${jwt.expiration:3600000}")
    private Duration accessTokenExpiration;

    @Value("${jwt.refresh-expiration:86400000}")
    private Duration refreshTokenExpiration;

    @Value("${jwt.secret:}")
    private String configuredSecret;

    @PostConstruct
    public void init() {
        String secret = configuredSecret;

        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            logger.warn("JWT secret is not configured or too short! This is insecure for production.");
            if (secret == null || secret.isEmpty()) {
                secret = generateSecureSecret();
                logger.info("Generated a random JWT secret for development use only");
            }
            if (!isDevProfile()) {
                throw new IllegalStateException("JWT secret is insecure or missing in production!");
            }
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    public SecretKey secretKey() {
        return this.secretKey;
    }

    /**
     * Gera um JWT de acesso.
     *<p>
     * - Adiciona claims de `roles` extraídos do UserDetails.
     * - Adiciona dados de fingerprint (ip, userAgent, timestamp) quando possível.
     * - Usa `accessTokenExpiration` para definir validade.
     *
     * @param userDetails dados do usuário (username e authorities)
     * @return token JWT de acesso assinado
     */
    public String generateAccessToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        List<String> roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        claims.put("roles", roles);
        addFingerprintData(claims);
        return createToken(claims, userDetails.getUsername(), accessTokenExpiration);
    }

    /**
     * Gera um JWT de refresh.
     *<p>
     * - Adiciona dados de fingerprint.
     * - Define o claim `tokenType` como `"refresh"` para distinguir do access token.
     * - Usa `refreshTokenExpiration` para definir validade.
     *
     * @param userDetails dados do usuário
     * @return token JWT de refresh assinado
     */
    public String generateRefreshToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        addFingerprintData(claims);
        claims.put("tokenType", "refresh");
        return createToken(claims, userDetails.getUsername(), refreshTokenExpiration);
    }

    /**
     * Cria e assina o JWT com claims, subject e tempos.
     *<p>
     * - Valida que `subject` não seja nulo ou em branco.
     * - Define `issuedAt` e `expiration` a partir de `expiration`.
     * - Assina com `secretKey`.
     *
     * @param claims claims a incluir no token
     * @param subject subject (normalmente username)
     * @param expiration duração até expiração
     * @return token JWT como string compactada
     */
    private String createToken(Map<String, Object> claims, String subject, Duration expiration) {
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("Subject não pode ser nulo");
        Instant now = Instant.now();
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiration)))
                .signWith(secretKey)
                .compact();
    }

    /**
     * Adiciona dados de fingerprint aos claims quando há uma requisição HTTP ativa.
     *<p>
     * - Tenta extrair o `HttpServletRequest` do contexto do Spring.
     * - Inclui `ip`, `userAgent` e `timestamp` nos claims quando disponíveis.
     * - Em caso de erro apenas registra aviso e segue sem interromper a geração do token.
     *
     * @param claims mapa de claims que será enriquecido
     */
    private void addFingerprintData(Map<String, Object> claims) {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                claims.put("ip", getClientIp(request));
                String userAgent = request.getHeader("User-Agent");
                if (userAgent != null) claims.put("userAgent", userAgent);
                claims.put("timestamp", System.currentTimeMillis());
            }
        } catch (Exception e) {
            logger.warn("Error adding fingerprint data: {}", e.getMessage());
        }
    }

    /**
     * Retorna o IP do cliente.
     *<p>
     * - Considera o header `X-Forwarded-For` quando presente (primeiro endereço).
     * - Caso contrário usa `request.getRemoteAddr()`.
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

    /**
     * Gera um secret seguro aleatório codificado em Base64.
     *<p>
     * - Usa `SecureRandom` e um array de `MIN_SECRET_LENGTH` bytes.
     * - Destinado para uso em desenvolvimento quando nenhum secret é fornecido.
     *
     * @return secret seguro em Base64
     */
    private String generateSecureSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[MIN_SECRET_LENGTH];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Determina se o profile ativo contém `dev`.
     *<p>
     * - Lê `spring.profiles.active` da propriedade do sistema.
     * - Se não configurado assume `dev` (retorna true) para facilitar desenvolvimento local.
     *
     * @return true se estiver em perfil de desenvolvimento
     */
    boolean isDevProfile() {
        return Optional.ofNullable(System.getProperty("spring.profiles.active"))
                .map(profile -> profile.contains("dev"))
                .orElse(true);
    }
}