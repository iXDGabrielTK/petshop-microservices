/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */

@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"inventory::api", "shared"}
)
package inv.checkout;
