# ADR-0013: Testes Unitários no Surefire e Integrações no Failsafe

* **Status:** Aceito
* **Data:** 2026-11-21
* **Autores:** Equipe de Engenharia
* **Bounded Contexts Afetados:** Transversal ao reactor Maven
* **Serviços:** `api-gateway`, `auth-service`, `inv-service` e `mail-service`. A biblioteca `common-lib` também participa do reactor.

---

## 1. Contexto e Problema (Context & Problem Statement)

Antes da [E1 do DEBT-ARCH-005](../debt/DEBT-ARCH-005/E1.md), o CI encerrava o ciclo Maven em `test`. A descoberta padrão do Surefire incluía `*IntegrationTest` pelo sufixo `Test` e deixava de fora `*IT`. Assim, a execução local podia iniciar Testcontainers e o CI podia aprovar um build com parte das integrações ausente.

---

## 2. Drivers de Decisão (Decision Drivers / Forces)

* **Ciclo local rápido.** A fase `test` deve executar a suíte do Surefire sem precisar de Docker.
* **Integrações como gate do CI.** O ciclo completo deve executar as duas convenções de nomes de integração e bloquear o build das imagens se houver falha.
* **Detecção de omissões.** O CI deve conferir a execução por módulo e por fase, acompanhando a evolução dos fontes de teste.

---

## 3. Opções Consideradas (Considered Options)

1. **Manter a descoberta padrão do Surefire.** Encerrar em `test` com a seleção anterior.
2. **Executar as duas suítes no Surefire.** Incluir explicitamente os testes de integração na mesma fase dos unitários.
3. **Separar os plugins por fase.** Executar a suíte rápida no Surefire e as integrações no Failsafe, com conferência dos relatórios no CI.

---

## 4. Decisão Tomada e Justificativa (Decision Outcome)

Adotamos a **Opção 3**, entregue em 2026-11-21 no commit `8066e72`. O [POM raiz](../../pom.xml) padroniza a descoberta no reactor e ativa as execuções do Failsafe herdadas do parent do Spring Boot.

| Plugin | Descoberta padrão no projeto | Fase do Maven |
| --- | --- | --- |
| Surefire | `Test*`, `*Test`, `*Tests` e `*TestCase`, com exclusão explícita de `*IT`, `*IntegrationTest` e classes internas (`**/*$*`). O JUnit descobre os casos `@Nested` pela classe externa. | `test` |
| Failsafe | `*IT` e `*IntegrationTest`, independentemente do pacote. | `integration-test` executa as integrações e `verify` verifica o resultado registrado pelo plugin. |

O Surefire executa testes em `common-lib`, `api-gateway`, `auth-service` e `inv-service`, incluindo os testes de arquitetura do inventário. As integrações atualmente estão em `inv-service` e `mail-service`. Os quatro POMs de serviço configuram o `mockito-core` como `-javaagent` na JVM de testes dos dois plugins, com o caminho do JAR fornecido por `maven-dependency-plugin:properties`. `common-lib` herda a partição do POM raiz e executa seus testes sem configuração de agente Mockito.

### Consequências e Trade-offs

* **Positivas.** `mvn test` atende ao ciclo local sem Docker. `mvn verify` percorre também as fases de integração e verifica suas falhas antes de liberar o CI para o build de imagens.
* **Custos e limites.** As integrações com Testcontainers exigem Docker e dependem da convenção de nomes. A conciliação XML protege o conjunto de classes reconhecido pela convenção. Ela verifica que cada relatório contém casos e que os casos registrados passaram, mas não inventaria métodos de teste nem mede cobertura ou detecta a omissão de uma classe fora dos padrões.

---

## 5. Prós e Contras das Opções Analisadas (Pros & Cons)

### Opção 1: Descoberta padrão do Surefire

* **Prós.** Exige menos configuração de build.
* **Contras.** Permite omitir integrações `*IT` e acopla as integrações `*IntegrationTest` à fase `test`.

### Opção 2: Duas suítes no Surefire

* **Prós.** Permite descobrir as duas convenções de integração em um único plugin.
* **Contras.** A fase `test` passaria a depender de Docker para executar toda a suíte. A separação da execução local exigiria filtros adicionais.

### Opção 3: Surefire e Failsafe por fase

* **Prós.** Vincula a seleção padrão ao ciclo Maven e mantém o gate de integração no CI.
* **Contras.** A execução completa precisa chegar a `verify`. Os módulos que usam Mockito mantêm a configuração do agente nos dois plugins.

---

## 6. Estratégia de Validação (Verification & Testing)

O job `unit-tests`, exibido como `2. Run Tests & Coverage`, roda `mvn clean verify` com ambas as suítes. Em seguida, o script de CI calcula o inventário esperado por módulo e plugin a partir dos nomes de arquivos em `apps/*/src/test/java` e do `package` declarado. Exclui classes abstratas, arquivos `*Fixture` e o diagnóstico `OutboxConcurrencyManualRunner`.

1. Cada classe esperada deve ter exatamente um `TEST-*.xml` com o nome qualificado correto no diretório de sua fase (`target/surefire-reports` ou `target/failsafe-reports`). Relatórios inesperados e classes que apareçam nas duas fases do mesmo módulo causam falha.
2. Cada relatório deve conter pelo menos um `<testcase>` e apresentar zero falhas, erros e casos ignorados, tanto nos contadores da suíte quanto nos elementos dos casos. O atributo `tests` da raiz pode ser zero em relatórios com casos `@Nested`.
3. Cada módulo com integrações previstas deve ter um `failsafe-summary.xml` de sucesso, sem timeout, com pelo menos um teste concluído. As contagens esperadas vêm dos fontes da revisão em execução.

O workflow publica os XMLs disponíveis como artefato mesmo em caso de falha. O job de build e publicação das imagens depende da aprovação do job de testes e do job `security-scan`.

Na raiz do repositório, `mvn test` executa a suíte do Surefire e `mvn clean verify` valida o ciclo completo com Docker ativo. Para seleção local, `-Dtest` seleciona no Surefire e `-Dit.test` seleciona no Failsafe. No segundo comando abaixo, `-Dtest=!*` deixa o Surefire sem classes selecionadas para rodar apenas a integração indicada. A conciliação do CI usa o ciclo completo e sem filtros.

```bash
mvn test -pl apps/inv-service -am "-Dtest=ArquiteturaTest,ModulithTest" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn verify -pl apps/inv-service -am '-Dtest=!*' "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=InventarioHttpIT" "-Dfailsafe.failIfNoSpecifiedTests=false"
```

---

## 7. Referências e Links Relevantes

* [Workflow de CI/CD](../../.github/workflows/ci-cd.yml) e [script de conciliação dos relatórios](../../.github/scripts/check-test-reports.py)
* POMs de [api-gateway](../../apps/api-gateway/pom.xml), [auth-service](../../apps/auth-service/pom.xml), [inv-service](../../apps/inv-service/pom.xml), [mail-service](../../apps/mail-service/pom.xml) e [common-lib](../../apps/common-lib/pom.xml)
* [Visão geral da arquitetura](../architecture/system-overview.md) e [histórico de execução do DEBT-ARCH-005 E1](../debt/DEBT-ARCH-005/E1.md)
