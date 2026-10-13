package inv.dashboard.infrastructure.web;

import inv.dashboard.application.DashboardStatsProvider;
import inv.dashboard.infrastructure.web.dto.DashboardStats;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Controller REST do módulo de composição Dashboard (BFF interno somente-leitura).
 * Expõe o endpoint público GET /dashboard/stats delegando ao {@link DashboardStatsProvider}.
 */
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    private final DashboardStatsProvider dashboardStatsProvider;

    public DashboardController(DashboardStatsProvider dashboardStatsProvider) {
        this.dashboardStatsProvider = Objects.requireNonNull(dashboardStatsProvider, "dashboardStatsProvider");
    }

    @GetMapping("/stats")
    public ResponseEntity<DashboardStats> getStats() {
        DashboardStats stats = dashboardStatsProvider.getStats();
        return ResponseEntity.ok(stats);
    }
}
