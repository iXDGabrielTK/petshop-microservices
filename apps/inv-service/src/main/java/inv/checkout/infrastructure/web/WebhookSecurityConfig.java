/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.checkout.infrastructure.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import java.time.Clock;

@Configuration
public class WebhookSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain webhookSecurityFilterChain(
            HttpSecurity http,
            @Value("${pagamento.webhook.hmac-secret:}") String hmacSecret,
            @Value("${pagamento.webhook.max-skew-seconds:300}") long maxSkewSeconds,
            ObjectProvider<Clock> clockProvider) throws Exception {

        Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
        WebhookHmacFilter webhookHmacFilter = new WebhookHmacFilter(hmacSecret, maxSkewSeconds, clock);

        http
                .securityMatcher("/pagamentos/webhook/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(webhookHmacFilter, AnonymousAuthenticationFilter.class);

        return http.build();
    }
}
