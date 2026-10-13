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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.OptionalLong;
import java.util.UUID;

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
        redisTemplate.delete(TelemetriaKeys.ATIVIDADE_ZSET);

        fixedClock = Clock.fixed(NOW, ZoneId.of("America/Sao_Paulo"));
        adapter = new RedisAtividadeSistemaAdapter(redisTemplate, fixedClock);
    }

    @Test
    @DisplayName("Deve contar eventos na janela de 1 hora ignorando eventos expirados fora do intervalo")
    void deveContarEventosNaJanelaComSucesso() {
        long agoraMillis = fixedClock.millis();
        Duration janela = Duration.ofHours(1);

        // 3 eventos dentro da janela (5 min atrás, 20 min atrás, 55 min atrás)
        redisTemplate.opsForZSet().add(TelemetriaKeys.ATIVIDADE_ZSET, UUID.randomUUID().toString(), agoraMillis - Duration.ofMinutes(5).toMillis());
        redisTemplate.opsForZSet().add(TelemetriaKeys.ATIVIDADE_ZSET, UUID.randomUUID().toString(), agoraMillis - Duration.ofMinutes(20).toMillis());
        redisTemplate.opsForZSet().add(TelemetriaKeys.ATIVIDADE_ZSET, UUID.randomUUID().toString(), agoraMillis - Duration.ofMinutes(55).toMillis());

        // 1 evento fora da janela (65 min atrás)
        redisTemplate.opsForZSet().add(TelemetriaKeys.ATIVIDADE_ZSET, UUID.randomUUID().toString(), agoraMillis - Duration.ofMinutes(65).toMillis());

        OptionalLong contagem = adapter.contarEventosNaJanela(janela);

        assertThat(contagem).isPresent();
        assertThat(contagem.getAsLong()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Deve retornar contagem zero quando o ZSET de telemetria estiver vazio")
    void deveRetornarZeroQuandoZSetEstiverVazio() {
        OptionalLong contagem = adapter.contarEventosNaJanela(Duration.ofHours(1));

        assertThat(contagem).isPresent();
        assertThat(contagem.getAsLong()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Deve degradar retornando OptionalLong.empty() sem lançar exceção quando o Redis estiver inacessível")
    void deveDegradarRetornandoEmptyQuandoRedisEstiverInacessivel() {
        // Factory apontando para porta sem serviço ativo com timeout curto de 200ms
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(200))
                .build();
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration("127.0.0.1", 59999);
        LettuceConnectionFactory brokenFactory = new LettuceConnectionFactory(serverConfig, clientConfig);
        brokenFactory.afterPropertiesSet();

        StringRedisTemplate brokenTemplate = new StringRedisTemplate(brokenFactory);
        brokenTemplate.afterPropertiesSet();

        AtividadeSistemaPort brokenAdapter = new RedisAtividadeSistemaAdapter(brokenTemplate, fixedClock);

        // Deve capturar DataAccessException / RedisConnectionFailureException e retornar OptionalLong.empty()
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
