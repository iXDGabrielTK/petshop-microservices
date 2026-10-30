package gateway.filter;

import common.telemetry.TelemetriaKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityFilter — Testes Unitários do Filtro Reativo de Telemetria de Atividade")
class ActivityFilterTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private ReactiveZSetOperations<String, String> zSetOperations;

    @Mock
    private GatewayFilterChain chain;

    @Captor
    private ArgumentCaptor<String> keyCaptor;

    @Captor
    private ArgumentCaptor<String> memberCaptor;

    @Captor
    private ArgumentCaptor<Double> scoreCaptor;

    @Captor
    private ArgumentCaptor<Range<Double>> rangeCaptor;

    private ActivityFilter activityFilter;
    private ServerWebExchange exchange;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        lenient().when(zSetOperations.add(anyString(), anyString(), anyDouble()))
                .thenReturn(Mono.just(Boolean.TRUE));
        lenient().when(zSetOperations.removeRangeByScore(anyString(), any()))
                .thenReturn(Mono.just(0L));
        lenient().when(chain.filter(any()))
                .thenReturn(Mono.empty());

        activityFilter = new ActivityFilter(redisTemplate);

        MockServerHttpRequest request = MockServerHttpRequest.get("/produtos/123").build();
        exchange = MockServerWebExchange.from(request);
    }

    @Nested
    @DisplayName("Execução com Sucesso no Fluxo Reativo")
    class SucessoTests {

        @Test
        @DisplayName("Deve registrar timestamp atual no ZSet, purgar registros com mais de 1h e repassar a cadeia sem quebrar o pipeline")
        void deveExecutarFiltroComSucesso() {
            long before = System.currentTimeMillis();

            when(zSetOperations.add(anyString(), anyString(), anyDouble()))
                    .thenReturn(Mono.just(Boolean.TRUE));
            when(zSetOperations.removeRangeByScore(anyString(), any()))
                    .thenReturn(Mono.just(1L));
            when(chain.filter(same(exchange)))
                    .thenReturn(Mono.empty());

            // Execução via StepVerifier reativo
            StepVerifier.create(activityFilter.filter(exchange, chain))
                    .verifyComplete();

            long after = System.currentTimeMillis();

            // 1. Validação da adição ao ZSet (TelemetriaKeys.ATIVIDADE_ZSET)
            verify(zSetOperations).add(keyCaptor.capture(), memberCaptor.capture(), scoreCaptor.capture());
            assertThat(keyCaptor.getValue()).isEqualTo(TelemetriaKeys.ATIVIDADE_ZSET);

            // Membro deve ser um UUID válido
            UUID parsedUuid = UUID.fromString(memberCaptor.getValue());
            assertThat(parsedUuid).isNotNull();

            // Score deve ser o timestamp atual em milissegundos
            Double score = scoreCaptor.getValue();
            assertThat(score).isNotNull();
            assertThat(score.longValue())
                    .isGreaterThanOrEqualTo(before)
                    .isLessThanOrEqualTo(after);

            // 2. Validação da remoção de registros mais velhos que 1 hora
            verify(zSetOperations).removeRangeByScore(keyCaptor.capture(), rangeCaptor.capture());
            assertThat(keyCaptor.getValue()).isEqualTo(TelemetriaKeys.ATIVIDADE_ZSET);

            Range<Double> range = rangeCaptor.getValue();
            assertThat(range).isNotNull();

            // Limite inferior: 0.0 inclusive
            assertThat(range.getLowerBound().getValue()).contains(0.0);
            assertThat(range.getLowerBound().isInclusive()).isTrue();

            // Limite superior: score - 3600000 (1 hora atrás)
            assertThat(range.getUpperBound().getValue()).isPresent();
            double upperBound = range.getUpperBound().getValue().get();
            long expectedOneHourAgo = score.longValue() - 3600000L;
            assertThat((long) upperBound).isEqualTo(expectedOneHourAgo);
            assertThat(range.getUpperBound().isInclusive()).isTrue();

            // 3. Validação do repasse na cadeia com o mesmo ServerWebExchange
            verify(chain).filter(exchange);
        }
    }

    @Nested
    @DisplayName("Propagação de Erros e Resiliência do Pipeline Reativo")
    class TratamentoErroTests {

        @Test
        @DisplayName("Deve tolerar falha de conexão do Redis no add sem quebrar o pipeline e prosseguir na cadeia")
        void deveTolerarFalhaNoAddSemQuebrarPipeline() {
            RuntimeException redisEx = new RuntimeException("Redis connection refused");
            when(zSetOperations.add(anyString(), anyString(), anyDouble()))
                    .thenReturn(Mono.error(redisEx));
            when(chain.filter(same(exchange)))
                    .thenReturn(Mono.empty());

            StepVerifier.create(activityFilter.filter(exchange, chain))
                    .verifyComplete();

            verify(zSetOperations).add(anyString(), anyString(), anyDouble());
            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Deve tolerar timeout do Redis no removeRangeByScore sem quebrar o pipeline e prosseguir na cadeia")
        void deveTolerarFalhaNoRemoveRangeByScoreSemQuebrarPipeline() {
            when(zSetOperations.add(anyString(), anyString(), anyDouble()))
                    .thenReturn(Mono.just(Boolean.TRUE));
            RuntimeException redisEx = new RuntimeException("Redis timeout on purge");
            when(zSetOperations.removeRangeByScore(anyString(), any()))
                    .thenReturn(Mono.error(redisEx));
            when(chain.filter(same(exchange)))
                    .thenReturn(Mono.empty());

            StepVerifier.create(activityFilter.filter(exchange, chain))
                    .verifyComplete();

            verify(zSetOperations).removeRangeByScore(anyString(), any());
            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Deve propagar erro downstream quando o chain subsequente falhar")
        void devePropagarErroQuandoChainFalhar() {
            when(zSetOperations.add(anyString(), anyString(), anyDouble()))
                    .thenReturn(Mono.just(Boolean.TRUE));
            when(zSetOperations.removeRangeByScore(anyString(), any()))
                    .thenReturn(Mono.just(0L));

            RuntimeException chainEx = new RuntimeException("Downstream route failure");
            when(chain.filter(same(exchange)))
                    .thenReturn(Mono.error(chainEx));

            StepVerifier.create(activityFilter.filter(exchange, chain))
                    .expectErrorMatches(ex -> ex instanceof RuntimeException && "Downstream route failure".equals(ex.getMessage()))
                    .verify();

            verify(chain).filter(exchange);
        }
    }

    @Nested
    @DisplayName("Validação Defensiva de Parâmetros")
    class ValidacaoParametrosTests {

        @Test
        @DisplayName("Construtor deve rejeitar redisTemplate nulo")
        void deveRejeitarRedisTemplateNulo() {
            assertThatThrownBy(() -> new ActivityFilter(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("redisTemplate não pode ser nulo");
        }

        @Test
        @DisplayName("filter deve rejeitar exchange nulo")
        void deveRejeitarExchangeNulo() {
            assertThatThrownBy(() -> activityFilter.filter(null, chain))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("exchange não pode ser nulo");
        }

        @Test
        @DisplayName("filter deve rejeitar chain nulo")
        void deveRejeitarChainNulo() {
            assertThatThrownBy(() -> activityFilter.filter(exchange, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("chain não pode ser nulo");
        }
    }
}
