/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.dto.response;

import java.util.Set;

public record UserResponse(
        Long id,
        String name,
        String email,
        Set<String> roles,
        String theme
) {}
