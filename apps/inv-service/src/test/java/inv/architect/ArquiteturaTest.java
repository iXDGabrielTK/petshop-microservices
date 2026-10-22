package inv.architect;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;

import java.util.Arrays;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;

/**
 * Regras de fronteira entre Bounded Contexts do inv-service (Pilar 5 — DEBT-ARCH-001 E6).
 * <p>
 * Classes de teste são excluídas da análise: fixtures de integração podem montar cenários
 * cruzando contextos sem que isso represente acoplamento de produção (DEBT-ARCH-001 / achado F2).
 */
@AnalyzeClasses(packages = "inv", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArquiteturaTest {

    private static final String[] CONTEXTOS = {"checkout", "finance", "inventory"};

    // Regra 1: Checkout NÃO deve acessar Finance diretamente
    @ArchTest
    static final ArchRule checkoutNaoDeveDependerDeFinance = noClasses()
            .that().resideInAPackage("..checkout..")
            .should().dependOnClassesThat().resideInAPackage("..finance..")
            .as("Checkout não deve depender diretamente de Finance");

    // Regra 2: Finance NÃO deve acessar Checkout (DEBT-ARCH-001 E6: catraca removida definitivamente)
    @ArchTest
    static final ArchRule financeNaoDeveDependerDeCheckout = noClasses()
            .that().resideInAPackage("..finance..")
            .should().dependOnClassesThat().resideInAPackage("..checkout..")
            .as("Finance não deve depender diretamente de Checkout");

    // Regra 3: Finance NÃO deve acessar Inventory (DEBT-ARCH-001 E6: catraca removida definitivamente)
    @ArchTest
    static final ArchRule financeNaoDeveDependerDeInventory = noClasses()
            .that().resideInAPackage("..finance..")
            .should().dependOnClassesThat().resideInAPackage("..inventory..")
            .as("Finance não deve depender diretamente de Inventory");

    // R4 — Sem ciclos entre módulos de topo (requer F3 resolvido)
    @ArchTest
    static final ArchRule semCiclosEntreModulos = slices()
            .matching("inv.(*)..")
            .should().beFreeOfCycles()
            .as("Não deve haver ciclos de dependência entre os módulos de topo de inv");

    // R5 — Ninguém depende do módulo de composição inv.dashboard
    @ArchTest
    static final ArchRule ninguemDependeDoDashboard = noClasses()
            .that().resideOutsideOfPackage("inv.dashboard..")
            .should().dependOnClassesThat().resideInAPackage("inv.dashboard..")
            .as("Nenhum módulo fora de inv.dashboard deve depender de classes de inv.dashboard");

    // R6 — Dashboard só enxerga *.api dos contextos (nunca domain/usecase/infrastructure alheios)
    @ArchTest
    static final ArchRule dashboardSoConsomeApis = noClasses()
            .that().resideInAPackage("inv.dashboard..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "inv.checkout.domain..", "inv.checkout.usecase..", "inv.checkout.infrastructure..",
                    "inv.finance.domain..",  "inv.finance.usecase..",  "inv.finance.infrastructure..",
                    "inv.inventory.domain..","inv.inventory.usecase..","inv.inventory.infrastructure..")
            .as("Dashboard deve depender apenas das APIs públicas (*.api) dos Bounded Contexts");

    // R7 — Repositórios Spring Data só são acessados dentro do próprio contexto
    // (inventory embrulhado em freeze devido ao achado F8 / DEBT-ARCH-002: Checkout acessa ProdutoRepository)
    @ArchTest
    static final ArchRule repositoriosConfinadosAoContexto =
            CompositeArchRule.of(Arrays.stream(CONTEXTOS).<ArchRule>map(ctx -> "inventory".equals(ctx)
                    ? freeze(classes().that().areAssignableTo(org.springframework.data.repository.Repository.class)
                            .and().resideInAPackage("inv.inventory..")
                            .should().onlyBeAccessed().byAnyPackage("inv.inventory..")
                            .as("Repositórios de inventory são internos ao contexto"))          // idêntico ao store
                    : classes().that().areAssignableTo(org.springframework.data.repository.Repository.class)
                            .and().resideInAPackage("inv." + ctx + "..")
                            .should().onlyHaveDependentClassesThat().resideInAPackage("inv." + ctx + "..")
                            .as("Repositórios de " + ctx + " são internos ao contexto")).toList());

    // R8 — Entidades JPA não atravessam fronteiras
    // (inventory embrulhado em freeze devido ao achado F8 / DEBT-ARCH-002: Checkout acessa Produto)
    @ArchTest
    static final ArchRule entidadesConfinadasAoContexto =
            CompositeArchRule.of(Arrays.stream(CONTEXTOS).<ArchRule>map(ctx -> "inventory".equals(ctx)
                    ? freeze(classes().that().areAnnotatedWith(jakarta.persistence.Entity.class)
                            .and().resideInAPackage("inv.inventory..")
                            .should().onlyBeAccessed().byAnyPackage("inv.inventory..")
                            .as("Entidades JPA de inventory são internas ao contexto"))         // idêntico ao store
                    : classes().that().areAnnotatedWith(jakarta.persistence.Entity.class)
                            .and().resideInAPackage("inv." + ctx + "..")
                            .should().onlyHaveDependentClassesThat().resideInAPackage("inv." + ctx + "..")
                            .as("Entidades JPA de " + ctx + " são internas ao contexto")).toList());

    // R9 — Pureza da Published Language
    @ArchTest
    static final ArchRule apisSaoContratosPuros = classes()
            .that().resideInAPackage("inv..api..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("inv..api..", "java..", "common..")
            .andShould(beInterfacesRecordsOrEnums())
            .as("APIs públicas devem ser contratos puros dependendo apenas de java, common ou outras APIs e ser interfaces, records ou enums");

    // R10 — Implementações de query não são públicas (forçam injeção pela interface)
    @ArchTest
    static final ArchRule implementacoesDeQueryNaoPublicas = classes()
            .that().resideInAPackage("inv..infrastructure.query..")
            .should().notBePublic()
            .as("Implementações de query não devem ser públicas (forçam injeção pelas interfaces da API)");

    // R11 — Clientes de infraestrutura (Redis/AMQP) só em ..infrastructure.., config ou outbox
    @ArchTest
    static final ArchRule infraSoEmInfraestrutura = noClasses()
            .that().resideOutsideOfPackage("..infrastructure..")
            .and().resideOutsideOfPackage("inv.shared.config..")
            .and().resideOutsideOfPackage("inv.shared.outbox..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.data.redis..", "org.springframework.amqp..")
            .as("Clientes de infraestrutura (Redis/AMQP) só devem ser acessados na camada de infraestrutura");

    // R12 — Shared Kernel não conhece contextos
    @ArchTest
    static final ArchRule sharedNaoConheceContextos = noClasses()
            .that().resideInAPackage("inv.shared..")
            .should().dependOnClassesThat().resideInAnyPackage("inv.checkout..", "inv.finance..", "inv.inventory..", "inv.dashboard..")
            .as("Shared Kernel não deve depender de nenhum Bounded Context");

    private static com.tngtech.archunit.lang.ArchCondition<com.tngtech.archunit.core.domain.JavaClass> beInterfacesRecordsOrEnums() {
        return new com.tngtech.archunit.lang.ArchCondition<>("ser interfaces, records ou enums") {
            @Override
            public void check(com.tngtech.archunit.core.domain.JavaClass item, com.tngtech.archunit.lang.ConditionEvents events) {
                boolean valid = item.isInterface() || item.isRecord() || item.isEnum();
                if (!valid) {
                    events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(item,
                            item.getName() + " não é interface, record ou enum"));
                }
            }
        };
    }
}
