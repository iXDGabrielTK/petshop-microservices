/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.dto.request;

import auth.model.Theme;
import jakarta.validation.constraints.NotNull;

public record UserSettingsRequest(
        @NotNull
        Theme theme
) {}
