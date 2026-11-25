/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.dashboard.infrastructure.redis;

import common.telemetry.TelemetriaKeys;
import inv.dashboard.application.port.AtividadeSistemaPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Adaptador de infraestrutura Redis para {@link AtividadeSistemaPort}.
 * Package-private por design para isolar detalhes de persistência.
 */
@Component
class RedisAtividadeSistemaAdapter implements AtividadeSistemaPort {

    private static final Logger log = LoggerFactory.getLogger(RedisAtividadeSistemaAdapter.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    RedisAtividadeSistemaAdapter(StringRedisTemplate redis, Clock clock) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public OptionalLong contarEventosNaJanela(Duration janela) {
        Objects.requireNonNull(janela, "janela");
        if (janela.isNegative() || janela.isZero()) {
            throw new IllegalArgumentException("A janela de atividade deve ser positiva");
        }

        long agoraEpochMinuto = TelemetriaKeys.epochMinute(clock.instant());
        long quantidadeBuckets = Math.max(1L, Math.ceilDiv(janela.toSeconds(), 60L));
        List<String> chaves = new ArrayList<>((int) quantidadeBuckets);
        for (long offset = 0; offset < quantidadeBuckets; offset++) {
            chaves.add(TelemetriaKeys.atividadeBucketKey(agoraEpochMinuto - offset));
        }

        try {
            List<String> valores = redis.opsForValue().multiGet(chaves);
            if (valores == null || valores.isEmpty()) {
                return OptionalLong.of(0L);
            }
            long total = 0L;
            for (String valor : valores) {
                if (valor != null && !valor.isBlank()) {
                    total += Long.parseLong(valor.trim());
                }
            }
            return OptionalLong.of(total);
        } catch (DataAccessException e) {
            log.warn("Telemetria de atividade indisponível no Redis: {}", e.getMessage());
            return OptionalLong.empty();
        } catch (RuntimeException e) {
            log.warn("Falha inesperada ao consultar telemetria de atividade: {}", e.getMessage(), e);
            return OptionalLong.empty();
        }
    }
}
