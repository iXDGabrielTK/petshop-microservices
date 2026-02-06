package auth.security.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CookieRefreshTokenRequestFilter implements Filter {

    @Value("${frontend.base-url}")
    private String frontendUrl;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        if ("/oauth2/token".equals(req.getRequestURI()) &&
                "refresh_token".equals(request.getParameter("grant_type"))) {

            String origin = req.getHeader("Origin");
            String referer = req.getHeader("Referer");

            boolean isValidOrigin = origin != null && (origin.equals(frontendUrl) || origin.equals(frontendUrl + "/"));
            boolean isValidReferer = referer != null && referer.startsWith(frontendUrl);

            if (!isValidOrigin && !isValidReferer) {
                res.sendError(HttpServletResponse.SC_FORBIDDEN, "CSRF: Invalid Origin/Referer");
                return;
            }

            Optional<Cookie> refreshCookie = Optional.ofNullable(req.getCookies())
                    .flatMap(cookies -> Arrays.stream(cookies)
                            .filter(c -> "refresh_token".equals(c.getName()))
                            .findFirst());

            if (refreshCookie.isPresent()) {
                HttpServletRequestWrapper wrapper = new HttpServletRequestWrapper(req) {
                    @Override
                    public String getParameter(String name) {
                        if ("refresh_token".equals(name)) return refreshCookie.get().getValue();
                        return super.getParameter(name);
                    }

                    @Override
                    public Map<String, String[]> getParameterMap() {
                        Map<String, String[]> map = new HashMap<>(super.getParameterMap());
                        map.put("refresh_token", new String[]{refreshCookie.get().getValue()});
                        return map;
                    }

                    @Override
                    public String[] getParameterValues(String name) {
                        if ("refresh_token".equals(name)) return new String[]{refreshCookie.get().getValue()};
                        return super.getParameterValues(name);
                    }

                    @Override
                    public Enumeration<String> getParameterNames() {
                        Set<String> names = new HashSet<>(Collections.list(super.getParameterNames()));
                        names.add("refresh_token");
                        return Collections.enumeration(names);
                    }
                };
                chain.doFilter(wrapper, response);
                return;
            }
        }

        chain.doFilter(request, response);
    }
}