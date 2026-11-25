/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.checkout.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Filtro de autenticação HMAC-SHA256 com proteção anti-replay para webhooks de pagamento.
 *
 * <p>Contrato canônico da assinatura:
 * {@code HMAC-SHA256(secret, "<timestamp>." + <path> + "." + <rawBodyBytes>)}
 * onde {@code <timestamp>} é o epoch em segundos enviado no header {@code X-Webhook-Timestamp},
 * {@code <path>} é {@code request.getRequestURI()} e {@code <rawBodyBytes>} são os bytes brutos
 * do corpo da requisição (vazio para endpoints sem corpo, como {@code /pagamentos/webhook/falhar/{id}}).
 * O header {@code X-Webhook-Signature} deve conter a digestão em hex minúsculo, com ou sem prefixo {@code sha256=}.</p>
 */
public class WebhookHmacFilter extends OncePerRequestFilter {

    public static final String HEADER_TIMESTAMP = "X-Webhook-Timestamp";
    public static final String HEADER_SIGNATURE = "X-Webhook-Signature";
    public static final String SIGNATURE_PREFIX = "sha256=";
    public static final int MAX_BODY_BYTES = 64 * 1024;

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int SHA256_HEX_LENGTH = 64;

    private final byte[] secretBytes;
    private final long maxSkewSeconds;
    private final Clock clock;

    public WebhookHmacFilter(String hmacSecret, long maxSkewSeconds, Clock clock) {
        this.secretBytes = (hmacSecret == null || hmacSecret.isBlank())
                ? null
                : hmacSecret.getBytes(StandardCharsets.UTF_8);
        this.maxSkewSeconds = maxSkewSeconds;
        this.clock = Objects.requireNonNull(clock, "clock é obrigatório");
    }

    /**
     * Gera o valor do header {@code X-Webhook-Signature} no formato {@code sha256=<hex>}
     * segundo o contrato canônico {@code "<timestamp>." + <path> + "." + <rawBodyBytes>}.
     */
    public static String assinar(String secret, long timestampEpochSeconds, String path, byte[] rawBody) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("secret é obrigatório");
        }
        Objects.requireNonNull(path, "path é obrigatório");
        byte[] body = rawBody != null ? rawBody : new byte[0];
        byte[] mac = calcularHmac(secret.getBytes(StandardCharsets.UTF_8), timestampEpochSeconds, path, body);
        return SIGNATURE_PREFIX + HexFormat.of().formatHex(mac);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (secretBytes == null || maxSkewSeconds < 0) {
            rejeitarNaoAutorizado(response);
            return;
        }

        String timestampHeader = request.getHeader(HEADER_TIMESTAMP);
        String signatureHeader = request.getHeader(HEADER_SIGNATURE);
        if (timestampHeader == null || timestampHeader.isBlank()
                || signatureHeader == null || signatureHeader.isBlank()) {
            rejeitarNaoAutorizado(response);
            return;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            rejeitarNaoAutorizado(response);
            return;
        }

        long now = clock.instant().getEpochSecond();
        if (timestamp < now - maxSkewSeconds || timestamp > now + maxSkewSeconds) {
            rejeitarNaoAutorizado(response);
            return;
        }

        byte[] providedMac = decodificarAssinatura(signatureHeader.trim());
        if (providedMac == null) {
            rejeitarNaoAutorizado(response);
            return;
        }

        byte[] rawBody = lerCorpoComLimite(request.getInputStream());
        if (rawBody == null) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }

        byte[] expectedMac = calcularHmac(secretBytes, timestamp, request.getRequestURI(), rawBody);
        if (!MessageDigest.isEqual(expectedMac, providedMac)) {
            rejeitarNaoAutorizado(response);
            return;
        }

        UsernamePasswordAuthenticationToken auth = UsernamePasswordAuthenticationToken.authenticated(
                "webhook-hmac",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_WEBHOOK"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
        filterChain.doFilter(new CachedBodyHttpServletRequest(request, rawBody), response);
    }

    private static void rejeitarNaoAutorizado(HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private static byte[] decodificarAssinatura(String rawSignature) {
        String hex = rawSignature.startsWith(SIGNATURE_PREFIX)
                ? rawSignature.substring(SIGNATURE_PREFIX.length())
                : rawSignature;

        if (hex.length() != SHA256_HEX_LENGTH) {
            return null;
        }
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            boolean hexMinusculo = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hexMinusculo) {
                return null;
            }
        }
        return HexFormat.of().parseHex(hex);
    }

    private static byte[] lerCorpoComLimite(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int total = 0;
        int read;
        while ((read = inputStream.read(chunk)) != -1) {
            total += read;
            if (total > MAX_BODY_BYTES) {
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static byte[] calcularHmac(byte[] secret, long timestamp, String path, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            byte[] prefixo = (timestamp + "." + path + ".").getBytes(StandardCharsets.UTF_8);
            mac.update(prefixo);
            mac.update(rawBody);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao calcular assinatura HMAC-SHA256", e);
        }
    }

    private static final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private final byte[] rawBody;

        CachedBodyHttpServletRequest(HttpServletRequest request, byte[] rawBody) {
            super(request);
            this.rawBody = rawBody;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bais = new ByteArrayInputStream(rawBody);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return bais.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Leitura assíncrona não suportada");
                }

                @Override
                public int read() {
                    return bais.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return bais.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = (encoding != null && !encoding.isBlank())
                    ? Charset.forName(encoding)
                    : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return rawBody.length;
        }

        @Override
        public long getContentLengthLong() {
            return rawBody.length;
        }
    }
}
