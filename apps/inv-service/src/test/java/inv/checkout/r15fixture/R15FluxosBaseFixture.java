/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.checkout.r15fixture;

import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.persistence.VendaRepository;

public class R15FluxosBaseFixture {

    private final VendaRepository vendaRepository;

    public R15FluxosBaseFixture(VendaRepository vendaRepository) {
        this.vendaRepository = vendaRepository;
    }

    public void fluxoBaseUm(Long vendaId, Venda venda) {
        vendaRepository.findByIdWithLock(vendaId);
        venda.cancelar();
    }

    public void fluxoBaseDois(Long vendaId, Venda venda) {
        vendaRepository.findByIdWithLock(vendaId);
        venda.cancelar();
    }

    public void fluxoBaseTres(Long vendaId, Venda venda) {
        vendaRepository.findByIdWithLock(vendaId);
        venda.cancelar();
    }
}
