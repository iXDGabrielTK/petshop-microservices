package auth.security.jwt;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class TokenBlacklistService {

    private final Map<String, Long> blacklistedTokens = new ConcurrentHashMap<>();

    public TokenBlacklistService() {
        ScheduledExecutorService cleanupService = Executors.newSingleThreadScheduledExecutor();
        cleanupService.scheduleAtFixedRate(this::cleanupExpiredTokens, 1, 1, TimeUnit.HOURS);
    }

    /**
     * Adiciona um token à blacklist com o tempo de expiração.
     *
     * @param token         O token a ser adicionado à blacklist
     * @param expirationTime A hora de expiração do token em milissegundos
     */
    public void blacklistToken(String token, long expirationTime) {
        blacklistedTokens.put(token, expirationTime);
    }

    /**
     * Verifica se um token está na blacklist.
     *
     * @param token O token a ser verificado
     * @return Verdadeiro se o token está na blacklist, falso caso contrário
     */
    boolean isBlacklisted(String token) {
        return blacklistedTokens.containsKey(token);
    }

    /**
     * Limpa os tokens expirados da blacklist.
     */
    private void cleanupExpiredTokens() {
        long now = System.currentTimeMillis();
        blacklistedTokens.entrySet().removeIf(entry -> entry.getValue() < now);
    }
}