package inv.finance.infrastructure.web.controller;

import inv.finance.infrastructure.web.dto.DashboardStats;
import inv.finance.usecase.DashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/stats")
    public ResponseEntity<DashboardStats> getStats() {

        DashboardStats stats = dashboardService.getStats();

        return ResponseEntity.ok(stats);
    }
}