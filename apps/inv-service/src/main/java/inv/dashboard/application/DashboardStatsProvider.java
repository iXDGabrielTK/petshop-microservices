package inv.dashboard.application;

import inv.dashboard.infrastructure.web.dto.DashboardStats;

/**
 * Provedor do contrato do Dashboard, com seleção dinâmica de engine
 * entre os modos 'legacy', 'shadow' e 'modular' (DEBT-ARCH-001 / Etapa E4).
 */
public interface DashboardStatsProvider {

    /**
     * Obtém as estatísticas do dashboard conforme o modo configurado em 'dashboard.engine'.
     *
     * @return estatísticas consolidadas para visualização
     */
    DashboardStats getStats();
}
