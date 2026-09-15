package inv.inventory.infrastructure.web.dto;

import inv.inventory.domain.model.UnidadeMedida;

import java.math.BigDecimal;

public record ProdutoResponse(
        Long id,
        String codigoBarras,
        String nome,
        UnidadeMedida unidadeMedida,
        BigDecimal quantidadeEstoque,
        BigDecimal precoVenda
) {}
