package inv.architect;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

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

    /**
     * R15 — Toda escrita/transição em Venda existente deve adquirir lock pessimista via VendaRepository.findByIdWithLock
     * (DEBT-ARCH-003 E4 / Gate 3: previne colisões otimistas no @Version e garante hierarquia de locks vendas -> produtos).
     *
     * <p><b>Convenção de Nomes e Limitações da Heurística R15:</b></p>
     * <ul>
     *   <li><b>Convenção de métodos de leitura:</b> A regra trata como mutador qualquer método invocado em {@code Venda}
     *       cujo nome não comece com os prefixos {@code get} ou {@code is} (exceto exceções explícitas {@code estaPaga},
     *       {@code equals}, {@code hashCode} e {@code toString}). Caso um método puramente de leitura futuro seja introduzido
     *       com outra nomenclatura (por exemplo, {@code temItens()} ou {@code possuiPendencias()}), ele seria apontado
     *       como violação se chamado sem lock, exigindo inclusão explícita em {@code isMetodoSomenteLeituraVenda}.</li>
     *   <li><b>Heurística de bytecode intra-procedural:</b> A verificação analisa a ordenação de chamadas por número de linha
     *       no escopo do próprio método chamador. A extração da aquisição de lock para um método auxiliar privado pode induzir
     *       falso positivo ou falso negativo.</li>
     *   <li><b>Rastreamento de argumentos:</b> A regra não inspeciona por fluxo de dados se o ID passado ao lock corresponde
     *       ao ID da instância de {@code Venda} que está sendo mutada.</li>
     *   <li><b>Ausência de teste sintético negativo:</b> A suíte não possui teste sintético negativo comprovando falha da regra
     *       sob violação intencional (lacuna conhecida, atuando como gate estático complementado por testes dinâmicos em
     *       {@code PagamentoConcorrenciaGatesIT}).</li>
     * </ul>
     */
    @ArchTest
    static final ArchRule escritasEmVendaDevemUsarFindByIdWithLock = classes()
            .that().resideInAPackage("inv.checkout..")
            .should(exigirFindByIdWithLockEmEscritasDeVenda())
            .as("Toda operação de escrita/transição em Venda existente deve adquirir lock pessimista via VendaRepository.findByIdWithLock");

    private static final String VENDA_CLASS = "inv.checkout.domain.model.Venda";
    private static final String VENDA_REPO_CLASS = "inv.checkout.infrastructure.persistence.VendaRepository";
    private static final String PAGAMENTO_REPO_CLASS = "inv.checkout.infrastructure.persistence.PagamentoRepository";
    private static final Set<String> MUTADORES_CRIACAO_VENDA = Set.of("adicionarItem", "irParaPagamento");
    private static final Set<String> METODOS_SAVE_REPO = Set.of(
            "save", "saveAndFlush", "saveAll", "saveAllAndFlush"
    );
    private static final Set<String> METODOS_DELETE_REPO = Set.of(
            "delete", "deleteById", "deleteAll", "deleteAllById", "deleteInBatch", "deleteAllInBatch"
    );

    private static ArchCondition<JavaClass> exigirFindByIdWithLockEmEscritasDeVenda() {
        return new ArchCondition<>("adquirir lock pessimista via VendaRepository.findByIdWithLock antes de mutar Venda existente") {
            private int metodosEscritaValidados = 0;

            @Override
            public void init(java.util.Collection<JavaClass> allObjectsToTest) {
                metodosEscritaValidados = 0;
            }

            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (item.getName().equals(VENDA_CLASS)) {
                    for (JavaField field : item.getFields()) {
                        if (!field.getModifiers().contains(JavaModifier.STATIC)
                                && !field.getModifiers().contains(JavaModifier.PRIVATE)) {
                            events.add(SimpleConditionEvent.violated(field,
                                    "Campo " + field.getFullName() + " em Venda deve ser private para impedir escrita sem método de domínio"));
                        }
                    }
                    for (JavaMethod method : item.getMethods()) {
                        if (method.getName().startsWith("set") && !method.getModifiers().contains(JavaModifier.PRIVATE)) {
                            events.add(SimpleConditionEvent.violated(method,
                                    "Método setter " + method.getFullName() + " em Venda não é permitido; mutações devem usar métodos de negócio"));
                        }
                    }
                    return;
                }

                if (item.getName().equals(VENDA_REPO_CLASS)) {
                    for (JavaMethod method : item.getMethods()) {
                        if (method.isAnnotatedWith(org.springframework.data.jpa.repository.Modifying.class)) {
                            events.add(SimpleConditionEvent.violated(method,
                                    "Query @Modifying " + method.getFullName() + " em VendaRepository contorna o lock pessimista findByIdWithLock"));
                        }
                    }
                    return;
                }

                for (JavaMethod method : item.getMethods()) {
                    boolean instanciaNovaVenda = method.getConstructorCallsFromSelf().stream()
                            .anyMatch(call -> call.getTargetOwner().getName().equals(VENDA_CLASS));

                    List<JavaMethodCall> chamadasMutadoras = method.getMethodCallsFromSelf().stream()
                            .filter(call -> call.getTargetOwner().getName().equals(VENDA_CLASS))
                            .filter(call -> !isMetodoSomenteLeituraVenda(call.getTarget().getName()))
                            .filter(call -> !(instanciaNovaVenda && MUTADORES_CRIACAO_VENDA.contains(call.getTarget().getName())))
                            .toList();

                    boolean acessaVendaRepo = method.getFieldAccesses().stream()
                            .anyMatch(fa -> fa.getTarget().getRawType().getName().equals(VENDA_REPO_CLASS));
                    boolean acessaOutroRepo = method.getFieldAccesses().stream()
                            .anyMatch(fa -> !fa.getTarget().getRawType().getName().equals(VENDA_REPO_CLASS)
                                    && fa.getTarget().getRawType().isAssignableTo(org.springframework.data.repository.Repository.class));

                    List<JavaMethodCall> chamadasEscritaDiretaVendaRepo = !acessaVendaRepo
                            ? List.of()
                            : method.getMethodCallsFromSelf().stream()
                                    .filter(call -> call.getTargetOwner().isAssignableTo(org.springframework.data.repository.Repository.class))
                                    .filter(call -> METODOS_DELETE_REPO.contains(call.getTarget().getName())
                                            || (!instanciaNovaVenda && !acessaOutroRepo && METODOS_SAVE_REPO.contains(call.getTarget().getName())))
                                    .toList();

                    if (chamadasMutadoras.isEmpty() && chamadasEscritaDiretoOuDelete(chamadasEscritaDiretaVendaRepo)) {
                        continue;
                    }

                    OptionalInt linhaLock = method.getMethodCallsFromSelf().stream()
                            .filter(call -> call.getTargetOwner().getName().equals(VENDA_REPO_CLASS)
                                    && call.getTarget().getName().equals("findByIdWithLock"))
                            .mapToInt(JavaMethodCall::getLineNumber)
                            .min();

                    if (linhaLock.isEmpty()) {
                        String operacao = !chamadasMutadoras.isEmpty()
                                ? chamadasMutadoras.getFirst().getTarget().getName()
                                : chamadasEscritaDiretaVendaRepo.getFirst().getTarget().getName();
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " realiza escrita em Venda existente (" + operacao
                                        + ") sem adquirir lock pessimista via VendaRepository.findByIdWithLock"));
                        continue;
                    }

                    int primeiraMutacao = chamadasMutadoras.stream()
                            .mapToInt(JavaMethodCall::getLineNumber)
                            .min()
                            .orElseGet(() -> chamadasEscritaDiretaVendaRepo.stream()
                                    .mapToInt(JavaMethodCall::getLineNumber)
                                    .min()
                                    .orElse(Integer.MAX_VALUE));

                    if (linhaLock.getAsInt() > primeiraMutacao) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " realiza escrita em Venda na linha " + primeiraMutacao
                                        + " antes de VendaRepository.findByIdWithLock na linha " + linhaLock.getAsInt()));
                        continue;
                    }

                    // Gate 4 complementar: nenhum carregamento de Pagamento/pagamentos pode ocorrer antes do lock pessimista em Venda
                    OptionalInt leituraPagamentoPreLock = method.getMethodCallsFromSelf().stream()
                            .filter(call -> call.getTargetOwner().getName().equals(PAGAMENTO_REPO_CLASS)
                                    || (call.getTargetOwner().getName().equals(VENDA_CLASS)
                                            && call.getTarget().getName().equals("getPagamentos")))
                            .mapToInt(JavaMethodCall::getLineNumber)
                            .filter(line -> line < linhaLock.getAsInt())
                            .min();

                    if (leituraPagamentoPreLock.isPresent()) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " carrega Pagamento/pagamentos na linha " + leituraPagamentoPreLock.getAsInt()
                                        + " antes de VendaRepository.findByIdWithLock na linha " + linhaLock.getAsInt()));
                        continue;
                    }

                    metodosEscritaValidados++;
                }
            }

            private boolean chamadasEscritaDiretoOuDelete(List<JavaMethodCall> chamadas) {
                return chamadas.isEmpty();
            }

            @Override
            public void finish(ConditionEvents events) {
                if (metodosEscritaValidados < 3) {
                    events.add(SimpleConditionEvent.violated(VENDA_CLASS,
                            "Esperava validar pelo menos 3 fluxos de escrita em Venda existente com findByIdWithLock, mas encontrou "
                                    + metodosEscritaValidados));
                }
            }

            private boolean isMetodoSomenteLeituraVenda(String methodName) {
                return methodName.startsWith("get")
                        || methodName.startsWith("is")
                        || methodName.equals("estaPaga")
                        || methodName.equals("equals")
                        || methodName.equals("hashCode")
                        || methodName.equals("toString");
            }
        };
    }

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
