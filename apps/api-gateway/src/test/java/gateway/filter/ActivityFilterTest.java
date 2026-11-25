/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
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
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityFilter — Testes Unitários de Telemetria por Buckets por Minuto")
class ActivityFilterTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-25T15:30:15Z");

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    @Mock
    private GatewayFilterChain chain;

    @Captor
    private ArgumentCaptor<String> keyCaptor;

    private Clock fixedClock;
    private ActivityFilter activityFilter;
    private ServerWebExchange exchange;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.increment(anyString())).thenReturn(Mono.just(1L));
        lenient().when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(Boolean.TRUE));
        lenient().when(chain.filter(any())).thenReturn(Mono.empty());

        activityFilter = new ActivityFilter(redisTemplate, fixedClock);

        MockServerHttpRequest request = MockServerHttpRequest.get("/produtos/123").build();
        exchange = MockServerWebExchange.from(request);
    }

    @Nested
    @DisplayName("Execução com Sucesso e Memória O(1) por Minuto")
    class SucessoTests {

        @Test
        @DisplayName("Deve incrementar o bucket do minuto corrente com TTL de 1 hora sem usar ZSet")
        void deveIncrementarBucketComTtl() {
            String expectedKey = TelemetriaKeys.atividadeBucketKey(FIXED_NOW);

            StepVerifier.create(activityFilter.filter(exchange, chain))
                    .verifyComplete();

            verify(valueOperations).increment(expectedKey);
            verify(redisTemplate).expire(expectedKey, TelemetriaKeys.ATIVIDADE_BUCKET_TTL);
            verify(redisTemplate, never()).opsForZSet();
            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Deve agregar múltiplas requisições no mesmo minuto em uma única chave (O(1) por minuto)")
        void deveAgregarRequisicoesNoMesmoMinutoEmUnicaChave() {
            for (int i = 0; i < 50; i++) {
                StepVerifier.create(activityFilter.filter(exchange, chain))
                        .verifyComplete();
            }

            verify(valueOperations, times(50)).increment(keyCaptor.capture());
            Set<String> distinctKeys = new HashSet<>(keyCaptor.getAllValues());
            assertThat(distinctKeys).containsExactly(TelemetriaKeys.atividadeBucketKey(FIXED_NOW));
        }

        @Test
        @DisplayName("Deve usar novo bucket quando avançar para o minuto seguinte")
        void deveUsarNovoBucketNoMinutoSeguinte() {
            Instant nextMinute = FIXED_NOW.plusSeconds(60);
            ActivityFilter nextMinuteFilter = new ActivityFilter(redisTemplate, Clock.fixed(nextMinute, ZoneOffset.UTC));

            StepVerifier.create(activityFilter.filter(exchange, chain)).verifyComplete();
            StepVerifier.create(nextMinuteFilter.filter(exchange, chain)).verifyComplete();

            verify(valueOperations).increment(TelemetriaKeys.atividadeBucketKey(FIXED_NOW));
            verify(valueOperations).increment(TelemetriaKeys.atividadeBucketKey(nextMinute));
        }

        @Test
        @DisplayName("Deve executar após o RequestRateLimiter e antes do roteamento Netty")
        void deveExecutarComOrdemAposRateLimiter() {
            assertThat(activityFilter.getOrder()).isGreaterThan(1);
            assertThat(activityFilter.getOrder()).isLessThan(Ordered.LOWEST_PRECEDENCE);
        }
    }

    @Nested
    @DisplayName("Resiliência do Pipeline Reativo")
    class TratamentoErroTests {

        @Test
        @DisplayName("Deve tolerar falha no increment do Redis sem quebrar o pipeline")
        void deveTolerarFalhaNoIncrement() {
            when(valueOperations.increment(anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));

            StepVerifier.create(activityFilter.filter(exchange, chain))
                    .verifyComplete();

            verify(valueOperations).increment(anyString());
            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Deve tolerar falha no expire do Redis sem quebrar o pipeline")
        void deveTolerarFalhaNoExpire() {
            when(redisTemplate.expire(anyString(), eq(TelemetriaKeys.ATIVIDADE_BUCKET_TTL)))
                    .thenReturn(Mono.error(new RuntimeException("Redis timeout")));

            StepVerifier.create(activityFilter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Deve propagar erro downstream quando o chain subsequente falhar")
        void devePropagarErroQuandoChainFalhar() {
            RuntimeException chainEx = new RuntimeException("Downstream route failure");
            when(chain.filter(same(exchange))).thenReturn(Mono.error(chainEx));

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
