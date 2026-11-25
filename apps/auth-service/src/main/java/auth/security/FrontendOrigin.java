/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.security;

import java.net.URI;
import java.net.URISyntaxException;

public final class FrontendOrigin {

    private FrontendOrigin() {}

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
            if (target.startsWith("/") && !target.startsWith("//")) {
                return true;
            }
            return sameOrigin(uri, new URI(frontendUrl));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean sameOrigin(URI uri, URI frontend) {
        String scheme = uri.getScheme();
        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && scheme.equalsIgnoreCase(frontend.getScheme())
                && uri.getRawUserInfo() == null && uri.getHost() != null
                && uri.getHost().equalsIgnoreCase(frontend.getHost())
                && effectivePort(uri) == effectivePort(frontend);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
