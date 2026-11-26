/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.dashboard.infrastructure.redis;

import common.telemetry.TelemetriaKeys;
import inv.dashboard.application.port.AtividadeSistemaPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class RedisAtividadeSistemaAdapterIT {

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private StringRedisTemplate redisTemplate;
    private Clock fixedClock;
    private AtividadeSistemaPort adapter;

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redisContainer.getHost(), redisContainer.getMappedPort(6379))
        );
        factory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();
        Set<String> existingBuckets = redisTemplate.keys(TelemetriaKeys.ATIVIDADE_BUCKET_PREFIX + "*");
        if (existingBuckets != null && !existingBuckets.isEmpty()) {
            redisTemplate.delete(existingBuckets);
        }

        fixedClock = Clock.fixed(NOW, ZoneId.of("America/Sao_Paulo"));
        adapter = new RedisAtividadeSistemaAdapter(redisTemplate, fixedClock);
    }

    @Test
    @DisplayName("Deve somar buckets de atividade na janela de 1 hora ignorando buckets fora do intervalo")
    void deveContarEventosNaJanelaComSucesso() {
        Duration janela = Duration.ofHours(1);

        // 3 eventos dentro da janela (5 min atrás, 20 min atrás, 55 min atrás)
        redisTemplate.opsForValue().increment(TelemetriaKeys.atividadeBucketKey(NOW.minus(Duration.ofMinutes(5))));
        redisTemplate.opsForValue().increment(TelemetriaKeys.atividadeBucketKey(NOW.minus(Duration.ofMinutes(20))));
        redisTemplate.opsForValue().increment(TelemetriaKeys.atividadeBucketKey(NOW.minus(Duration.ofMinutes(55))));

        // 1 evento fora da janela (65 min atrás)
        redisTemplate.opsForValue().increment(TelemetriaKeys.atividadeBucketKey(NOW.minus(Duration.ofMinutes(65))));

        OptionalLong contagem = adapter.contarEventosNaJanela(janela);

        assertThat(contagem).isPresent();
        assertThat(contagem.getAsLong()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Deve agregar múltiplos eventos do mesmo minuto em uma única chave Redis (O(1) por minuto)")
    void deveAgregarMultiplosEventosNoMesmoMinuto() {
        String key = TelemetriaKeys.atividadeBucketKey(NOW.minus(Duration.ofMinutes(2)));
        for (int i = 0; i < 15; i++) {
            redisTemplate.opsForValue().increment(key);
        }

        OptionalLong contagem = adapter.contarEventosNaJanela(Duration.ofHours(1));

        assertThat(contagem).isPresent();
        assertThat(contagem.getAsLong()).isEqualTo(15L);
        assertThat(redisTemplate.keys(TelemetriaKeys.ATIVIDADE_BUCKET_PREFIX + "*")).containsExactly(key);
    }

    @Test
    @DisplayName("Deve garantir TTL atômico e autorreparo via script Lua mantendo leitura pelo adaptador")
    void deveLerBucketsGravadosPeloScriptLuaComTtlGarantidoEAutorreparo() {
        String key = TelemetriaKeys.atividadeBucketKey(NOW.minus(Duration.ofMinutes(3)));
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(TelemetriaKeys.ATIVIDADE_INCREMENT_LUA, Long.class);
        String ttlSeconds = String.valueOf(TelemetriaKeys.ATIVIDADE_BUCKET_TTL.toSeconds());

        for (int i = 0; i < 10; i++) {
            Long count = redisTemplate.execute(script, List.of(key), ttlSeconds);
            assertThat(count).isEqualTo(i + 1L);
        }

        Long initialTtl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(initialTtl).isNotNull().isBetween(1L, TelemetriaKeys.ATIVIDADE_BUCKET_TTL.toSeconds());

        // Simula chave órfã sem TTL (ex.: falha parcial externa) e valida autorreparo no próximo incremento
        assertThat(redisTemplate.persist(key)).isTrue();
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isEqualTo(-1L);

        Long repairedCount = redisTemplate.execute(script, List.of(key), ttlSeconds);
        assertThat(repairedCount).isEqualTo(11L);
        Long repairedTtl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(repairedTtl).isNotNull().isBetween(1L, TelemetriaKeys.ATIVIDADE_BUCKET_TTL.toSeconds());

        OptionalLong contagem = adapter.contarEventosNaJanela(Duration.ofHours(1));
        assertThat(contagem).isPresent();
        assertThat(contagem.getAsLong()).isEqualTo(11L);
        assertThat(redisTemplate.keys(TelemetriaKeys.ATIVIDADE_BUCKET_PREFIX + "*")).containsExactly(key);
    }

    @Test
    @DisplayName("Deve retornar contagem zero quando não houver buckets de telemetria")
    void deveRetornarZeroQuandoZSetEstiverVazio() {
        OptionalLong contagem = adapter.contarEventosNaJanela(Duration.ofHours(1));

        assertThat(contagem).isPresent();
        assertThat(contagem.getAsLong()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Deve degradar retornando OptionalLong.empty() sem lançar exceção quando o Redis estiver inacessível")
    void deveDegradarRetornandoEmptyQuandoRedisEstiverInacessivel() {
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(200))
                .build();
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration("127.0.0.1", 59999);
        LettuceConnectionFactory brokenFactory = new LettuceConnectionFactory(serverConfig, clientConfig);
        brokenFactory.afterPropertiesSet();

        StringRedisTemplate brokenTemplate = new StringRedisTemplate(brokenFactory);
        brokenTemplate.afterPropertiesSet();

        AtividadeSistemaPort brokenAdapter = new RedisAtividadeSistemaAdapter(brokenTemplate, fixedClock);

        OptionalLong resultado = brokenAdapter.contarEventosNaJanela(Duration.ofHours(1));

        assertThat(resultado).isEmpty();
        brokenFactory.destroy();
    }

    @Test
    @DisplayName("Deve lançar IllegalArgumentException quando janela for zero ou negativa")
    void deveLancarExcecaoQuandoJanelaForZeroOuNegativa() {
        assertThatThrownBy(() -> adapter.contarEventosNaJanela(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positiva");

        assertThatThrownBy(() -> adapter.contarEventosNaJanela(Duration.ofMinutes(-5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positiva");
    }
}
