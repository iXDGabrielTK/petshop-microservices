package inv.architect;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Arrays;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Regras de fronteira entre Bounded Contexts do inv-service.
 * <p>
 * DEBT-ARCH-001 (E6) e DEBT-ARCH-002 (E5): nenhuma regra usa FreezingArchRule; o archunit_store foi removido.
 * <p>
 * Classes de teste são excluídas da análise: fixtures de integração (pacote inv.it) podem montar cenários
 * cruzando contextos sem que isso represente acoplamento de produção (DEBT-ARCH-001 F2 / DEBT-ARCH-002 F8).
 */
@AnalyzeClasses(packages = "inv", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArquiteturaTest {

    private static final String[] CONTEXTOS = {"checkout", "finance", "inventory"};

    // R1 — Checkout NÃO deve acessar Finance diretamente
    @ArchTest
    static final ArchRule checkoutNaoDeveDependerDeFinance = noClasses()
            .that().resideInAPackage("..checkout..")
            .should().dependOnClassesThat().resideInAPackage("..finance..")
            .as("Checkout não deve depender diretamente de Finance");

    // R2 — Finance NÃO deve acessar Checkout
    @ArchTest
    static final ArchRule financeNaoDeveDependerDeCheckout = noClasses()
            .that().resideInAPackage("..finance..")
            .should().dependOnClassesThat().resideInAPackage("..checkout..")
            .as("Finance não deve depender diretamente de Checkout");

    // R3 — Finance NÃO deve acessar Inventory
    @ArchTest
    static final ArchRule financeNaoDeveDependerDeInventory = noClasses()
            .that().resideInAPackage("..finance..")
            .should().dependOnClassesThat().resideInAPackage("..inventory..")
            .as("Finance não deve depender diretamente de Inventory");

    // R4 — Sem ciclos entre módulos de topo
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

    // R6 — Dashboard só enxerga *.api dos contextos
    @ArchTest
    static final ArchRule dashboardSoConsomeApis = noClasses()
            .that().resideInAPackage("inv.dashboard..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "inv.checkout.domain..", "inv.checkout.usecase..", "inv.checkout.infrastructure..",
                    "inv.finance.domain..",  "inv.finance.usecase..",  "inv.finance.infrastructure..",
                    "inv.inventory.domain..","inv.inventory.usecase..","inv.inventory.infrastructure..")
            .as("Dashboard deve depender apenas das APIs públicas (*.api) dos Bounded Contexts");

    // R7 — Repositórios Spring Data só têm dependentes no próprio contexto
    // (DEBT-ARCH-002 E5: sem catraca; F1: dependência de qualquer tipo, não só acesso a membro declarado)
    @ArchTest
    static final ArchRule repositoriosConfinadosAoContexto =
            CompositeArchRule.of(Arrays.stream(CONTEXTOS).<ArchRule>map(ctx ->
                    classes().that().areAssignableTo(org.springframework.data.repository.Repository.class)
                            .and().resideInAPackage("inv." + ctx + "..")
                            .should().onlyHaveDependentClassesThat().resideInAPackage("inv." + ctx + "..")
                            .as("Repositórios de " + ctx + " são internos ao contexto")
            ).toList());

    // R8 — Entidades JPA não atravessam fronteiras
    // (DEBT-ARCH-002 E5: sem catraca; captura campos @ManyToOne, parâmetros e retornos, além de chamadas)
    @ArchTest
    static final ArchRule entidadesConfinadasAoContexto =
            CompositeArchRule.of(Arrays.stream(CONTEXTOS).<ArchRule>map(ctx ->
                    classes().that().areAnnotatedWith(jakarta.persistence.Entity.class)
                            .and().resideInAPackage("inv." + ctx + "..")
                            .should().onlyHaveDependentClassesThat().resideInAPackage("inv." + ctx + "..")
                            .as("Entidades JPA de " + ctx + " são internas ao contexto")
            ).toList());

    // R9 — Pureza da Published Language
    @ArchTest
    static final ArchRule apisSaoContratosPuros = classes()
            .that().resideInAPackage("inv..api..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "inv..api..", "java..", "common..", "org.springframework.modulith..")
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

    // R13 — Checkout só enxerga a Published Language de Inventory (whitelist: cobre subpacotes futuros)
    @ArchTest
    static final ArchRule checkoutNaoDeveDependerDeInventoryInterno = classes()
            .that().resideInAPackage("inv.checkout..")
            .should().onlyDependOnClassesThat(
                    resideOutsideOfPackage("inv.inventory..").or(resideInAPackage("inv.inventory.api..")))
            .as("Checkout só pode depender de Inventory através de inv.inventory.api");

    // R14 — Implementações de contratos *.api não são públicas (EstoqueService, Jpa*/Jdbc*QueryService...)
    @ArchTest
    static final ArchRule implementacoesDeApiNaoPublicas = classes()
            .that().implement(resideInAPackage("inv..api.."))
            .and().resideOutsideOfPackage("inv..api..")
            .should().notBePublic()
            .as("Implementações de contratos da Published Language não devem ser públicas (forçam injeção pela interface)");

    private static ArchCondition<JavaClass> beInterfacesRecordsOrEnums() {
        return new ArchCondition<>("ser interfaces, records ou enums") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean valid = item.isInterface() || item.isRecord() || item.isEnum();
                if (!valid) {
                    events.add(SimpleConditionEvent.violated(item, item.getName() + " não é interface, record ou enum"));
                }
            }
        };
    }
}
