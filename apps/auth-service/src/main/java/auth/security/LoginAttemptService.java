/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serviço em memória limitado (LRU + expiração temporal) para proteção contra brute force
 * no endpoint {@code /login}, rastreando falhas consecutivas por {@code (username normalizado, IP)}.
 */
@Service
public class LoginAttemptService {

    private final int maxAttempts;
    private final Duration lockDuration;
    private final int maxEntries;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<AttemptKey, AttemptState> attempts = new LinkedHashMap<>(16, 0.75f, true);

    @Autowired
    public LoginAttemptService(
            @Value("${auth.login.lockout.max-attempts:5}") int maxAttempts,
            @Value("${auth.login.lockout.duration-minutes:15}") long lockDurationMinutes,
            @Value("${auth.login.lockout.max-entries:10000}") int maxEntries) {
        this(maxAttempts, Duration.ofMinutes(lockDurationMinutes), maxEntries, Clock.systemUTC());
    }

    public LoginAttemptService(int maxAttempts, Duration lockDuration, int maxEntries, Clock clock) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts deve ser >= 1");
        }
        if (lockDuration == null || lockDuration.isNegative() || lockDuration.isZero()) {
            throw new IllegalArgumentException("lockDuration deve ser positivo");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries deve ser >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.lockDuration = lockDuration;
        this.maxEntries = maxEntries;
        this.clock = Objects.requireNonNull(clock, "clock não pode ser nulo");
    }

    public boolean isBlocked(String username, String ip) {
        AttemptKey key = AttemptKey.of(username, ip);
        Instant now = clock.instant();
        lock.lock();
        try {
            AttemptState state = attempts.get(key);
            if (state == null) {
                return false;
            }
            if (state.isExpired(now)) {
                attempts.remove(key);
                return false;
            }
            return state.isLocked(now);
        } finally {
            lock.unlock();
        }
    }

    public void recordFailure(String username, String ip) {
        AttemptKey key = AttemptKey.of(username, ip);
        Instant now = clock.instant();
        lock.lock();
        try {
            AttemptState existing = attempts.get(key);
            if (existing != null && existing.isExpired(now)) {
                attempts.remove(key);
                existing = null;
            }
            if (existing != null && existing.isLocked(now)) {
                return;
            }

            int failures = (existing == null ? 0 : existing.failures()) + 1;
            Instant lockedUntil = failures >= maxAttempts ? now.plus(lockDuration) : null;
            AttemptState next = new AttemptState(failures, now.plus(lockDuration), lockedUntil);

            if (!attempts.containsKey(key) && attempts.size() >= maxEntries) {
                evictIfNeeded(now);
            }
            attempts.put(key, next);
        } finally {
            lock.unlock();
        }
    }

    public void recordSuccess(String username, String ip) {
        AttemptKey key = AttemptKey.of(username, ip);
        lock.lock();
        try {
            attempts.remove(key);
        } finally {
            lock.unlock();
        }
    }

    int trackedEntriesCount() {
        lock.lock();
        try {
            return attempts.size();
        } finally {
            lock.unlock();
        }
    }

    private void evictIfNeeded(Instant now) {
        attempts.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        if (attempts.size() < maxEntries) {
            return;
        }

        // Evita despejar contas atualmente bloqueadas enquanto houver contadores não bloqueados disponíveis.
        Iterator<Map.Entry<AttemptKey, AttemptState>> iterator = attempts.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<AttemptKey, AttemptState> entry = iterator.next();
            if (!entry.getValue().isLocked(now)) {
                iterator.remove();
                return;
            }
        }

        Iterator<Map.Entry<AttemptKey, AttemptState>> fallback = attempts.entrySet().iterator();
        if (fallback.hasNext()) {
            fallback.next();
            fallback.remove();
        }
    }

    private record AttemptKey(String username, String ip) {
        static AttemptKey of(String username, String ip) {
            String normalizedUser = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
            String normalizedIp = (ip == null || ip.isBlank()) ? "unknown" : ip.trim();
            return new AttemptKey(normalizedUser, normalizedIp);
        }
    }

    private record AttemptState(int failures, Instant windowExpiresAt, Instant lockedUntil) {
        boolean isLocked(Instant now) {
            return lockedUntil != null && now.isBefore(lockedUntil);
        }

        boolean isExpired(Instant now) {
            return lockedUntil != null ? !now.isBefore(lockedUntil) : !now.isBefore(windowExpiresAt);
        }
    }
}
