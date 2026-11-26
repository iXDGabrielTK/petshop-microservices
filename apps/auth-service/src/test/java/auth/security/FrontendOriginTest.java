/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FrontendOrigin — Testes Unitários de Validação de Redirect e Origem")
class FrontendOriginTest {

    private static final String FRONTEND = "http://localhost:5173";

    @ParameterizedTest(name = "Deve rejeitar barra invertida/codificação em path, query e fragment: {0}")
    @ValueSource(strings = {
            "/\\evil.com",
            "/%5Cevil.com",
            "/%5cevil.com",
            "/%255Cevil.com",
            "/%255cevil.com",
            "/%25255Cevil.com",
            "/conta?voltar=%5Cevil.com",
            "/conta?voltar=%5cevil.com",
            "/conta?voltar=%255Cevil.com",
            "/conta?voltar=%255cevil.com",
            "/conta?voltar=%25255Cevil.com",
            "/conta#%5Cevil.com",
            "/conta#%5cevil.com",
            "/conta#%255Cevil.com",
            "http://localhost:5173/app?next=%5Cevil.com",
            "http://localhost:5173/app?next=%5cevil.com",
            "http://localhost:5173/app?next=%255Cevil.com",
            "http://localhost:5173/app#%5Cevil.com",
            "http://localhost:5173/app#%5cevil.com",
            "http://localhost:5173/app#%255Cevil.com",
            "http://localhost:8081/oauth2/authorize?x=%5C",
            "http://localhost:8081/oauth2/authorize?x=%5c",
            "http://localhost:8081/oauth2/authorize?x=%255C",
            "http://localhost:8081/oauth2/authorize?x=%255c",
            "http://localhost:8081/oauth2/authorize?x=%25255C",
            "/oauth2/authorize?x=%5c",
            "/oauth2/authorize?x=%255C",
            "http://localhost:8081/oauth2/authorize?client_id=petshop-client#%5Cevil.com",
            "http://localhost:8081/oauth2/authorize?client_id=petshop-client#%255c",
            "http://evil.com%5c/oauth2/authorize?client_id=petshop-client"
    })
    void deveRejeitarBarraInvertidaEmQualquerCamadaOuComponente(String maliciousTarget) {
        assertThat(FrontendOrigin.acceptsRedirect(FRONTEND, maliciousTarget)).isFalse();
        assertThat(FrontendOrigin.resolvePostLoginRedirect(FRONTEND, maliciousTarget)).isEqualTo(FRONTEND);
    }

    @ParameterizedTest(name = "Deve rejeitar userInfo (@) em URL absoluta: {0}")
    @ValueSource(strings = {
            "http://usuario@localhost:5173/app",
            "http://usuario:senha@localhost:5173/app",
            "http://usuario@localhost:8081/oauth2/authorize?client_id=petshop-client",
            "http://evil.com@localhost:8081/oauth2/authorize?client_id=petshop-client"
    })
    void deveRejeitarUserInfoEmAcceptsRedirectEResolvePostLoginRedirect(String targetComUserInfo) {
        assertThat(FrontendOrigin.acceptsRedirect(FRONTEND, targetComUserInfo)).isFalse();
        assertThat(FrontendOrigin.resolvePostLoginRedirect(FRONTEND, targetComUserInfo)).isEqualTo(FRONTEND);
    }

    @Test
    @DisplayName("Deve aceitar @ dentro de query legítima e preservar rawQuery intacta sem re-encoding")
    void deveAceitarQueryLegitimaComCaracteresEspeciaisEPreservarRawQuery() {
        String relativo = "/conta?email=user@petshop.com&q=banho%20e%20tosa&tag=a+b&ok=100%25+ok&k=v";
        assertThat(FrontendOrigin.acceptsRedirect(FRONTEND, relativo)).isTrue();
        assertThat(FrontendOrigin.resolvePostLoginRedirect(FRONTEND, relativo)).isEqualTo(relativo);

        String authorize = "http://localhost:8081/oauth2/authorize?client_id=petshop-client&scope=pets%20read&state=a+b=c&redirect_uri=http%3A%2F%2Flocalhost%3A5173%2Fauthorized";
        assertThat(FrontendOrigin.resolvePostLoginRedirect(FRONTEND, authorize))
                .isEqualTo("/oauth2/authorize?client_id=petshop-client&scope=pets%20read&state=a+b=c&redirect_uri=http%3A%2F%2Flocalhost%3A5173%2Fauthorized");
    }

    @Test
    @DisplayName("Deve manter normalização relativa para /oauth2/authorize válido mesmo com host externo sem userInfo/backslash")
    void deveNormalizarAuthorizeValidoSemVazarHost() {
        assertThat(FrontendOrigin.resolvePostLoginRedirect(
                FRONTEND,
                "http://evil.com/oauth2/authorize?client_id=petshop-client&scope=pets%20read"))
                .isEqualTo("/oauth2/authorize?client_id=petshop-client&scope=pets%20read");
    }
}
