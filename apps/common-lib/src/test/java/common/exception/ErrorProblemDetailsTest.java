/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorProblemDetailsTest {

    @Test
    void deveConstruirErroInternoComDetalheGenerico() {
        ProblemDetail problem = ErrorProblemDetails.internalServerError();

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getDetail()).isEqualTo("Ocorreu um erro interno inesperado.");
        assertThat(problem.getType()).hasToString("https://petshop.com/errors/internal-server-error");
        assertThat(problem.getProperties()).containsOnlyKeys("timestamp");
    }

    @Test
    void deveConstruirConflitoDeIntegridadeComDetalheGenerico() {
        ProblemDetail problem = ErrorProblemDetails.dataIntegrityViolation();

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getDetail()).isEqualTo("Os dados informados conflitam com os registros existentes.");
        assertThat(problem.getType()).hasToString("https://petshop.com/errors/data-integrity-violation");
        assertThat(problem.getProperties()).containsOnlyKeys("timestamp");
    }
}
