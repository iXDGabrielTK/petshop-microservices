package inv.architect;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;

/**
 * Regras de fronteira entre Bounded Contexts do inv-service.
 * <p>
 * Classes de teste são excluídas da análise: fixtures de integração podem montar cenários
 * cruzando contextos sem que isso represente acoplamento de produção (DEBT-ARCH-001 / achado F2).
 */
@AnalyzeClasses(packages = "inv", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArquiteturaTest {

    // Regra 1: Checkout NÃO deve acessar Finance diretamente (Ativa e passando como rede de segurança no CI)
    @ArchTest
    static final ArchRule checkoutNaoDeveDependerDeFinance = noClasses()
            .that().resideInAPackage("..checkout..")
            .should().dependOnClassesThat().resideInAPackage("..finance..")
            .as("Checkout não deve depender diretamente de Finance");

    // Regra 2: Finance NÃO deve acessar Checkout.
    // DEBT-ARCH-001 (E0): catraca — violações conhecidas ficam no archunit_store; qualquer violação NOVA quebra o build.
    @ArchTest
    static final ArchRule financeNaoDeveDependerDeCheckout = freeze(noClasses()
            .that().resideInAPackage("..finance..")
            .should().dependOnClassesThat().resideInAPackage("..checkout..")
            .as("Finance não deve depender diretamente de Checkout"));

    // Regra 3: Finance NÃO deve acessar Inventory.
    // DEBT-ARCH-001 (E0): catraca — violações conhecidas ficam no archunit_store; qualquer violação NOVA quebra o build.
    @ArchTest
    static final ArchRule financeNaoDeveDependerDeInventory = freeze(noClasses()
            .that().resideInAPackage("..finance..")
            .should().dependOnClassesThat().resideInAPackage("..inventory..")
            .as("Finance não deve depender diretamente de Inventory"));

    // Regra 4: Sem ciclos entre módulos de topo (desbloqueada pela resolução de F3 na Etapa 1)
    @ArchTest
    static final ArchRule semCiclosEntreModulos = slices()
            .matching("inv.(*)..")
            .should().beFreeOfCycles()
            .as("Não deve haver ciclos de dependência entre os módulos de topo de inv");
}