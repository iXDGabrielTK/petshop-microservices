/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox.web;

import inv.shared.outbox.service.OutboxReplayService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/outbox")
@PreAuthorize("hasRole('ADMIN')")
public class OutboxAdminController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final OutboxReplayService outboxReplayService;

    public OutboxAdminController(OutboxReplayService outboxReplayService) {
        this.outboxReplayService = outboxReplayService;
    }

    @GetMapping("/quarantined")
    public ResponseEntity<Page<OutboxRecordView>> listarQuarentena(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return ResponseEntity.ok(outboxReplayService.listQuarantined(PageRequest.of(safePage, safeSize)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OutboxRecordView> buscarPorId(@PathVariable Long id) {
        return ResponseEntity.ok(outboxReplayService.findById(id));
    }

    @PostMapping("/{id}/replay")
    public ResponseEntity<OutboxReplayResponse> replay(@PathVariable Long id) {
        return ResponseEntity.ok(outboxReplayService.replay(id));
    }
}
