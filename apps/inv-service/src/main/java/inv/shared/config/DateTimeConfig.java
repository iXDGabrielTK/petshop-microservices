package inv.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Configuração global de relógio e timezone para o inv-service (F7).
 * Garante padronização em America/Sao_Paulo e permite injeção de Clock para testes determinísticos.
 */
@Configuration
public class DateTimeConfig {

    public static final ZoneId ZONE_SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    @Bean
    public Clock clock() {
        return Clock.system(ZONE_SAO_PAULO);
    }
}
