package auth.security.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class RefreshTokenCookieFilter extends OncePerRequestFilter {

    @Value("${auth.cookie.secure}")
    private boolean isCookieSecure;

    @Value("${auth.cookie.same-site}")
    private String cookieSameSite;

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        if (!request.getRequestURI().endsWith("/oauth2/token")) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(request, responseWrapper);

        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");

        if (response.getStatus() == 200) {
            String body = new String(responseWrapper.getContentAsByteArray(), StandardCharsets.UTF_8);

            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode json = mapper.readTree(body);

                if (json.has("refresh_token")) {
                    String refreshToken = json.get("refresh_token").asText();

                    ResponseCookie cookie = ResponseCookie.from("refresh_token", refreshToken)
                            .httpOnly(true)
                            .secure(isCookieSecure)
                            .sameSite(cookieSameSite)
                            .path("/oauth2/token")
                            .maxAge(Duration.ofDays(1))
                            .build();

                    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

                    ((ObjectNode) json).remove("refresh_token");

                    byte[] newBody = mapper.writeValueAsBytes(json);

                    responseWrapper.resetBuffer();
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType("application/json");
                    responseWrapper.getOutputStream().write(newBody);
                }
            } catch (Exception e) {
                // Em caso de erro de parse, mantém o body original (segurança por falha)

            }
        }
        responseWrapper.copyBodyToResponse();
    }
}