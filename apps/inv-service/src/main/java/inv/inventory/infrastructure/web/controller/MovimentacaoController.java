/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.infrastructure.web.controller;

import common.exception.BusinessException;
import inv.inventory.infrastructure.web.dto.MovimentacaoResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.Set;
import inv.inventory.infrastructure.persistence.MovimentacaoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/movimentacoes")
public class MovimentacaoController {

    private final MovimentacaoRepository repository;

    public MovimentacaoController(MovimentacaoRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<Page<MovimentacaoResponse>> listar(
            @PageableDefault(size = 20, sort = "dataHora", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Sort sort = pageable.getSort();
        for (Sort.Order order : sort) {
            if (!Set.of("id", "dataHora", "tipo", "quantidade").contains(order.getProperty())
                    || order.isIgnoreCase()) {
                throw new BusinessException("Ordenação de movimentações inválida: " + order.getProperty());
            }
        }
        if (sort.getOrderFor("id") == null) {
            sort = sort.and(Sort.by(Sort.Order.asc("id")));
        }
        Pageable normalizado = PageRequest.of(Math.max(page, 0), size < 1 ? 20 : Math.min(size, 50), sort);
        return ResponseEntity.ok(repository.listarResumo(normalizado));
    }
}
