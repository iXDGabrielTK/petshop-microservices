/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.config;

import inv.shared.outbox.scheduler.OutboxProcessor;
import inv.shared.outbox.scheduler.OutboxScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("SchedulingConfig — Testes Unitários de Condicionais de Agendamento")
class SchedulingConfigTest {

    private final ApplicationContextRunner schedulingRunner =
            new ApplicationContextRunner().withUserConfiguration(SchedulingConfig.class);

    private final ApplicationContextRunner schedulerRunner =
            new ApplicationContextRunner()
                    .withBean(OutboxProcessor.class, () -> mock(OutboxProcessor.class))
                    .withUserConfiguration(OutboxScheduler.class);

    @Test
    @DisplayName("DEVE ativar SchedulingConfig por padrão quando inv.scheduling.enabled estiver ausente")
    void deveAtivarSchedulingPorPadrao() {
        schedulingRunner.run(context -> {
            assertThat(context).hasSingleBean(SchedulingConfig.class);
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }

    @Test
    @DisplayName("DEVE ativar SchedulingConfig quando inv.scheduling.enabled=true")
    void deveAtivarSchedulingQuandoPropriedadeTrue() {
        schedulingRunner
                .withPropertyValues("inv.scheduling.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(SchedulingConfig.class);
                    assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
                });
    }

    @Test
    @DisplayName("DEVE desativar SchedulingConfig quando inv.scheduling.enabled=false")
    void deveDesativarSchedulingQuandoPropriedadeFalse() {
        schedulingRunner
                .withPropertyValues("inv.scheduling.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SchedulingConfig.class);
                    assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                });
    }

    @Test
    @DisplayName("DEVE registrar OutboxScheduler por padrão e omitir quando outbox.scheduler.enabled=false")
    void deveCondicionarOutboxSchedulerPorPropriedade() {
        schedulerRunner.run(context -> assertThat(context).hasSingleBean(OutboxScheduler.class));

        schedulerRunner
                .withPropertyValues("outbox.scheduler.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OutboxScheduler.class));
    }
}
