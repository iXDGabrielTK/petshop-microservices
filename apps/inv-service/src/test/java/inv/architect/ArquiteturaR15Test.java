/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.architect;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import inv.checkout.r15fixture.R15DoisFluxosBaseFixture;
import inv.checkout.r15fixture.R15EscritaAntesDoLockFixture;
import inv.checkout.r15fixture.R15FluxosBaseFixture;
import inv.checkout.r15fixture.R15LockAntesDaEscritaFixture;
import inv.checkout.r15fixture.R15LockAntesDeSaveAndFlushEMutacaoFixture;
import inv.checkout.r15fixture.R15SaveAndFlushAntesDoLockFixture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArquiteturaR15Test {

    private static final Pattern PADRAO_ESCRITA_PRE_LOCK = Pattern.compile(
            "realiza escrita em Venda na linha (\\d+) antes de VendaRepository\\.findByIdWithLock na linha (\\d+)");

    @Test
    void deveSatisfazerPisoMinimoComFluxosBaseIsolados() {
        JavaClasses classes = new ClassFileImporter().importClasses(R15FluxosBaseFixture.class);

        EvaluationResult resultado = ArquiteturaTest.escritasEmVendaDevemUsarFindByIdWithLock.evaluate(classes);

        assertFalse(resultado.hasViolation());
        assertTrue(resultado.getFailureReport().getDetails().isEmpty());
        assertDoesNotThrow(() -> ArquiteturaTest.escritasEmVendaDevemUsarFindByIdWithLock.check(classes));
    }

    @Test
    void deveAceitarFluxoEquivalenteComLockAntesDaEscrita() {
        JavaClasses classes = new ClassFileImporter()
                .importClasses(R15DoisFluxosBaseFixture.class, R15LockAntesDaEscritaFixture.class);

        EvaluationResult resultado = ArquiteturaTest.escritasEmVendaDevemUsarFindByIdWithLock.evaluate(classes);

        assertFalse(resultado.hasViolation(), () -> String.join(System.lineSeparator(), resultado.getFailureReport().getDetails()));
        assertTrue(resultado.getFailureReport().getDetails().isEmpty());
        assertDoesNotThrow(() -> ArquiteturaTest.escritasEmVendaDevemUsarFindByIdWithLock.check(classes));
    }

    @Test
    void deveRejeitarFluxoEquivalenteComEscritaAntesDoLock() {
        JavaClasses classes = new ClassFileImporter()
                .importClasses(R15FluxosBaseFixture.class, R15EscritaAntesDoLockFixture.class);

        EvaluationResult resultado = ArquiteturaTest.escritasEmVendaDevemUsarFindByIdWithLock.evaluate(classes);

        assertTrue(resultado.hasViolation());
        List<String> detalhes = resultado.getFailureReport().getDetails();
        assertEquals(1, detalhes.size());

        String violacao = detalhes.getFirst();
        assertTrue(violacao.contains(
                "inv.checkout.r15fixture.R15EscritaAntesDoLockFixture.executar(java.lang.Long, inv.checkout.domain.model.Venda)"));
        Matcher matcher = PADRAO_ESCRITA_PRE_LOCK.matcher(violacao);
        assertTrue(matcher.find(), () -> "Mensagem inesperada da R15: " + violacao);
        int linhaEscrita = Integer.parseInt(matcher.group(1));
        int linhaLock = Integer.parseInt(matcher.group(2));
        assertTrue(linhaEscrita < linhaLock, () -> "Esperava linhaEscrita < linhaLock em: " + violacao);

        assertFalse(violacao.contains("pelo menos 3 fluxos"));
        assertFalse(violacao.contains("sem adquirir lock pessimista"));

        AssertionError erro = assertThrows(
                AssertionError.class,
                () -> ArquiteturaTest.escritasEmVendaDevemUsarFindByIdWithLock.check(classes));
        assertTrue(erro.getMessage().contains("R15EscritaAntesDoLockFixture.executar"));
        assertTrue(PADRAO_ESCRITA_PRE_LOCK.matcher(erro.getMessage()).find());
    }

    @Test
    void deveRejeitarSaveAndFlushAntesDoLockMesmoComMutacaoPosterior() {
        JavaClasses classes = new ClassFileImporter()
                .importClasses(R15FluxosBaseFixture.class, R15SaveAndFlushAntesDoLockFixture.class);

        EvaluationResult resultado = ArquiteturaTest.escritasEmVendaDevemUsarFindByIdWithLock.evaluate(classes);

        assertTrue(resultado.hasViolation());
        List<String> detalhes = resultado.getFailureReport().getDetails();
        assertEquals(1, detalhes.size());

        String violacao = detalhes.getFirst();
        assertTrue(violacao.contains(
                "inv.checkout.r15fixture.R15SaveAndFlushAntesDoLockFixture.executar(java.lang.Long, inv.checkout.domain.model.Venda)"));
        Matcher matcher = PADRAO_ESCRITA_PRE_LOCK.matcher(violacao);
        assertTrue(matcher.find(), () -> "Mensagem inesperada da R15: " + violacao);
        int linhaEscrita = Integer.parseInt(matcher.group(1));
        int linhaLock = Integer.parseInt(matcher.group(2));
        assertTrue(linhaEscrita < linhaLock, () -> "Esperava linhaEscrita < linhaLock em: " + violacao);

        assertFalse(violacao.contains("pelo menos 3 fluxos"));
        assertFalse(violacao.contains("sem adquirir lock pessimista"));

        AssertionError erro = assertThrows(
                AssertionError.class,
                () -> ArquiteturaTest.escritasEmVendaDevemUsarFindByIdWithLock.check(classes));
        assertTrue(erro.getMessage().contains("R15SaveAndFlushAntesDoLockFixture.executar"));
        assertTrue(PADRAO_ESCRITA_PRE_LOCK.matcher(erro.getMessage()).find());
    }

    @Test
    void deveAceitarLockAntesDeSaveAndFlushEMutacao() {
        JavaClasses classes = new ClassFileImporter()
                .importClasses(R15DoisFluxosBaseFixture.class, R15LockAntesDeSaveAndFlushEMutacaoFixture.class);

        EvaluationResult resultado = ArquiteturaTest.escritasEmVendaDevemUsarFindByIdWithLock.evaluate(classes);

        assertFalse(resultado.hasViolation(), () -> String.join(System.lineSeparator(), resultado.getFailureReport().getDetails()));
        assertTrue(resultado.getFailureReport().getDetails().isEmpty());
        assertDoesNotThrow(() -> ArquiteturaTest.escritasEmVendaDevemUsarFindByIdWithLock.check(classes));
    }
}
