/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;

final class ErrorProblemDetails {

    private ErrorProblemDetails() {
    }

    static ProblemDetail internalServerError() {
        return buildProblemDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Ocorreu um erro interno inesperado.",
                "Erro Interno do Servidor",
                "internal-server-error"
        );
    }

    static ProblemDetail dataIntegrityViolation() {
        return buildProblemDetail(
                HttpStatus.CONFLICT,
                "Os dados informados conflitam com os registros existentes.",
                "Conflito de Integridade de Dados",
                "data-integrity-violation"
        );
    }

    static ProblemDetail buildProblemDetail(HttpStatus status, String detail, String title, String typeSuffix) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        if (typeSuffix != null) {
            problem.setType(URI.create("https://petshop.com/errors/" + typeSuffix));
        }
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
