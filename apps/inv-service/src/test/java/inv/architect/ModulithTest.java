package inv.architect;

import inv.InvServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Verificação de fronteiras modulares e geração de documentação C4 com Spring Modulith
 * (DEBT-ARCH-001 E7 / DEBT-ARCH-002 E6).
 */
class ModulithTest {

    private static final ApplicationModules MODULES = ApplicationModules.of(InvServiceApplication.class);

    @Test
    void deveVerificarEstruturaDosApplicationModulesSemViolacoes() {
        MODULES.verify();
    }

    @Test
    void deveGerarDocumentacaoC4DosModulos() {
        new Documenter(MODULES)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeModuleCanvases();
    }
}
