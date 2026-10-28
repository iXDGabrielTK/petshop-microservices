package inv.dashboard.infrastructure.web;

import inv.dashboard.application.DashboardQueryService;
import inv.dashboard.application.DashboardSnapshot;
import inv.dashboard.infrastructure.web.dto.DashboardStats;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Controller REST do módulo de composição Dashboard (BFF interno somente-leitura).
 * Expõe o endpoint público GET /dashboard/stats orquestrando {@link DashboardQueryService}
 * e convertendo o snapshot para o DTO web via {@link DashboardStatsMapper}.
 */
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    private final DashboardQueryService queryService;
    private final DashboardStatsMapper mapper;

    public DashboardController(DashboardQueryService queryService, DashboardStatsMapper mapper) {
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @GetMapping("/stats")
    public ResponseEntity<DashboardStats> getStats() {
        DashboardSnapshot snapshot = queryService.consultar();
        return ResponseEntity.ok(mapper.toDto(snapshot));
    }
}

