/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.checkout.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("WebhookHmacFilter — Testes Unitários de Assinatura e Anti-Replay")
class WebhookHmacFilterTest {

    private static final String SECRET = "segredo-hmac-teste-onda-2";
    private static final Instant NOW = Instant.parse("2026-11-25T20:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @AfterEach
    void limparContexto() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Deve rejeitar com 401 quando segredo HMAC estiver ausente ou em branco")
    void deveRejeitarQuandoSegredoAusente() throws Exception {
        WebhookHmacFilter filter = new WebhookHmacFilter("   ", 300, FIXED_CLOCK);
        byte[] body = "{\"valor_recebido\":10.00,\"nsu\":\"NSU-1\"}".getBytes(StandardCharsets.UTF_8);
        long ts = NOW.getEpochSecond();

        MockHttpServletRequest request = criarRequisicao("/pagamentos/webhook/confirmar/1", body,
                String.valueOf(ts), WebhookHmacFilter.assinar(SECRET, ts, "/pagamentos/webhook/confirmar/1", body));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("Deve rejeitar com 401 quando headers obrigatórios estiverem ausentes")
    void deveRejeitarQuandoHeaderAusente() throws Exception {
        WebhookHmacFilter filter = new WebhookHmacFilter(SECRET, 300, FIXED_CLOCK);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        MockHttpServletRequest semAssinatura = criarRequisicao("/pagamentos/webhook/confirmar/1", body,
                String.valueOf(NOW.getEpochSecond()), null);
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(semAssinatura, res1, chain);
        assertThat(res1.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);

        MockHttpServletRequest semTimestamp = criarRequisicao("/pagamentos/webhook/confirmar/1", body,
                null, WebhookHmacFilter.assinar(SECRET, NOW.getEpochSecond(), "/pagamentos/webhook/confirmar/1", body));
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilter(semTimestamp, res2, chain);
        assertThat(res2.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);

        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("Deve rejeitar com 401 quando timestamp for inválido, expirado ou futuro além da janela")
    void deveRejeitarTimestampInvalidoOuForaDaJanela() throws Exception {
        WebhookHmacFilter filter = new WebhookHmacFilter(SECRET, 300, FIXED_CLOCK);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest tsNaoNumerico = criarRequisicao("/pagamentos/webhook/confirmar/1", body,
                "abc", WebhookHmacFilter.assinar(SECRET, NOW.getEpochSecond(), "/pagamentos/webhook/confirmar/1", body));
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilter(tsNaoNumerico, res1, chain);
        assertThat(res1.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);

        long tsExpirado = NOW.getEpochSecond() - 301;
        MockHttpServletRequest reqExpirada = criarRequisicao("/pagamentos/webhook/confirmar/1", body,
                String.valueOf(tsExpirado), WebhookHmacFilter.assinar(SECRET, tsExpirado, "/pagamentos/webhook/confirmar/1", body));
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilter(reqExpirada, res2, chain);
        assertThat(res2.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);

        long tsFuturo = NOW.getEpochSecond() + 301;
        MockHttpServletRequest reqFutura = criarRequisicao("/pagamentos/webhook/confirmar/1", body,
                String.valueOf(tsFuturo), WebhookHmacFilter.assinar(SECRET, tsFuturo, "/pagamentos/webhook/confirmar/1", body));
        MockHttpServletResponse res3 = new MockHttpServletResponse();
        filter.doFilter(reqFutura, res3, chain);
        assertThat(res3.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);

        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("Deve rejeitar com 401 quando assinatura for inválida, hex maiúsculo, body adulterado ou path adulterado")
    void deveRejeitarAssinaturaInvalidaOuAdulteracao() throws Exception {
        WebhookHmacFilter filter = new WebhookHmacFilter(SECRET, 300, FIXED_CLOCK);
        long ts = NOW.getEpochSecond();
        String path = "/pagamentos/webhook/confirmar/42";
        byte[] bodyOriginal = "{\"valor_recebido\":100.00,\"nsu\":\"NSU-1\"}".getBytes(StandardCharsets.UTF_8);
        String assinaturaValida = WebhookHmacFilter.assinar(SECRET, ts, path, bodyOriginal);
        FilterChain chain = mock(FilterChain.class);

        // Assinatura incorreta
        MockHttpServletRequest reqAssinaturaInvalida = criarRequisicao(path, bodyOriginal, String.valueOf(ts),
                "sha256=" + "0".repeat(64));
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilter(reqAssinaturaInvalida, res1, chain);
        assertThat(res1.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);

        // Hex maiúsculo rejeitado
        MockHttpServletRequest reqMaiusculo = criarRequisicao(path, bodyOriginal, String.valueOf(ts),
                assinaturaValida.toUpperCase());
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilter(reqMaiusculo, res2, chain);
        assertThat(res2.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);

        // Body adulterado
        byte[] bodyAdulterado = "{\"valor_recebido\":0.01,\"nsu\":\"NSU-1\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest reqBodyAdulterado = criarRequisicao(path, bodyAdulterado, String.valueOf(ts), assinaturaValida);
        MockHttpServletResponse res3 = new MockHttpServletResponse();
        filter.doFilter(reqBodyAdulterado, res3, chain);
        assertThat(res3.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);

        // Path adulterado (pagamentoId diferente)
        MockHttpServletRequest reqPathAdulterado = criarRequisicao("/pagamentos/webhook/confirmar/99", bodyOriginal,
                String.valueOf(ts), assinaturaValida);
        MockHttpServletResponse res4 = new MockHttpServletResponse();
        filter.doFilter(reqPathAdulterado, res4, chain);
        assertThat(res4.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);

        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("Deve autenticar, preservar body e aceitar formato sha256=<hex> e hex minúsculo puro")
    void deveAutenticarEPreservarBodyComSha256OuHexPuro() throws Exception {
        WebhookHmacFilter filter = new WebhookHmacFilter(SECRET, 300, FIXED_CLOCK);
        long ts = NOW.getEpochSecond();
        String path = "/pagamentos/webhook/confirmar/42";
        byte[] body = "{\"valor_recebido\":150.50,\"nsu\":\"NSU-123\"}".getBytes(StandardCharsets.UTF_8);
        String assinaturaComPrefixo = WebhookHmacFilter.assinar(SECRET, ts, path, body);
        String assinaturaSemPrefixo = assinaturaComPrefixo.substring(WebhookHmacFilter.SIGNATURE_PREFIX.length());

        for (String assinatura : new String[]{assinaturaComPrefixo, assinaturaSemPrefixo}) {
            SecurityContextHolder.clearContext();
            MockHttpServletRequest request = criarRequisicao(path, body, String.valueOf(ts), assinatura);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<byte[]> corpoLidoNaCadeia = new AtomicReference<>();
            AtomicReference<Authentication> authNaCadeia = new AtomicReference<>();

            filter.doFilter(request, response, (req, res) -> {
                HttpServletRequest httpReq = (HttpServletRequest) req;
                corpoLidoNaCadeia.set(httpReq.getInputStream().readAllBytes());
                authNaCadeia.set(SecurityContextHolder.getContext().getAuthentication());
            });

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
            assertThat(corpoLidoNaCadeia.get()).isEqualTo(body);
            assertThat(authNaCadeia.get()).isNotNull();
            assertThat(authNaCadeia.get().isAuthenticated()).isTrue();
            assertThat(authNaCadeia.get().getAuthorities())
                    .extracting("authority")
                    .containsExactly("ROLE_WEBHOOK");
        }
    }

    @Test
    @DisplayName("Deve autenticar requisição sem body (como /pagamentos/webhook/falhar/{id})")
    void deveAutenticarRequisicaoSemBody() throws Exception {
        WebhookHmacFilter filter = new WebhookHmacFilter(SECRET, 300, FIXED_CLOCK);
        long ts = NOW.getEpochSecond();
        String path = "/pagamentos/webhook/falhar/42";
        String assinatura = WebhookHmacFilter.assinar(SECRET, ts, path, new byte[0]);

        MockHttpServletRequest request = criarRequisicao(path, new byte[0], String.valueOf(ts), assinatura);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<byte[]> corpoLido = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) ->
                corpoLido.set(req.getInputStream().readAllBytes()));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(corpoLido.get()).isEmpty();
    }

    private static MockHttpServletRequest criarRequisicao(String path, byte[] body, String timestamp, String signature) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        request.setContent(body);
        if (timestamp != null) {
            request.addHeader(WebhookHmacFilter.HEADER_TIMESTAMP, timestamp);
        }
        if (signature != null) {
            request.addHeader(WebhookHmacFilter.HEADER_SIGNATURE, signature);
        }
        return request;
    }
}
