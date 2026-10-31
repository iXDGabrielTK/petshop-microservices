package gateway.telemetry;

import common.telemetry.TelemetriaKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityPurgeScheduler — Testes Unitários da Purga Periódica do ZSet de Atividade")
class ActivityPurgeSchedulerTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private ReactiveZSetOperations<String, String> zSetOperations;

    @Captor
    private ArgumentCaptor<String> keyCaptor;

    @Captor
    private ArgumentCaptor<Range<Double>> rangeCaptor;

    private ActivityPurgeScheduler scheduler;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        scheduler = new ActivityPurgeScheduler(redisTemplate);
    }

    @Test
    @DisplayName("Deve purgar registros mais antigos que 1 hora com sucesso")
    void devePurgarRegistrosComMaisDeUmaHora() {
        long before = System.currentTimeMillis() - 3_600_000L;

        when(zSetOperations.removeRangeByScore(eq(TelemetriaKeys.ATIVIDADE_ZSET), any()))
                .thenReturn(Mono.just(5L));

        scheduler.purgeOldActivity();

        long after = System.currentTimeMillis() - 3_600_000L;

        verify(zSetOperations).removeRangeByScore(keyCaptor.capture(), rangeCaptor.capture());
        assertThat(keyCaptor.getValue()).isEqualTo(TelemetriaKeys.ATIVIDADE_ZSET);

        Range<Double> range = rangeCaptor.getValue();
        assertThat(range).isNotNull();
        assertThat(range.getLowerBound().getValue()).contains(0.0);
        assertThat(range.getLowerBound().isInclusive()).isTrue();

        assertThat(range.getUpperBound().getValue()).isPresent();
        double upperBound = range.getUpperBound().getValue().get();
        assertThat((long) upperBound).isBetween(before, after);
    }

    @Test
    @DisplayName("Deve tolerar falha do Redis na purga sem propagar exceção")
    void deveTolerarFalhaDoRedisSemPropagarExcecao() {
        when(zSetOperations.removeRangeByScore(eq(TelemetriaKeys.ATIVIDADE_ZSET), any()))
                .thenReturn(Mono.error(new RuntimeException("Redis timeout")));

        // Não deve lançar exceção
        scheduler.purgeOldActivity();

        verify(zSetOperations).removeRangeByScore(any(), any());
    }

    @Test
    @DisplayName("Construtor deve rejeitar redisTemplate nulo")
    void deveRejeitarRedisTemplateNulo() {
        assertThatThrownBy(() -> new ActivityPurgeScheduler(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("redisTemplate não pode ser nulo");
    }
}
