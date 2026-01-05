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

    /**
     * Expõe a chave secreta como Bean para ser usada pelo JwtTokenValidator
     */
    @Bean
    public SecretKey secretKey() {
        return this.secretKey;
    }

    public String generateAccessToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        List<String> roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        claims.put("roles", roles);
        addFingerprintData(claims);
        return createToken(claims, userDetails.getUsername(), accessTokenExpiration);
    }

    public String generateRefreshToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        addFingerprintData(claims);
        claims.put("tokenType", "refresh");
        return createToken(claims, userDetails.getUsername(), refreshTokenExpiration);
    }

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

    // --- Métodos Auxiliares Privados ---

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

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String generateSecureSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[MIN_SECRET_LENGTH];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    boolean isDevProfile() {
        return Optional.ofNullable(System.getProperty("spring.profiles.active"))
                .map(profile -> profile.contains("dev"))
                .orElse(true);
    }
}