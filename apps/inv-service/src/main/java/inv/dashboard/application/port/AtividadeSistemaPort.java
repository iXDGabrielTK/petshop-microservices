/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.dashboard.application.port;

import java.time.Duration;
import java.util.OptionalLong;

/**
 * Porta de saída de propriedade do consumidor (DIP).
 * Não expõe tipos ou detalhes de infraestrutura (Redis) para a camada de aplicação.
 */
public interface AtividadeSistemaPort {

    /**
     * Conta a quantidade de eventos de atividade registrados na janela informada.
     *
     * @param janela duração retroativa a partir do instante atual
     * @return quantidade de eventos de atividade na janela; vazio se a fonte estiver indisponível
     */
    OptionalLong contarEventosNaJanela(Duration janela);
}
