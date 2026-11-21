/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.checkout.r15fixture;

import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.persistence.VendaRepository;

public class R15EscritaAntesDoLockFixture {

    private final VendaRepository vendaRepository;

    public R15EscritaAntesDoLockFixture(VendaRepository vendaRepository) {
        this.vendaRepository = vendaRepository;
    }

    public void executar(Long vendaId, Venda venda) {
        venda.cancelar();
        vendaRepository.findByIdWithLock(vendaId);
    }
}
