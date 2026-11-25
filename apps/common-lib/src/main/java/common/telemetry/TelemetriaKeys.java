/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package common.telemetry;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class TelemetriaKeys {

    @Deprecated(forRemoval = true)
    public static final String ATIVIDADE_ZSET = "system:activity:ts";

    public static final String ATIVIDADE_BUCKET_PREFIX = "system:activity:bucket:";
    public static final Duration ATIVIDADE_BUCKET_TTL = Duration.ofHours(1);

    private TelemetriaKeys() {}

    public static long epochMinute(Instant instante) {
        Objects.requireNonNull(instante, "instante não pode ser nulo");
        return Math.floorDiv(instante.getEpochSecond(), 60L);
    }

    public static String atividadeBucketKey(Instant instante) {
        return atividadeBucketKey(epochMinute(instante));
    }

    public static String atividadeBucketKey(long epochMinute) {
        return ATIVIDADE_BUCKET_PREFIX + epochMinute;
    }
}
