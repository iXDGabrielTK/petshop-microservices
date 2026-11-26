/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.security;

import java.net.URI;
import java.net.URISyntaxException;

public final class FrontendOrigin {

    private static final String AUTHORIZE_PATH = "/oauth2/authorize";

    private FrontendOrigin() {}

    public static String resolvePostLoginRedirect(String frontendUrl, String target) {
        if (target != null) {
            try {
                URI uri = new URI(target);
                if (!hasUnsafeComponents(uri) && AUTHORIZE_PATH.equals(uri.getPath())) {
                    String rawQuery = uri.getRawQuery();
                    return AUTHORIZE_PATH + (rawQuery != null ? "?" + rawQuery : "");
                }
            } catch (URISyntaxException ignored) {
                // cai na validação padrão
            }
        }
        return acceptsRedirect(frontendUrl, target) ? target : frontendUrl;
    }

    public static boolean acceptsHeader(String frontendUrl, String origin, String referer) {
        String header = origin != null ? origin : referer;
        if (header == null) return false;
        try {
            URI uri = new URI(header);
            if (!sameOrigin(uri, new URI(frontendUrl))) return false;
            return origin == null || ((uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                    && uri.getRawQuery() == null && uri.getRawFragment() == null);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    public static boolean acceptsRedirect(String frontendUrl, String target) {
        if (target == null) return false;
        try {
            URI uri = new URI(target);
            if (hasUnsafeComponents(uri)) {
                return false;
            }
            if (target.startsWith("/") && !target.startsWith("//")) {
                return !uri.getPath().startsWith("//");
            }
            return sameOrigin(uri, new URI(frontendUrl));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean hasUnsafeComponents(URI uri) {
        return uri.isOpaque()
                || uri.getRawUserInfo() != null
                || hasBackslash(uri.getRawAuthority())
                || hasBackslash(uri.getPath())
                || hasBackslash(uri.getQuery())
                || hasBackslash(uri.getFragment())
                || hasControl(uri.getPath())
                || hasControl(uri.getQuery())
                || hasControl(uri.getFragment());
    }

    private static boolean hasBackslash(String component) {
        if (component == null) {
            return false;
        }
        if (component.indexOf('\\') >= 0) {
            return true;
        }
        String current = component;
        while (current.indexOf('%') >= 0) {
            String decoded = decodePercentLayer(current);
            if (decoded.equals(current)) {
                return false;
            }
            if (decoded.indexOf('\\') >= 0) {
                return true;
            }
            current = decoded;
        }
        return false;
    }

    private static String decodePercentLayer(String value) {
        int len = value.length();
        StringBuilder sb = null;
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < len) {
                int hi = Character.digit(value.charAt(i + 1), 16);
                int lo = Character.digit(value.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    if (sb == null) {
                        sb = new StringBuilder(len);
                        sb.append(value, 0, i);
                    }
                    sb.append((char) ((hi << 4) | lo));
                    i += 2;
                    continue;
                }
            }
            if (sb != null) {
                sb.append(c);
            }
        }
        return sb != null ? sb.toString() : value;
    }

    private static boolean hasControl(String component) {
        return component != null && component.chars().anyMatch(Character::isISOControl);
    }

    private static boolean sameOrigin(URI uri, URI frontend) {
        String scheme = uri.getScheme();
        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && scheme.equalsIgnoreCase(frontend.getScheme())
                && uri.getRawUserInfo() == null && uri.getHost() != null
                && !uri.getRawAuthority().endsWith(":")
                && uri.getHost().equalsIgnoreCase(frontend.getHost())
                && effectivePort(uri) == effectivePort(frontend);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
