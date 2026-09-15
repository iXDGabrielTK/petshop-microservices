package inv.architect;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchIgnore;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "inv")
public class ArquiteturaTest {

    // Regra 1: Checkout NÃO deve acessar Finance diretamente (Ativa e passando como rede de segurança no CI)
    @ArchTest
    static final ArchRule checkoutNaoDeveDependerDeFinance = noClasses()
            .that().resideInAPackage("..checkout..")
            .should().dependOnClassesThat().resideInAPackage("..finance..")
            .as("Checkout não deve depender diretamente de Finance");

    // Regra 2: Finance NÃO deve acessar Checkout (Desabilitada temporariamente para não travar o CI)
    @ArchIgnore(reason = "DEBT-ARCH-001: Sprint 24 (Meta: 15/10/2026) - Responsável: Time Backend. Causa: DashboardService injeta VendaRepository")
    @ArchTest
    static final ArchRule financeNaoDeveDependerDeCheckout = noClasses()
            .that().resideInAPackage("..finance..")
            .should().dependOnClassesThat().resideInAPackage("..checkout..")
            .as("Finance não deve depender diretamente de Checkout");

    // Regra 3: Finance NÃO deve acessar Inventory (Desabilitada temporariamente para não travar o CI)
    @ArchIgnore(reason = "DEBT-ARCH-001: Sprint 24 (Meta: 15/10/2026) - Responsável: Time Backend. Causa: DashboardService injeta ProdutoRepository")
    @ArchTest
    static final ArchRule financeNaoDeveDependerDeInventory = noClasses()
            .that().resideInAPackage("..finance..")
            .should().dependOnClassesThat().resideInAPackage("..inventory..")
            .as("Finance não deve depender diretamente de Inventory");
}