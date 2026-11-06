# Avisos de terceiros

> Nota de Escopo: Este documento consolida o inventário unificado de dependências de terceiros de todo o ecossistema `petshop-microservices`. Cada imagem de microsserviço embarca em runtime apenas o subconjunto de bibliotecas estritamente necessário à sua execução.

## Origem e escopo do inventário

Inventário baseado no Analyzer ORT 98.0.0 de 2026-11-05, revisão
`d31dee741d54fc21adfcfc3b3c9918ae5b248f83` (PR #54), reconciliado em 2026-11-06
com `BOOT-INF/lib/` dos quatro JARs publicados no GHCR. As dependências não foram
alteradas nesta migração. Ferramentas de CI e dependências exclusivas de teste não
integram a tabela. `common-lib` pertence ao próprio projeto e está coberto por `LICENSE`.
`spring-boot-jarmode-tools`, acrescentado pelo empacotador Spring Boot, foi incluído
pela inspeção direta mesmo não aparecendo no Analyzer.

O relatório foi gerado sem Scanner, com `PlainTextTemplate` (template `NOTICE_SUMMARY`)
e `StaticHTML`, os nomes suportados pelo ORT 98.0.0. A configuração `.ort.yml`
exclui o escopo `test`. Os textos do relatório são complementados pelos arquivos
de licença e avisos dos JARs, sources JARs e fontes oficiais nas versões indicadas.
Metadados de POM não substituem os termos do artefato: a expressão do AspectJ abaixo
foi corrigida a partir de `LICENSE-AspectJ.adoc`.

As licenças de terceiros permanecem aplicáveis aos respectivos componentes; a licença
do projeto não as substitui. Esta relação descreve os artefatos e seus termos e não
introduz exceção de linking nem uma conclusão jurídica sobre sua combinação.

### Imagens usadas como evidência

Os digests abaixo identificam as imagens anteriores à migração, ainda sob MIT,
e não os digests da imagem-base nem de imagens futuras.

| Serviço | Digest GHCR |
| --- | --- |
| api-gateway | `sha256:ece637e9ad753d41303e88fcdcc665a994185ccc24373b9f50de03468a003a30` |
| auth-service | `sha256:88e4f02ae283d034603395037ea41b81d55854416d94e090cfb8d36f5584005b` |
| inv-service | `sha256:68e022f2002a408e1a7392580b12d75d56d7e8da9b6fd6537fabf03cefbb5a01` |
| mail-service | `sha256:7fdbdf2ac29c1c6bc8268ac3f08e2ddd0f6671ac68df8586479cce96c599819d` |

## AspectJ Weaver 1.9.25.1

`org.aspectj:aspectjweaver:1.9.25.1` é uma dependência de infraestrutura usada na
pilha Spring/JPA, distribuída sem alterações como JAR em `BOOT-INF/lib/`.
O arquivo `LICENSE-AspectJ.adoc` declara `EPL-2.0 AND BSD-3-Clause AND Apache-1.1`.
Inclui BCEL 5.1 modificado pelo projeto AspectJ e ASM 9.8; isso não significa que
este repositório tenha modificado o artefato recebido.

- [Código-fonte da versão no Maven Central](https://repo1.maven.org/maven2/org/aspectj/aspectjweaver/1.9.25.1/aspectjweaver-1.9.25.1-sources.jar).
- [Release V1_9_25_1](https://github.com/eclipse-aspectj/aspectj/releases/tag/V1_9_25_1).

O anexo de avisos reproduz `LICENSE-AspectJ.adoc` e os textos integrais EPL-2.0,
Apache-1.1 e BSD-3-Clause contidos no JAR, inclusive os avisos de autoria e disclaimers.

## Bibliotecas LGPL e substituição (relinking)

Hibernate ORM 6.6.39.Final declara `LGPL-2.1-or-later`. Logback 1.5.22 declara
`EPL-1.0 OR LGPL-2.1-or-later`; a alternativa EPL não é omitida neste inventário.
Os JARs são preservados separadamente em `BOOT-INF/lib/`, sem incorporar seu
código-fonte às classes do projeto. Para substituir essas bibliotecas por uma versão
compatível, inclusive uma versão modificada publicada no repositório Maven local:

1. Obtenha o código-fonte correspondente à imagem, conforme o README.
2. Compile e instale a biblioteca substituta com suas próprias coordenadas/versão.
3. No POM pai, sobrescreva `hibernate.version` e/ou `logback.version` em `properties`.
4. Execute `mvn clean package -DskipTests` na raiz e reconstrua a imagem Docker.
5. Confira a versão em `BOOT-INF/lib/` e valide a compatibilidade da substituição.

Alternativamente, um override temporário pode ser aplicado sem editar o POM:

```bash
mvn clean package -pl apps/inv-service -am -DskipTests -Dhibernate.version=6.6.40.Final
jar tf apps/inv-service/target/inv-service-1.0-SNAPSHOT.jar
```

O empacotador regenera `BOOT-INF/classpath.idx`; não edite apenas o JAR aninhado.
O override funciona porque o pai herda diretamente de `spring-boot-starter-parent`
3.4.13. Em uma futura migração para importação do BOM, declare o override da
dependência em `dependencyManagement`, antes do BOM importado.

Fontes oficiais: [Hibernate ORM](https://github.com/hibernate/hibernate-orm/tree/6.6.39)
e [Logback](https://github.com/qos-ch/logback/tree/v_1.5.22). A tabela também fornece
sources JARs por versão. Os termos LGPL integrais constam do anexo.

## Runtime e sistema operacional

As imagens usam a família `eclipse-temurin:21-jre-alpine`. O digest efetivamente
selecionado está em cada Dockerfile e pode ser atualizado pelo Dependabot.
O OpenJDK é distribuído sob GPL-2.0 com Classpath Exception; consulte também os
avisos específicos de módulos em `/opt/java/openjdk/legal/` dentro da imagem.
Pacotes Alpine mantêm licenças próprias; a tabela Java abaixo não é um inventário
dos pacotes do sistema operacional. `apk info -a` permite inspecionar os pacotes
da imagem concreta.

- [Fontes e releases Temurin 21](https://github.com/adoptium/temurin21-binaries/releases).
- [Dockerfiles e proveniência das imagens Temurin](https://github.com/adoptium/containers).
- [Licenças OpenJDK e Classpath Exception](https://openjdk.org/legal/).
- [Pacotes e fontes Alpine](https://pkgs.alpinelinux.org/packages).

## Inventário de runtime

G = api-gateway; A = auth-service; I = inv-service; M = mail-service.
Classificadores de plataforma são preservados no nome do JAR. Expressões com `OR`
representam alternativas declaradas pelo componente, não licenças cumulativas.

| Artefato Maven / JAR | Licença declarada ou verificada | Imagens | Fonte |
| --- | --- | --- | --- |
| `ch.qos.logback:logback-classic-1.5.22.jar` | `EPL-1.0 OR LGPL-2.1-or-later` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/ch/qos/logback/logback-classic/1.5.22/logback-classic-1.5.22-sources.jar) |
| `ch.qos.logback:logback-core-1.5.22.jar` | `EPL-1.0 OR LGPL-2.1-or-later` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/ch/qos/logback/logback-core/1.5.22/logback-core-1.5.22-sources.jar) |
| `com.fasterxml.jackson.core:jackson-annotations-2.18.5.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.18.5/jackson-annotations-2.18.5-sources.jar) |
| `com.fasterxml.jackson.core:jackson-core-2.18.5.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-core/2.18.5/jackson-core-2.18.5-sources.jar) |
| `com.fasterxml.jackson.core:jackson-databind-2.18.5.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.18.5/jackson-databind-2.18.5-sources.jar) |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-toml-2.18.5.jar` | `Apache-2.0` | A, I | [fontes](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/dataformat/jackson-dataformat-toml/2.18.5/jackson-dataformat-toml-2.18.5-sources.jar) |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml-2.18.5.jar` | `Apache-2.0` | G, A, I | [fontes](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/dataformat/jackson-dataformat-yaml/2.18.5/jackson-dataformat-yaml-2.18.5-sources.jar) |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8-2.18.5.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/datatype/jackson-datatype-jdk8/2.18.5/jackson-datatype-jdk8-2.18.5-sources.jar) |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310-2.18.5.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/datatype/jackson-datatype-jsr310/2.18.5/jackson-datatype-jsr310-2.18.5-sources.jar) |
| `com.fasterxml.jackson.module:jackson-module-parameter-names-2.18.5.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/module/jackson-module-parameter-names/2.18.5/jackson-module-parameter-names-2.18.5-sources.jar) |
| `com.fasterxml:classmate-1.7.1.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/com/fasterxml/classmate/1.7.1/classmate-1.7.1-sources.jar) |
| `com.github.ben-manes.caffeine:caffeine-3.1.8.jar` | `Apache-2.0` | I | [fontes](https://repo.maven.apache.org/maven2/com/github/ben-manes/caffeine/caffeine/3.1.8/caffeine-3.1.8-sources.jar) |
| `com.github.stephenc.jcip:jcip-annotations-1.0-1.jar` | `Apache-2.0` | G, A, I | [fontes](https://repo.maven.apache.org/maven2/com/github/stephenc/jcip/jcip-annotations/1.0-1/jcip-annotations-1.0-1-sources.jar) |
| `com.google.errorprone:error_prone_annotations-2.21.1.jar` | `Apache-2.0` | I | [fontes](https://repo.maven.apache.org/maven2/com/google/errorprone/error_prone_annotations/2.21.1/error_prone_annotations-2.21.1-sources.jar) |
| `com.nimbusds:content-type-2.2.jar` | `Apache-2.0` | A | [fontes](https://repo.maven.apache.org/maven2/com/nimbusds/content-type/2.2/content-type-2.2-sources.jar) |
| `com.nimbusds:lang-tag-1.7.jar` | `Apache-2.0` | A | [fontes](https://repo.maven.apache.org/maven2/com/nimbusds/lang-tag/1.7/lang-tag-1.7-sources.jar) |
| `com.nimbusds:nimbus-jose-jwt-9.37.4.jar` | `Apache-2.0` | G, I | [fontes](https://repo.maven.apache.org/maven2/com/nimbusds/nimbus-jose-jwt/9.37.4/nimbus-jose-jwt-9.37.4-sources.jar) |
| `com.nimbusds:nimbus-jose-jwt-9.47.jar` | `Apache-2.0` | A | [fontes](https://repo.maven.apache.org/maven2/com/nimbusds/nimbus-jose-jwt/9.47/nimbus-jose-jwt-9.47-sources.jar) |
| `com.nimbusds:oauth2-oidc-sdk-9.43.6.jar` | `Apache-2.0` | A | [fontes](https://repo.maven.apache.org/maven2/com/nimbusds/oauth2-oidc-sdk/9.43.6/oauth2-oidc-sdk-9.43.6-sources.jar) |
| `com.rabbitmq:amqp-client-5.22.0.jar` | `Apache-2.0 OR GPL-2.0-only OR MPL-2.0` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/com/rabbitmq/amqp-client/5.22.0/amqp-client-5.22.0-sources.jar) |
| `com.sun.istack:istack-commons-runtime-4.1.2.jar` | `BSD-3-Clause` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/com/sun/istack/istack-commons-runtime/4.1.2/istack-commons-runtime-4.1.2-sources.jar) |
| `com.zaxxer:HikariCP-5.1.0.jar` | `Apache-2.0` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/com/zaxxer/HikariCP/5.1.0/HikariCP-5.1.0-sources.jar) |
| `io.lettuce:lettuce-core-6.4.2.RELEASE.jar` | `MIT` | G, I | [fontes](https://repo.maven.apache.org/maven2/io/lettuce/lettuce-core/6.4.2.RELEASE/lettuce-core-6.4.2.RELEASE-sources.jar) |
| `io.micrometer:micrometer-commons-1.14.14.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/io/micrometer/micrometer-commons/1.14.14/micrometer-commons-1.14.14-sources.jar) |
| `io.micrometer:micrometer-core-1.14.14.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/io/micrometer/micrometer-core/1.14.14/micrometer-core-1.14.14-sources.jar) |
| `io.micrometer:micrometer-jakarta9-1.14.14.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/io/micrometer/micrometer-jakarta9/1.14.14/micrometer-jakarta9-1.14.14-sources.jar) |
| `io.micrometer:micrometer-observation-1.14.14.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/io/micrometer/micrometer-observation/1.14.14/micrometer-observation-1.14.14-sources.jar) |
| `io.micrometer:micrometer-registry-prometheus-1.14.14.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/io/micrometer/micrometer-registry-prometheus/1.14.14/micrometer-registry-prometheus-1.14.14-sources.jar) |
| `io.netty:netty-buffer-4.1.130.Final.jar` | `Apache-2.0` | G, I | [fontes](https://repo.maven.apache.org/maven2/io/netty/netty-buffer/4.1.130.Final/netty-buffer-4.1.130.Final-sources.jar) |
| `io.netty:netty-codec-dns-4.1.130.Final.jar` | `Apache-2.0` | G | [fontes](https://repo.maven.apache.org/maven2/io/netty/netty-codec-dns/4.1.130.Final/netty-codec-dns-4.1.130.Final-sources.jar) |
| `io.netty:netty-codec-http2-4.1.130.Final.jar` | `Apache-2.0` | G | [fontes](https://repo.maven.apache.org/maven2/io/netty/netty-codec-http2/4.1.130.Final/netty-codec-http2-4.1.130.Final-sources.jar) |
| `io.netty:netty-codec-http-4.1.130.Final.jar` | `Apache-2.0` | G | [fontes](https://repo.maven.apache.org/maven2/io/netty/netty-codec-http/4.1.130.Final/netty-codec-http-4.1.130.Final-sources.jar) |
| `io.netty:netty-codec-socks-4.1.130.Final.jar` | `Apache-2.0` | G | [fontes](https://repo.maven.apache.org/maven2/io/netty/netty-codec-socks/4.1.130.Final/netty-codec-socks-4.1.130.Final-sources.jar) |
| `io.netty:netty-codec-4.1.130.Final.jar` | `Apache-2.0` | G, I | [fontes](https://repo.maven.apache.org/maven2/io/netty/netty-codec/4.1.130.Final/netty-codec-4.1.130.Final-sources.jar) |
| `io.netty:netty-common-4.1.130.Final.jar` | `Apache-2.0` | G, I | [fontes](https://repo.maven.apache.org/maven2/io/netty/netty-common/4.1.130.Final/netty-common-4.1.130.Final-sources.jar) |
| `io.netty:netty-handler-proxy-4.1.130.Final.jar` | `Apache-2.0` | G | [fontes](https://repo.maven.apache.org/maven2/io/netty/netty-handler-proxy/4.1.130.Final/netty-handler-proxy-4.1.130.Final-sources.jar) |
| `io.netty:netty-handler-4.1.130.Final.jar` | `Apache-2.0` | G, I | [fontes](https://repo.maven.apache.org/maven2/io/netty/netty-handler/4.1.130.Final/netty-handler-4.1.130.Final-sources.jar) |
| `io.netty:netty-resolver-dns-classes-macos-4.1.130.Final.jar` | `Apache-2.0` | G | [fontes](https://repo.maven.apache.org/maven2/io/netty/netty-resolver-dns-classes-macos/4.1.130.Final/netty-resolver-dns-classes-macos-4.1.130.Final-sources.jar) |
| `io.netty:netty-resolver-dns-native-macos-4.1.130.Final-osx-x86_64.jar` | `Apache-2.0` | G | [fontes](https://repo.maven.apache.org/maven2/io/netty/netty-resolver-dns-native-macos/4.1.130.Final/netty-resolver-dns-native-macos-4.1.130.Final-sources.jar) |
| `io.netty:netty-resolver-dns-4.1.130.Final.jar` | `Apache-2.0` | G | [fontes](https://repo.maven.apache.org/maven2/io/netty/netty-resolver-dns/4.1.130.Final/netty-resolver-dns-4.1.130.Final-sources.jar) |
| `io.netty:netty-resolver-4.1.130.Final.jar` | `Apache-2.0` | G, I | [fontes](https://repo.maven.apache.org/maven2/io/netty/netty-resolver/4.1.130.Final/netty-resolver-4.1.130.Final-sources.jar) |
| `io.netty:netty-transport-classes-epoll-4.1.130.Final.jar` | `Apache-2.0` | G | [fontes](https://repo.maven.apache.org/maven2/io/netty/netty-transport-classes-epoll/4.1.130.Final/netty-transport-classes-epoll-4.1.130.Final-sources.jar) |
| `io.netty:netty-transport-native-epoll-4.1.130.Final-linux-x86_64.jar` | `Apache-2.0` | G | [fontes](https://repo.maven.apache.org/maven2/io/netty/netty-transport-native-epoll/4.1.130.Final/netty-transport-native-epoll-4.1.130.Final-sources.jar) |
| `io.netty:netty-transport-native-unix-common-4.1.130.Final.jar` | `Apache-2.0` | G, I | [fontes](https://repo.maven.apache.org/maven2/io/netty/netty-transport-native-unix-common/4.1.130.Final/netty-transport-native-unix-common-4.1.130.Final-sources.jar) |
| `io.netty:netty-transport-4.1.130.Final.jar` | `Apache-2.0` | G, I | [fontes](https://repo.maven.apache.org/maven2/io/netty/netty-transport/4.1.130.Final/netty-transport-4.1.130.Final-sources.jar) |
| `io.projectreactor.addons:reactor-extra-3.5.4.jar` | `Apache-2.0` | G | [fontes](https://repo.maven.apache.org/maven2/io/projectreactor/addons/reactor-extra/3.5.4/reactor-extra-3.5.4-sources.jar) |
| `io.projectreactor.netty:reactor-netty-core-1.2.13.jar` | `Apache-2.0` | G | [fontes](https://repo.maven.apache.org/maven2/io/projectreactor/netty/reactor-netty-core/1.2.13/reactor-netty-core-1.2.13-sources.jar) |
| `io.projectreactor.netty:reactor-netty-http-1.2.13.jar` | `Apache-2.0` | G | [fontes](https://repo.maven.apache.org/maven2/io/projectreactor/netty/reactor-netty-http/1.2.13/reactor-netty-http-1.2.13-sources.jar) |
| `io.projectreactor:reactor-core-3.7.14.jar` | `Apache-2.0` | G, I | [fontes](https://repo.maven.apache.org/maven2/io/projectreactor/reactor-core/3.7.14/reactor-core-3.7.14-sources.jar) |
| `io.prometheus:prometheus-metrics-config-1.3.10.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/io/prometheus/prometheus-metrics-config/1.3.10/prometheus-metrics-config-1.3.10-sources.jar) |
| `io.prometheus:prometheus-metrics-core-1.3.10.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/io/prometheus/prometheus-metrics-core/1.3.10/prometheus-metrics-core-1.3.10-sources.jar) |
| `io.prometheus:prometheus-metrics-exposition-formats-1.3.10.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/io/prometheus/prometheus-metrics-exposition-formats/1.3.10/prometheus-metrics-exposition-formats-1.3.10-sources.jar) |
| `io.prometheus:prometheus-metrics-exposition-textformats-1.3.10.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/io/prometheus/prometheus-metrics-exposition-textformats/1.3.10/prometheus-metrics-exposition-textformats-1.3.10-sources.jar) |
| `io.prometheus:prometheus-metrics-model-1.3.10.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/io/prometheus/prometheus-metrics-model/1.3.10/prometheus-metrics-model-1.3.10-sources.jar) |
| `io.prometheus:prometheus-metrics-tracer-common-1.3.10.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/io/prometheus/prometheus-metrics-tracer-common/1.3.10/prometheus-metrics-tracer-common-1.3.10-sources.jar) |
| `io.smallrye:jandex-3.2.0.jar` | `Apache-2.0` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/io/smallrye/jandex/3.2.0/jandex-3.2.0-sources.jar) |
| `io.swagger.core.v3:swagger-annotations-jakarta-2.2.27.jar` | `Apache-2.0` | G, A, I | [fontes](https://repo.maven.apache.org/maven2/io/swagger/core/v3/swagger-annotations-jakarta/2.2.27/swagger-annotations-jakarta-2.2.27-sources.jar) |
| `io.swagger.core.v3:swagger-core-jakarta-2.2.27.jar` | `Apache-2.0` | G, A, I | [fontes](https://repo.maven.apache.org/maven2/io/swagger/core/v3/swagger-core-jakarta/2.2.27/swagger-core-jakarta-2.2.27-sources.jar) |
| `io.swagger.core.v3:swagger-models-jakarta-2.2.27.jar` | `Apache-2.0` | G, A, I | [fontes](https://repo.maven.apache.org/maven2/io/swagger/core/v3/swagger-models-jakarta/2.2.27/swagger-models-jakarta-2.2.27-sources.jar) |
| `jakarta.activation:jakarta.activation-api-2.1.4.jar` | `BSD-3-Clause` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/jakarta/activation/jakarta.activation-api/2.1.4/jakarta.activation-api-2.1.4-sources.jar) |
| `jakarta.annotation:jakarta.annotation-api-2.1.1.jar` | `EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/jakarta/annotation/jakarta.annotation-api/2.1.1/jakarta.annotation-api-2.1.1-sources.jar) |
| `jakarta.inject:jakarta.inject-api-2.0.1.jar` | `Apache-2.0` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/jakarta/inject/jakarta.inject-api/2.0.1/jakarta.inject-api-2.0.1-sources.jar) |
| `jakarta.persistence:jakarta.persistence-api-3.1.0.jar` | `BSD-3-Clause OR EPL-2.0` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/jakarta/persistence/jakarta.persistence-api/3.1.0/jakarta.persistence-api-3.1.0-sources.jar) |
| `jakarta.transaction:jakarta.transaction-api-2.0.1.jar` | `EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/jakarta/transaction/jakarta.transaction-api/2.0.1/jakarta.transaction-api-2.0.1-sources.jar) |
| `jakarta.validation:jakarta.validation-api-3.0.2.jar` | `Apache-2.0` | G, A, I | [fontes](https://repo.maven.apache.org/maven2/jakarta/validation/jakarta.validation-api/3.0.2/jakarta.validation-api-3.0.2-sources.jar) |
| `jakarta.xml.bind:jakarta.xml.bind-api-4.0.4.jar` | `BSD-3-Clause` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/jakarta/xml/bind/jakarta.xml.bind-api/4.0.4/jakarta.xml.bind-api-4.0.4-sources.jar) |
| `net.bytebuddy:byte-buddy-1.15.11.jar` | `Apache-2.0` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/net/bytebuddy/byte-buddy/1.15.11/byte-buddy-1.15.11-sources.jar) |
| `net.logstash.logback:logstash-logback-encoder-7.4.jar` | `Apache-2.0 OR MIT` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/net/logstash/logback/logstash-logback-encoder/7.4/logstash-logback-encoder-7.4-sources.jar) |
| `net.minidev:accessors-smart-2.5.2.jar` | `Apache-2.0` | A | [fontes](https://repo.maven.apache.org/maven2/net/minidev/accessors-smart/2.5.2/accessors-smart-2.5.2-sources.jar) |
| `net.minidev:json-smart-2.5.2.jar` | `Apache-2.0` | A | [fontes](https://repo.maven.apache.org/maven2/net/minidev/json-smart/2.5.2/json-smart-2.5.2-sources.jar) |
| `org.antlr:antlr4-runtime-4.13.0.jar` | `BSD-3-Clause` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/antlr/antlr4-runtime/4.13.0/antlr4-runtime-4.13.0-sources.jar) |
| `org.apache.commons:commons-lang3-3.17.0.jar` | `Apache-2.0` | G, A, I | [fontes](https://repo.maven.apache.org/maven2/org/apache/commons/commons-lang3/3.17.0/commons-lang3-3.17.0-sources.jar) |
| `org.apache.logging.log4j:log4j-api-2.24.3.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/apache/logging/log4j/log4j-api/2.24.3/log4j-api-2.24.3-sources.jar) |
| `org.apache.logging.log4j:log4j-to-slf4j-2.24.3.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/apache/logging/log4j/log4j-to-slf4j/2.24.3/log4j-to-slf4j-2.24.3-sources.jar) |
| `org.apache.tomcat.embed:tomcat-embed-core-10.1.50.jar` | `Apache-2.0` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/apache/tomcat/embed/tomcat-embed-core/10.1.50/tomcat-embed-core-10.1.50-sources.jar) |
| `org.apache.tomcat.embed:tomcat-embed-el-10.1.50.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/apache/tomcat/embed/tomcat-embed-el/10.1.50/tomcat-embed-el-10.1.50-sources.jar) |
| `org.apache.tomcat.embed:tomcat-embed-websocket-10.1.50.jar` | `Apache-2.0` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/apache/tomcat/embed/tomcat-embed-websocket/10.1.50/tomcat-embed-websocket-10.1.50-sources.jar) |
| `org.aspectj:aspectjweaver-1.9.25.1.jar` | `EPL-2.0 AND BSD-3-Clause AND Apache-1.1` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/aspectj/aspectjweaver/1.9.25.1/aspectjweaver-1.9.25.1-sources.jar) |
| `org.attoparser:attoparser-2.0.7.RELEASE.jar` | `Apache-2.0` | A | [fontes](https://repo.maven.apache.org/maven2/org/attoparser/attoparser/2.0.7.RELEASE/attoparser-2.0.7.RELEASE-sources.jar) |
| `org.bouncycastle:bcprov-jdk18on-1.78.1.jar` | `MIT` | G | [fontes](https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk18on/1.78.1/bcprov-jdk18on-1.78.1-sources.jar) |
| `org.checkerframework:checker-qual-3.49.5.jar` | `MIT` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/checkerframework/checker-qual/3.49.5/checker-qual-3.49.5-sources.jar) |
| `org.eclipse.angus:angus-activation-2.0.3.jar` | `BSD-3-Clause` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/eclipse/angus/angus-activation/2.0.3/angus-activation-2.0.3-sources.jar) |
| `org.eclipse.angus:jakarta.mail-2.0.5.jar` | `BSD-3-Clause OR EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0` | M | [fontes](https://repo.maven.apache.org/maven2/org/eclipse/angus/jakarta.mail/2.0.5/jakarta.mail-2.0.5-sources.jar) |
| `org.flywaydb:flyway-core-10.20.1.jar` | `Apache-2.0` | A, I | [fontes](https://repo.maven.apache.org/maven2/org/flywaydb/flyway-core/10.20.1/flyway-core-10.20.1-sources.jar) |
| `org.flywaydb:flyway-database-postgresql-10.20.1.jar` | `Apache-2.0` | A, I | [fontes](https://repo.maven.apache.org/maven2/org/flywaydb/flyway-database-postgresql/10.20.1/flyway-database-postgresql-10.20.1-sources.jar) |
| `org.glassfish.jaxb:jaxb-core-4.0.6.jar` | `BSD-3-Clause` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/glassfish/jaxb/jaxb-core/4.0.6/jaxb-core-4.0.6-sources.jar) |
| `org.glassfish.jaxb:jaxb-runtime-4.0.6.jar` | `BSD-3-Clause` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/glassfish/jaxb/jaxb-runtime/4.0.6/jaxb-runtime-4.0.6-sources.jar) |
| `org.glassfish.jaxb:txw2-4.0.6.jar` | `BSD-3-Clause` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/glassfish/jaxb/txw2/4.0.6/txw2-4.0.6-sources.jar) |
| `org.hdrhistogram:HdrHistogram-2.2.2.jar` | `BSD-2-Clause OR CC0-1.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/hdrhistogram/HdrHistogram/2.2.2/HdrHistogram-2.2.2-sources.jar) |
| `org.hibernate.common:hibernate-commons-annotations-7.0.3.Final.jar` | `Apache-2.0` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/hibernate/common/hibernate-commons-annotations/7.0.3.Final/hibernate-commons-annotations-7.0.3.Final-sources.jar) |
| `org.hibernate.orm:hibernate-core-6.6.39.Final.jar` | `LGPL-2.1-or-later` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/hibernate/orm/hibernate-core/6.6.39.Final/hibernate-core-6.6.39.Final-sources.jar) |
| `org.hibernate.validator:hibernate-validator-8.0.3.Final.jar` | `Apache-2.0` | G, A, I | [fontes](https://repo.maven.apache.org/maven2/org/hibernate/validator/hibernate-validator/8.0.3.Final/hibernate-validator-8.0.3.Final-sources.jar) |
| `org.jboss.logging:jboss-logging-3.6.1.Final.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/jboss/logging/jboss-logging/3.6.1.Final/jboss-logging-3.6.1.Final-sources.jar) |
| `org.jspecify:jspecify-1.0.0.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/jspecify/jspecify/1.0.0/jspecify-1.0.0-sources.jar) |
| `org.latencyutils:LatencyUtils-2.0.3.jar` | `CC0-1.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/latencyutils/LatencyUtils/2.0.3/LatencyUtils-2.0.3-sources.jar) |
| `org.ow2.asm:asm-9.7.1.jar` | `BSD-3-Clause` | A | [fontes](https://repo.maven.apache.org/maven2/org/ow2/asm/asm/9.7.1/asm-9.7.1-sources.jar) |
| `org.postgresql:postgresql-42.7.8.jar` | `BSD-2-Clause` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/postgresql/postgresql/42.7.8/postgresql-42.7.8-sources.jar) |
| `org.reactivestreams:reactive-streams-1.0.4.jar` | `MIT-0` | G, I | [fontes](https://repo.maven.apache.org/maven2/org/reactivestreams/reactive-streams/1.0.4/reactive-streams-1.0.4-sources.jar) |
| `org.slf4j:jul-to-slf4j-2.0.17.jar` | `MIT` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/slf4j/jul-to-slf4j/2.0.17/jul-to-slf4j-2.0.17-sources.jar) |
| `org.slf4j:slf4j-api-2.0.17.jar` | `MIT` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17-sources.jar) |
| `org.springdoc:springdoc-openapi-starter-common-2.8.3.jar` | `Apache-2.0` | G, A, I | [fontes](https://repo.maven.apache.org/maven2/org/springdoc/springdoc-openapi-starter-common/2.8.3/springdoc-openapi-starter-common-2.8.3-sources.jar) |
| `org.springdoc:springdoc-openapi-starter-webflux-api-2.8.3.jar` | `Apache-2.0` | G | [fontes](https://repo.maven.apache.org/maven2/org/springdoc/springdoc-openapi-starter-webflux-api/2.8.3/springdoc-openapi-starter-webflux-api-2.8.3-sources.jar) |
| `org.springdoc:springdoc-openapi-starter-webflux-ui-2.8.3.jar` | `Apache-2.0` | G | [fontes](https://repo.maven.apache.org/maven2/org/springdoc/springdoc-openapi-starter-webflux-ui/2.8.3/springdoc-openapi-starter-webflux-ui-2.8.3-sources.jar) |
| `org.springdoc:springdoc-openapi-starter-webmvc-api-2.8.3.jar` | `Apache-2.0` | A, I | [fontes](https://repo.maven.apache.org/maven2/org/springdoc/springdoc-openapi-starter-webmvc-api/2.8.3/springdoc-openapi-starter-webmvc-api-2.8.3-sources.jar) |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui-2.8.3.jar` | `Apache-2.0` | A, I | [fontes](https://repo.maven.apache.org/maven2/org/springdoc/springdoc-openapi-starter-webmvc-ui/2.8.3/springdoc-openapi-starter-webmvc-ui-2.8.3-sources.jar) |
| `org.springframework.amqp:spring-amqp-3.2.8.jar` | `Apache-2.0` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/amqp/spring-amqp/3.2.8/spring-amqp-3.2.8-sources.jar) |
| `org.springframework.amqp:spring-rabbit-3.2.8.jar` | `Apache-2.0` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/amqp/spring-rabbit/3.2.8/spring-rabbit-3.2.8-sources.jar) |
| `org.springframework.boot:spring-boot-actuator-autoconfigure-3.4.13.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-actuator-autoconfigure/3.4.13/spring-boot-actuator-autoconfigure-3.4.13-sources.jar) |
| `org.springframework.boot:spring-boot-actuator-3.4.13.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-actuator/3.4.13/spring-boot-actuator-3.4.13-sources.jar) |
| `org.springframework.boot:spring-boot-autoconfigure-3.4.13.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-autoconfigure/3.4.13/spring-boot-autoconfigure-3.4.13-sources.jar) |
| `org.springframework.boot:spring-boot-jarmode-tools-3.4.13.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-jarmode-tools/3.4.13/spring-boot-jarmode-tools-3.4.13-sources.jar) |
| `org.springframework.boot:spring-boot-3.4.13.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot/3.4.13/spring-boot-3.4.13-sources.jar) |
| `org.springframework.cloud:spring-cloud-commons-4.2.4.jar` | `Apache-2.0` | G | [fontes](https://repo.maven.apache.org/maven2/org/springframework/cloud/spring-cloud-commons/4.2.4/spring-cloud-commons-4.2.4-sources.jar) |
| `org.springframework.cloud:spring-cloud-context-4.2.4.jar` | `Apache-2.0` | G | [fontes](https://repo.maven.apache.org/maven2/org/springframework/cloud/spring-cloud-context/4.2.4/spring-cloud-context-4.2.4-sources.jar) |
| `org.springframework.cloud:spring-cloud-gateway-server-4.2.7.jar` | `Apache-2.0` | G | [fontes](https://repo.maven.apache.org/maven2/org/springframework/cloud/spring-cloud-gateway-server/4.2.7/spring-cloud-gateway-server-4.2.7-sources.jar) |
| `org.springframework.cloud:spring-cloud-starter-gateway-4.2.7.jar` | `Apache-2.0` | G | [fontes](https://github.com/spring-cloud/spring-cloud-gateway/tree/v4.2.7) |
| `org.springframework.cloud:spring-cloud-starter-4.2.4.jar` | `Apache-2.0` | G | [fontes](https://github.com/spring-cloud/spring-cloud-commons/tree/v4.2.4) |
| `org.springframework.data:spring-data-commons-3.4.13.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/data/spring-data-commons/3.4.13/spring-data-commons-3.4.13-sources.jar) |
| `org.springframework.data:spring-data-jpa-3.4.13.jar` | `Apache-2.0` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/data/spring-data-jpa/3.4.13/spring-data-jpa-3.4.13-sources.jar) |
| `org.springframework.data:spring-data-keyvalue-3.4.13.jar` | `Apache-2.0` | G, I | [fontes](https://repo.maven.apache.org/maven2/org/springframework/data/spring-data-keyvalue/3.4.13/spring-data-keyvalue-3.4.13-sources.jar) |
| `org.springframework.data:spring-data-redis-3.4.13.jar` | `Apache-2.0` | G, I | [fontes](https://repo.maven.apache.org/maven2/org/springframework/data/spring-data-redis/3.4.13/spring-data-redis-3.4.13-sources.jar) |
| `org.springframework.modulith:spring-modulith-api-1.3.2.jar` | `Apache-2.0` | I | [fontes](https://repo.maven.apache.org/maven2/org/springframework/modulith/spring-modulith-api/1.3.2/spring-modulith-api-1.3.2-sources.jar) |
| `org.springframework.retry:spring-retry-2.0.12.jar` | `Apache-2.0` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/retry/spring-retry/2.0.12/spring-retry-2.0.12-sources.jar) |
| `org.springframework.security:spring-security-config-6.4.13.jar` | `Apache-2.0` | G, A, I | [fontes](https://repo.maven.apache.org/maven2/org/springframework/security/spring-security-config/6.4.13/spring-security-config-6.4.13-sources.jar) |
| `org.springframework.security:spring-security-core-6.4.13.jar` | `Apache-2.0` | G, A, I | [fontes](https://repo.maven.apache.org/maven2/org/springframework/security/spring-security-core/6.4.13/spring-security-core-6.4.13-sources.jar) |
| `org.springframework.security:spring-security-crypto-6.4.13.jar` | `Apache-2.0` | G, A, I | [fontes](https://repo.maven.apache.org/maven2/org/springframework/security/spring-security-crypto/6.4.13/spring-security-crypto-6.4.13-sources.jar) |
| `org.springframework.security:spring-security-oauth2-authorization-server-1.4.8.jar` | `Apache-2.0` | A | [fontes](https://repo.maven.apache.org/maven2/org/springframework/security/spring-security-oauth2-authorization-server/1.4.8/spring-security-oauth2-authorization-server-1.4.8-sources.jar) |
| `org.springframework.security:spring-security-oauth2-client-6.4.13.jar` | `Apache-2.0` | A | [fontes](https://repo.maven.apache.org/maven2/org/springframework/security/spring-security-oauth2-client/6.4.13/spring-security-oauth2-client-6.4.13-sources.jar) |
| `org.springframework.security:spring-security-oauth2-core-6.4.13.jar` | `Apache-2.0` | G, A, I | [fontes](https://repo.maven.apache.org/maven2/org/springframework/security/spring-security-oauth2-core/6.4.13/spring-security-oauth2-core-6.4.13-sources.jar) |
| `org.springframework.security:spring-security-oauth2-jose-6.4.13.jar` | `Apache-2.0` | G, A, I | [fontes](https://repo.maven.apache.org/maven2/org/springframework/security/spring-security-oauth2-jose/6.4.13/spring-security-oauth2-jose-6.4.13-sources.jar) |
| `org.springframework.security:spring-security-oauth2-resource-server-6.4.13.jar` | `Apache-2.0` | G, A, I | [fontes](https://repo.maven.apache.org/maven2/org/springframework/security/spring-security-oauth2-resource-server/6.4.13/spring-security-oauth2-resource-server-6.4.13-sources.jar) |
| `org.springframework.security:spring-security-web-6.4.13.jar` | `Apache-2.0` | G, A, I | [fontes](https://repo.maven.apache.org/maven2/org/springframework/security/spring-security-web/6.4.13/spring-security-web-6.4.13-sources.jar) |
| `org.springframework:spring-aop-6.2.15.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/spring-aop/6.2.15/spring-aop-6.2.15-sources.jar) |
| `org.springframework:spring-aspects-6.2.15.jar` | `Apache-2.0` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/spring-aspects/6.2.15/spring-aspects-6.2.15-sources.jar) |
| `org.springframework:spring-beans-6.2.15.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/spring-beans/6.2.15/spring-beans-6.2.15-sources.jar) |
| `org.springframework:spring-context-support-6.2.15.jar` | `Apache-2.0` | G, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/spring-context-support/6.2.15/spring-context-support-6.2.15-sources.jar) |
| `org.springframework:spring-context-6.2.15.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/spring-context/6.2.15/spring-context-6.2.15-sources.jar) |
| `org.springframework:spring-core-6.2.15.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/spring-core/6.2.15/spring-core-6.2.15-sources.jar) |
| `org.springframework:spring-expression-6.2.15.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/spring-expression/6.2.15/spring-expression-6.2.15-sources.jar) |
| `org.springframework:spring-jcl-6.2.15.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/spring-jcl/6.2.15/spring-jcl-6.2.15-sources.jar) |
| `org.springframework:spring-jdbc-6.2.15.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/spring-jdbc/6.2.15/spring-jdbc-6.2.15-sources.jar) |
| `org.springframework:spring-messaging-6.2.15.jar` | `Apache-2.0` | A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/spring-messaging/6.2.15/spring-messaging-6.2.15-sources.jar) |
| `org.springframework:spring-orm-6.2.15.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/spring-orm/6.2.15/spring-orm-6.2.15-sources.jar) |
| `org.springframework:spring-oxm-6.2.15.jar` | `Apache-2.0` | G, I | [fontes](https://repo.maven.apache.org/maven2/org/springframework/spring-oxm/6.2.15/spring-oxm-6.2.15-sources.jar) |
| `org.springframework:spring-tx-6.2.15.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/spring-tx/6.2.15/spring-tx-6.2.15-sources.jar) |
| `org.springframework:spring-web-6.2.15.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/spring-web/6.2.15/spring-web-6.2.15-sources.jar) |
| `org.springframework:spring-webflux-6.2.15.jar` | `Apache-2.0` | G | [fontes](https://repo.maven.apache.org/maven2/org/springframework/spring-webflux/6.2.15/spring-webflux-6.2.15-sources.jar) |
| `org.springframework:spring-webmvc-6.2.15.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/springframework/spring-webmvc/6.2.15/spring-webmvc-6.2.15-sources.jar) |
| `org.thymeleaf:thymeleaf-spring6-3.1.3.RELEASE.jar` | `Apache-2.0` | A | [fontes](https://repo.maven.apache.org/maven2/org/thymeleaf/thymeleaf-spring6/3.1.3.RELEASE/thymeleaf-spring6-3.1.3.RELEASE-sources.jar) |
| `org.thymeleaf:thymeleaf-3.1.3.RELEASE.jar` | `Apache-2.0` | A | [fontes](https://repo.maven.apache.org/maven2/org/thymeleaf/thymeleaf/3.1.3.RELEASE/thymeleaf-3.1.3.RELEASE-sources.jar) |
| `org.unbescape:unbescape-1.1.6.RELEASE.jar` | `Apache-2.0` | A | [fontes](https://repo.maven.apache.org/maven2/org/unbescape/unbescape/1.1.6.RELEASE/unbescape-1.1.6.RELEASE-sources.jar) |
| `org.webjars:swagger-ui-5.18.2.jar` | `Apache-2.0` | G, A, I | [fontes](https://github.com/swagger-api/swagger-ui/tree/v5.18.2) |
| `org.webjars:webjars-locator-lite-1.0.1.jar` | `MIT` | G, A, I | [fontes](https://repo.maven.apache.org/maven2/org/webjars/webjars-locator-lite/1.0.1/webjars-locator-lite-1.0.1-sources.jar) |
| `org.yaml:snakeyaml-2.3.jar` | `Apache-2.0` | G, A, I, M | [fontes](https://repo.maven.apache.org/maven2/org/yaml/snakeyaml/2.3/snakeyaml-2.3-sources.jar) |

## Textos de licenças consolidados pelo ORT

O relatório abaixo reúne os textos disponíveis a partir dos metadados do Analyzer.
A presença de um texto neste anexo não implica que todo serviço distribua componentes
sob essa licença; a associação componente/licença é a tabela acima.

````text
This project contains or depends on third-party software components pursuant to the following licenses:

----

                    GNU AFFERO GENERAL PUBLIC LICENSE
                       Version 3, 19 November 2007

 Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>
 Everyone is permitted to copy and distribute verbatim copies
 of this license document, but changing it is not allowed.

                            Preamble

  The GNU Affero General Public License is a free, copyleft license for
software and other kinds of works, specifically designed to ensure
cooperation with the community in the case of network server software.

  The licenses for most software and other practical works are designed
to take away your freedom to share and change the works.  By contrast,
our General Public Licenses are intended to guarantee your freedom to
share and change all versions of a program--to make sure it remains free
software for all its users.

  When we speak of free software, we are referring to freedom, not
price.  Our General Public Licenses are designed to make sure that you
have the freedom to distribute copies of free software (and charge for
them if you wish), that you receive source code or can get it if you
want it, that you can change the software or use pieces of it in new
free programs, and that you know you can do these things.

  Developers that use our General Public Licenses protect your rights
with two steps: (1) assert copyright on the software, and (2) offer
you this License which gives you legal permission to copy, distribute
and/or modify the software.

  A secondary benefit of defending all users' freedom is that
improvements made in alternate versions of the program, if they
receive widespread use, become available for other developers to
incorporate.  Many developers of free software are heartened and
encouraged by the resulting cooperation.  However, in the case of
software used on network servers, this result may fail to come about.
The GNU General Public License permits making a modified version and
letting the public access it on a server without ever releasing its
source code to the public.

  The GNU Affero General Public License is designed specifically to
ensure that, in such cases, the modified source code becomes available
to the community.  It requires the operator of a network server to
provide the source code of the modified version running there to the
users of that server.  Therefore, public use of a modified version, on
a publicly accessible server, gives the public access to the source
code of the modified version.

  An older license, called the Affero General Public License and
published by Affero, was designed to accomplish similar goals.  This is
a different license, not a version of the Affero GPL, but Affero has
released a new version of the Affero GPL which permits relicensing under
this license.

  The precise terms and conditions for copying, distribution and
modification follow.

                       TERMS AND CONDITIONS

  0. Definitions.

  "This License" refers to version 3 of the GNU Affero General Public License.

  "Copyright" also means copyright-like laws that apply to other kinds of
works, such as semiconductor masks.

  "The Program" refers to any copyrightable work licensed under this
License.  Each licensee is addressed as "you".  "Licensees" and
"recipients" may be individuals or organizations.

  To "modify" a work means to copy from or adapt all or part of the work
in a fashion requiring copyright permission, other than the making of an
exact copy.  The resulting work is called a "modified version" of the
earlier work or a work "based on" the earlier work.

  A "covered work" means either the unmodified Program or a work based
on the Program.

  To "propagate" a work means to do anything with it that, without
permission, would make you directly or secondarily liable for
infringement under applicable copyright law, except executing it on a
computer or modifying a private copy.  Propagation includes copying,
distribution (with or without modification), making available to the
public, and in some countries other activities as well.

  To "convey" a work means any kind of propagation that enables other
parties to make or receive copies.  Mere interaction with a user through
a computer network, with no transfer of a copy, is not conveying.

  An interactive user interface displays "Appropriate Legal Notices"
to the extent that it includes a convenient and prominently visible
feature that (1) displays an appropriate copyright notice, and (2)
tells the user that there is no warranty for the work (except to the
extent that warranties are provided), that licensees may convey the
work under this License, and how to view a copy of this License.  If
the interface presents a list of user commands or options, such as a
menu, a prominent item in the list meets this criterion.

  1. Source Code.

  The "source code" for a work means the preferred form of the work
for making modifications to it.  "Object code" means any non-source
form of a work.

  A "Standard Interface" means an interface that either is an official
standard defined by a recognized standards body, or, in the case of
interfaces specified for a particular programming language, one that
is widely used among developers working in that language.

  The "System Libraries" of an executable work include anything, other
than the work as a whole, that (a) is included in the normal form of
packaging a Major Component, but which is not part of that Major
Component, and (b) serves only to enable use of the work with that
Major Component, or to implement a Standard Interface for which an
implementation is available to the public in source code form.  A
"Major Component", in this context, means a major essential component
(kernel, window system, and so on) of the specific operating system
(if any) on which the executable work runs, or a compiler used to
produce the work, or an object code interpreter used to run it.

  The "Corresponding Source" for a work in object code form means all
the source code needed to generate, install, and (for an executable
work) run the object code and to modify the work, including scripts to
control those activities.  However, it does not include the work's
System Libraries, or general-purpose tools or generally available free
programs which are used unmodified in performing those activities but
which are not part of the work.  For example, Corresponding Source
includes interface definition files associated with source files for
the work, and the source code for shared libraries and dynamically
linked subprograms that the work is specifically designed to require,
such as by intimate data communication or control flow between those
subprograms and other parts of the work.

  The Corresponding Source need not include anything that users
can regenerate automatically from other parts of the Corresponding
Source.

  The Corresponding Source for a work in source code form is that
same work.

  2. Basic Permissions.

  All rights granted under this License are granted for the term of
copyright on the Program, and are irrevocable provided the stated
conditions are met.  This License explicitly affirms your unlimited
permission to run the unmodified Program.  The output from running a
covered work is covered by this License only if the output, given its
content, constitutes a covered work.  This License acknowledges your
rights of fair use or other equivalent, as provided by copyright law.

  You may make, run and propagate covered works that you do not
convey, without conditions so long as your license otherwise remains
in force.  You may convey covered works to others for the sole purpose
of having them make modifications exclusively for you, or provide you
with facilities for running those works, provided that you comply with
the terms of this License in conveying all material for which you do
not control copyright.  Those thus making or running the covered works
for you must do so exclusively on your behalf, under your direction
and control, on terms that prohibit them from making any copies of
your copyrighted material outside their relationship with you.

  Conveying under any other circumstances is permitted solely under
the conditions stated below.  Sublicensing is not allowed; section 10
makes it unnecessary.

  3. Protecting Users' Legal Rights From Anti-Circumvention Law.

  No covered work shall be deemed part of an effective technological
measure under any applicable law fulfilling obligations under article
11 of the WIPO copyright treaty adopted on 20 December 1996, or
similar laws prohibiting or restricting circumvention of such
measures.

  When you convey a covered work, you waive any legal power to forbid
circumvention of technological measures to the extent such circumvention
is effected by exercising rights under this License with respect to
the covered work, and you disclaim any intention to limit operation or
modification of the work as a means of enforcing, against the work's
users, your or third parties' legal rights to forbid circumvention of
technological measures.

  4. Conveying Verbatim Copies.

  You may convey verbatim copies of the Program's source code as you
receive it, in any medium, provided that you conspicuously and
appropriately publish on each copy an appropriate copyright notice;
keep intact all notices stating that this License and any
non-permissive terms added in accord with section 7 apply to the code;
keep intact all notices of the absence of any warranty; and give all
recipients a copy of this License along with the Program.

  You may charge any price or no price for each copy that you convey,
and you may offer support or warranty protection for a fee.

  5. Conveying Modified Source Versions.

  You may convey a work based on the Program, or the modifications to
produce it from the Program, in the form of source code under the
terms of section 4, provided that you also meet all of these conditions:

    a) The work must carry prominent notices stating that you modified
    it, and giving a relevant date.

    b) The work must carry prominent notices stating that it is
    released under this License and any conditions added under section
    7.  This requirement modifies the requirement in section 4 to
    "keep intact all notices".

    c) You must license the entire work, as a whole, under this
    License to anyone who comes into possession of a copy.  This
    License will therefore apply, along with any applicable section 7
    additional terms, to the whole of the work, and all its parts,
    regardless of how they are packaged.  This License gives no
    permission to license the work in any other way, but it does not
    invalidate such permission if you have separately received it.

    d) If the work has interactive user interfaces, each must display
    Appropriate Legal Notices; however, if the Program has interactive
    interfaces that do not display Appropriate Legal Notices, your
    work need not make them do so.

  A compilation of a covered work with other separate and independent
works, which are not by their nature extensions of the covered work,
and which are not combined with it such as to form a larger program,
in or on a volume of a storage or distribution medium, is called an
"aggregate" if the compilation and its resulting copyright are not
used to limit the access or legal rights of the compilation's users
beyond what the individual works permit.  Inclusion of a covered work
in an aggregate does not cause this License to apply to the other
parts of the aggregate.

  6. Conveying Non-Source Forms.

  You may convey a covered work in object code form under the terms
of sections 4 and 5, provided that you also convey the
machine-readable Corresponding Source under the terms of this License,
in one of these ways:

    a) Convey the object code in, or embodied in, a physical product
    (including a physical distribution medium), accompanied by the
    Corresponding Source fixed on a durable physical medium
    customarily used for software interchange.

    b) Convey the object code in, or embodied in, a physical product
    (including a physical distribution medium), accompanied by a
    written offer, valid for at least three years and valid for as
    long as you offer spare parts or customer support for that product
    model, to give anyone who possesses the object code either (1) a
    copy of the Corresponding Source for all the software in the
    product that is covered by this License, on a durable physical
    medium customarily used for software interchange, for a price no
    more than your reasonable cost of physically performing this
    conveying of source, or (2) access to copy the
    Corresponding Source from a network server at no charge.

    c) Convey individual copies of the object code with a copy of the
    written offer to provide the Corresponding Source.  This
    alternative is allowed only occasionally and noncommercially, and
    only if you received the object code with such an offer, in accord
    with subsection 6b.

    d) Convey the object code by offering access from a designated
    place (gratis or for a charge), and offer equivalent access to the
    Corresponding Source in the same way through the same place at no
    further charge.  You need not require recipients to copy the
    Corresponding Source along with the object code.  If the place to
    copy the object code is a network server, the Corresponding Source
    may be on a different server (operated by you or a third party)
    that supports equivalent copying facilities, provided you maintain
    clear directions next to the object code saying where to find the
    Corresponding Source.  Regardless of what server hosts the
    Corresponding Source, you remain obligated to ensure that it is
    available for as long as needed to satisfy these requirements.

    e) Convey the object code using peer-to-peer transmission, provided
    you inform other peers where the object code and Corresponding
    Source of the work are being offered to the general public at no
    charge under subsection 6d.

  A separable portion of the object code, whose source code is excluded
from the Corresponding Source as a System Library, need not be
included in conveying the object code work.

  A "User Product" is either (1) a "consumer product", which means any
tangible personal property which is normally used for personal, family,
or household purposes, or (2) anything designed or sold for incorporation
into a dwelling.  In determining whether a product is a consumer product,
doubtful cases shall be resolved in favor of coverage.  For a particular
product received by a particular user, "normally used" refers to a
typical or common use of that class of product, regardless of the status
of the particular user or of the way in which the particular user
actually uses, or expects or is expected to use, the product.  A product
is a consumer product regardless of whether the product has substantial
commercial, industrial or non-consumer uses, unless such uses represent
the only significant mode of use of the product.

  "Installation Information" for a User Product means any methods,
procedures, authorization keys, or other information required to install
and execute modified versions of a covered work in that User Product from
a modified version of its Corresponding Source.  The information must
suffice to ensure that the continued functioning of the modified object
code is in no case prevented or interfered with solely because
modification has been made.

  If you convey an object code work under this section in, or with, or
specifically for use in, a User Product, and the conveying occurs as
part of a transaction in which the right of possession and use of the
User Product is transferred to the recipient in perpetuity or for a
fixed term (regardless of how the transaction is characterized), the
Corresponding Source conveyed under this section must be accompanied
by the Installation Information.  But this requirement does not apply
if neither you nor any third party retains the ability to install
modified object code on the User Product (for example, the work has
been installed in ROM).

  The requirement to provide Installation Information does not include a
requirement to continue to provide support service, warranty, or updates
for a work that has been modified or installed by the recipient, or for
the User Product in which it has been modified or installed.  Access to a
network may be denied when the modification itself materially and
adversely affects the operation of the network or violates the rules and
protocols for communication across the network.

  Corresponding Source conveyed, and Installation Information provided,
in accord with this section must be in a format that is publicly
documented (and with an implementation available to the public in
source code form), and must require no special password or key for
unpacking, reading or copying.

  7. Additional Terms.

  "Additional permissions" are terms that supplement the terms of this
License by making exceptions from one or more of its conditions.
Additional permissions that are applicable to the entire Program shall
be treated as though they were included in this License, to the extent
that they are valid under applicable law.  If additional permissions
apply only to part of the Program, that part may be used separately
under those permissions, but the entire Program remains governed by
this License without regard to the additional permissions.

  When you convey a copy of a covered work, you may at your option
remove any additional permissions from that copy, or from any part of
it.  (Additional permissions may be written to require their own
removal in certain cases when you modify the work.)  You may place
additional permissions on material, added by you to a covered work,
for which you have or can give appropriate copyright permission.

  Notwithstanding any other provision of this License, for material you
add to a covered work, you may (if authorized by the copyright holders of
that material) supplement the terms of this License with terms:

    a) Disclaiming warranty or limiting liability differently from the
    terms of sections 15 and 16 of this License; or

    b) Requiring preservation of specified reasonable legal notices or
    author attributions in that material or in the Appropriate Legal
    Notices displayed by works containing it; or

    c) Prohibiting misrepresentation of the origin of that material, or
    requiring that modified versions of such material be marked in
    reasonable ways as different from the original version; or

    d) Limiting the use for publicity purposes of names of licensors or
    authors of the material; or

    e) Declining to grant rights under trademark law for use of some
    trade names, trademarks, or service marks; or

    f) Requiring indemnification of licensors and authors of that
    material by anyone who conveys the material (or modified versions of
    it) with contractual assumptions of liability to the recipient, for
    any liability that these contractual assumptions directly impose on
    those licensors and authors.

  All other non-permissive additional terms are considered "further
restrictions" within the meaning of section 10.  If the Program as you
received it, or any part of it, contains a notice stating that it is
governed by this License along with a term that is a further
restriction, you may remove that term.  If a license document contains
a further restriction but permits relicensing or conveying under this
License, you may add to a covered work material governed by the terms
of that license document, provided that the further restriction does
not survive such relicensing or conveying.

  If you add terms to a covered work in accord with this section, you
must place, in the relevant source files, a statement of the
additional terms that apply to those files, or a notice indicating
where to find the applicable terms.

  Additional terms, permissive or non-permissive, may be stated in the
form of a separately written license, or stated as exceptions;
the above requirements apply either way.

  8. Termination.

  You may not propagate or modify a covered work except as expressly
provided under this License.  Any attempt otherwise to propagate or
modify it is void, and will automatically terminate your rights under
this License (including any patent licenses granted under the third
paragraph of section 11).

  However, if you cease all violation of this License, then your
license from a particular copyright holder is reinstated (a)
provisionally, unless and until the copyright holder explicitly and
finally terminates your license, and (b) permanently, if the copyright
holder fails to notify you of the violation by some reasonable means
prior to 60 days after the cessation.

  Moreover, your license from a particular copyright holder is
reinstated permanently if the copyright holder notifies you of the
violation by some reasonable means, this is the first time you have
received notice of violation of this License (for any work) from that
copyright holder, and you cure the violation prior to 30 days after
your receipt of the notice.

  Termination of your rights under this section does not terminate the
licenses of parties who have received copies or rights from you under
this License.  If your rights have been terminated and not permanently
reinstated, you do not qualify to receive new licenses for the same
material under section 10.

  9. Acceptance Not Required for Having Copies.

  You are not required to accept this License in order to receive or
run a copy of the Program.  Ancillary propagation of a covered work
occurring solely as a consequence of using peer-to-peer transmission
to receive a copy likewise does not require acceptance.  However,
nothing other than this License grants you permission to propagate or
modify any covered work.  These actions infringe copyright if you do
not accept this License.  Therefore, by modifying or propagating a
covered work, you indicate your acceptance of this License to do so.

  10. Automatic Licensing of Downstream Recipients.

  Each time you convey a covered work, the recipient automatically
receives a license from the original licensors, to run, modify and
propagate that work, subject to this License.  You are not responsible
for enforcing compliance by third parties with this License.

  An "entity transaction" is a transaction transferring control of an
organization, or substantially all assets of one, or subdividing an
organization, or merging organizations.  If propagation of a covered
work results from an entity transaction, each party to that
transaction who receives a copy of the work also receives whatever
licenses to the work the party's predecessor in interest had or could
give under the previous paragraph, plus a right to possession of the
Corresponding Source of the work from the predecessor in interest, if
the predecessor has it or can get it with reasonable efforts.

  You may not impose any further restrictions on the exercise of the
rights granted or affirmed under this License.  For example, you may
not impose a license fee, royalty, or other charge for exercise of
rights granted under this License, and you may not initiate litigation
(including a cross-claim or counterclaim in a lawsuit) alleging that
any patent claim is infringed by making, using, selling, offering for
sale, or importing the Program or any portion of it.

  11. Patents.

  A "contributor" is a copyright holder who authorizes use under this
License of the Program or a work on which the Program is based.  The
work thus licensed is called the contributor's "contributor version".

  A contributor's "essential patent claims" are all patent claims
owned or controlled by the contributor, whether already acquired or
hereafter acquired, that would be infringed by some manner, permitted
by this License, of making, using, or selling its contributor version,
but do not include claims that would be infringed only as a
consequence of further modification of the contributor version.  For
purposes of this definition, "control" includes the right to grant
patent sublicenses in a manner consistent with the requirements of
this License.

  Each contributor grants you a non-exclusive, worldwide, royalty-free
patent license under the contributor's essential patent claims, to
make, use, sell, offer for sale, import and otherwise run, modify and
propagate the contents of its contributor version.

  In the following three paragraphs, a "patent license" is any express
agreement or commitment, however denominated, not to enforce a patent
(such as an express permission to practice a patent or covenant not to
sue for patent infringement).  To "grant" such a patent license to a
party means to make such an agreement or commitment not to enforce a
patent against the party.

  If you convey a covered work, knowingly relying on a patent license,
and the Corresponding Source of the work is not available for anyone
to copy, free of charge and under the terms of this License, through a
publicly available network server or other readily accessible means,
then you must either (1) cause the Corresponding Source to be so
available, or (2) arrange to deprive yourself of the benefit of the
patent license for this particular work, or (3) arrange, in a manner
consistent with the requirements of this License, to extend the patent
license to downstream recipients.  "Knowingly relying" means you have
actual knowledge that, but for the patent license, your conveying the
covered work in a country, or your recipient's use of the covered work
in a country, would infringe one or more identifiable patents in that
country that you have reason to believe are valid.

  If, pursuant to or in connection with a single transaction or
arrangement, you convey, or propagate by procuring conveyance of, a
covered work, and grant a patent license to some of the parties
receiving the covered work authorizing them to use, propagate, modify
or convey a specific copy of the covered work, then the patent license
you grant is automatically extended to all recipients of the covered
work and works based on it.

  A patent license is "discriminatory" if it does not include within
the scope of its coverage, prohibits the exercise of, or is
conditioned on the non-exercise of one or more of the rights that are
specifically granted under this License.  You may not convey a covered
work if you are a party to an arrangement with a third party that is
in the business of distributing software, under which you make payment
to the third party based on the extent of your activity of conveying
the work, and under which the third party grants, to any of the
parties who would receive the covered work from you, a discriminatory
patent license (a) in connection with copies of the covered work
conveyed by you (or copies made from those copies), or (b) primarily
for and in connection with specific products or compilations that
contain the covered work, unless you entered into that arrangement,
or that patent license was granted, prior to 28 March 2007.

  Nothing in this License shall be construed as excluding or limiting
any implied license or other defenses to infringement that may
otherwise be available to you under applicable patent law.

  12. No Surrender of Others' Freedom.

  If conditions are imposed on you (whether by court order, agreement or
otherwise) that contradict the conditions of this License, they do not
excuse you from the conditions of this License.  If you cannot convey a
covered work so as to satisfy simultaneously your obligations under this
License and any other pertinent obligations, then as a consequence you may
not convey it at all.  For example, if you agree to terms that obligate you
to collect a royalty for further conveying from those to whom you convey
the Program, the only way you could satisfy both those terms and this
License would be to refrain entirely from conveying the Program.

  13. Remote Network Interaction; Use with the GNU General Public License.

  Notwithstanding any other provision of this License, if you modify the
Program, your modified version must prominently offer all users
interacting with it remotely through a computer network (if your version
supports such interaction) an opportunity to receive the Corresponding
Source of your version by providing access to the Corresponding Source
from a network server at no charge, through some standard or customary
means of facilitating copying of software.  This Corresponding Source
shall include the Corresponding Source for any work covered by version 3
of the GNU General Public License that is incorporated pursuant to the
following paragraph.

  Notwithstanding any other provision of this License, you have
permission to link or combine any covered work with a work licensed
under version 3 of the GNU General Public License into a single
combined work, and to convey the resulting work.  The terms of this
License will continue to apply to the part which is the covered work,
but the work with which it is combined will remain governed by version
3 of the GNU General Public License.

  14. Revised Versions of this License.

  The Free Software Foundation may publish revised and/or new versions of
the GNU Affero General Public License from time to time.  Such new versions
will be similar in spirit to the present version, but may differ in detail to
address new problems or concerns.

  Each version is given a distinguishing version number.  If the
Program specifies that a certain numbered version of the GNU Affero General
Public License "or any later version" applies to it, you have the
option of following the terms and conditions either of that numbered
version or of any later version published by the Free Software
Foundation.  If the Program does not specify a version number of the
GNU Affero General Public License, you may choose any version ever published
by the Free Software Foundation.

  If the Program specifies that a proxy can decide which future
versions of the GNU Affero General Public License can be used, that proxy's
public statement of acceptance of a version permanently authorizes you
to choose that version for the Program.

  Later license versions may give you additional or different
permissions.  However, no additional obligations are imposed on any
author or copyright holder as a result of your choosing to follow a
later version.

  15. Disclaimer of Warranty.

  THERE IS NO WARRANTY FOR THE PROGRAM, TO THE EXTENT PERMITTED BY
APPLICABLE LAW.  EXCEPT WHEN OTHERWISE STATED IN WRITING THE COPYRIGHT
HOLDERS AND/OR OTHER PARTIES PROVIDE THE PROGRAM "AS IS" WITHOUT WARRANTY
OF ANY KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE.  THE ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE PROGRAM
IS WITH YOU.  SHOULD THE PROGRAM PROVE DEFECTIVE, YOU ASSUME THE COST OF
ALL NECESSARY SERVICING, REPAIR OR CORRECTION.

  16. Limitation of Liability.

  IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN WRITING
WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MODIFIES AND/OR CONVEYS
THE PROGRAM AS PERMITTED ABOVE, BE LIABLE TO YOU FOR DAMAGES, INCLUDING ANY
GENERAL, SPECIAL, INCIDENTAL OR CONSEQUENTIAL DAMAGES ARISING OUT OF THE
USE OR INABILITY TO USE THE PROGRAM (INCLUDING BUT NOT LIMITED TO LOSS OF
DATA OR DATA BEING RENDERED INACCURATE OR LOSSES SUSTAINED BY YOU OR THIRD
PARTIES OR A FAILURE OF THE PROGRAM TO OPERATE WITH ANY OTHER PROGRAMS),
EVEN IF SUCH HOLDER OR OTHER PARTY HAS BEEN ADVISED OF THE POSSIBILITY OF
SUCH DAMAGES.

  17. Interpretation of Sections 15 and 16.

  If the disclaimer of warranty and limitation of liability provided
above cannot be given local legal effect according to their terms,
reviewing courts shall apply local law that most closely approximates
an absolute waiver of all civil liability in connection with the
Program, unless a warranty or assumption of liability accompanies a
copy of the Program in return for a fee.

                     END OF TERMS AND CONDITIONS

            How to Apply These Terms to Your New Programs

  If you develop a new program, and you want it to be of the greatest
possible use to the public, the best way to achieve this is to make it
free software which everyone can redistribute and change under these terms.

  To do so, attach the following notices to the program.  It is safest
to attach them to the start of each source file to most effectively
state the exclusion of warranty; and each file should have at least
the "copyright" line and a pointer to where the full notice is found.

    <one line to give the program's name and a brief idea of what it does.>
    Copyright (C) <year>  <name of author>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.

Also add information on how to contact you by electronic and paper mail.

  If your software can interact with users remotely through a computer
network, you should also make sure that it provides a way for users to
get its source.  For example, if your program is a web application, its
interface could display a "Source" link that leads users to an archive
of the code.  There are many ways you could offer source, and different
solutions will be better for different programs; see section 13 for the
specific requirements.

  You should also get your employer (if you work as a programmer) or school,
if any, to sign a "copyright disclaimer" for the program, if necessary.
For more information on this, and how to apply and follow the GNU AGPL, see
<https://www.gnu.org/licenses/>.

----

                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

----

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

Redistributions of source code must retain the above copyright notice, this list
of conditions and the following disclaimer.

Redistributions in binary form must reproduce the above copyright notice, this
list of conditions and the following disclaimer in the documentation and/or
other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

----

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

Redistributions of source code must retain the above copyright notice, this list
of conditions and the following disclaimer.

Redistributions in binary form must reproduce the above copyright notice, this
list of conditions and the following disclaimer in the documentation and/or
other materials provided with the distribution.

Neither the name of the ORGANIZATION nor the names of its contributors may be
used to endorse or promote products derived from this software without specific
prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS
BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

----

Creative Commons Legal Code

CC0 1.0 Universal

    CREATIVE COMMONS CORPORATION IS NOT A LAW FIRM AND DOES NOT PROVIDE
    LEGAL SERVICES. DISTRIBUTION OF THIS DOCUMENT DOES NOT CREATE AN
    ATTORNEY-CLIENT RELATIONSHIP. CREATIVE COMMONS PROVIDES THIS
    INFORMATION ON AN "AS-IS" BASIS. CREATIVE COMMONS MAKES NO WARRANTIES
    REGARDING THE USE OF THIS DOCUMENT OR THE INFORMATION OR WORKS
    PROVIDED HEREUNDER, AND DISCLAIMS LIABILITY FOR DAMAGES RESULTING FROM
    THE USE OF THIS DOCUMENT OR THE INFORMATION OR WORKS PROVIDED
    HEREUNDER.

Statement of Purpose

The laws of most jurisdictions throughout the world automatically confer
exclusive Copyright and Related Rights (defined below) upon the creator
and subsequent owner(s) (each and all, an "owner") of an original work of
authorship and/or a database (each, a "Work").

Certain owners wish to permanently relinquish those rights to a Work for
the purpose of contributing to a commons of creative, cultural and
scientific works ("Commons") that the public can reliably and without fear
of later claims of infringement build upon, modify, incorporate in other
works, reuse and redistribute as freely as possible in any form whatsoever
and for any purposes, including without limitation commercial purposes.
These owners may contribute to the Commons to promote the ideal of a free
culture and the further production of creative, cultural and scientific
works, or to gain reputation or greater distribution for their Work in
part through the use and efforts of others.

For these and/or other purposes and motivations, and without any
expectation of additional consideration or compensation, the person
associating CC0 with a Work (the "Affirmer"), to the extent that he or she
is an owner of Copyright and Related Rights in the Work, voluntarily
elects to apply CC0 to the Work and publicly distribute the Work under its
terms, with knowledge of his or her Copyright and Related Rights in the
Work and the meaning and intended legal effect of CC0 on those rights.

1. Copyright and Related Rights. A Work made available under CC0 may be
protected by copyright and related or neighboring rights ("Copyright and
Related Rights"). Copyright and Related Rights include, but are not
limited to, the following:

  i. the right to reproduce, adapt, distribute, perform, display,
     communicate, and translate a Work;
 ii. moral rights retained by the original author(s) and/or performer(s);
iii. publicity and privacy rights pertaining to a person's image or
     likeness depicted in a Work;
 iv. rights protecting against unfair competition in regards to a Work,
     subject to the limitations in paragraph 4(a), below;
  v. rights protecting the extraction, dissemination, use and reuse of data
     in a Work;
 vi. database rights (such as those arising under Directive 96/9/EC of the
     European Parliament and of the Council of 11 March 1996 on the legal
     protection of databases, and under any national implementation
     thereof, including any amended or successor version of such
     directive); and
vii. other similar, equivalent or corresponding rights throughout the
     world based on applicable law or treaty, and any national
     implementations thereof.

2. Waiver. To the greatest extent permitted by, but not in contravention
of, applicable law, Affirmer hereby overtly, fully, permanently,
irrevocably and unconditionally waives, abandons, and surrenders all of
Affirmer's Copyright and Related Rights and associated claims and causes
of action, whether now known or unknown (including existing as well as
future claims and causes of action), in the Work (i) in all territories
worldwide, (ii) for the maximum duration provided by applicable law or
treaty (including future time extensions), (iii) in any current or future
medium and for any number of copies, and (iv) for any purpose whatsoever,
including without limitation commercial, advertising or promotional
purposes (the "Waiver"). Affirmer makes the Waiver for the benefit of each
member of the public at large and to the detriment of Affirmer's heirs and
successors, fully intending that such Waiver shall not be subject to
revocation, rescission, cancellation, termination, or any other legal or
equitable action to disrupt the quiet enjoyment of the Work by the public
as contemplated by Affirmer's express Statement of Purpose.

3. Public License Fallback. Should any part of the Waiver for any reason
be judged legally invalid or ineffective under applicable law, then the
Waiver shall be preserved to the maximum extent permitted taking into
account Affirmer's express Statement of Purpose. In addition, to the
extent the Waiver is so judged Affirmer hereby grants to each affected
person a royalty-free, non transferable, non sublicensable, non exclusive,
irrevocable and unconditional license to exercise Affirmer's Copyright and
Related Rights in the Work (i) in all territories worldwide, (ii) for the
maximum duration provided by applicable law or treaty (including future
time extensions), (iii) in any current or future medium and for any number
of copies, and (iv) for any purpose whatsoever, including without
limitation commercial, advertising or promotional purposes (the
"License"). The License shall be deemed effective as of the date CC0 was
applied by Affirmer to the Work. Should any part of the License for any
reason be judged legally invalid or ineffective under applicable law, such
partial invalidity or ineffectiveness shall not invalidate the remainder
of the License, and in such case Affirmer hereby affirms that he or she
will not (i) exercise any of his or her remaining Copyright and Related
Rights in the Work or (ii) assert any associated claims and causes of
action with respect to the Work, in either case contrary to Affirmer's
express Statement of Purpose.

4. Limitations and Disclaimers.

 a. No trademark or patent rights held by Affirmer are waived, abandoned,
    surrendered, licensed or otherwise affected by this document.
 b. Affirmer offers the Work as-is and makes no representations or
    warranties of any kind concerning the Work, express, implied,
    statutory or otherwise, including without limitation warranties of
    title, merchantability, fitness for a particular purpose, non
    infringement, or the absence of latent or other defects, accuracy, or
    the present or absence of errors, whether or not discoverable, all to
    the greatest extent permissible under applicable law.
 c. Affirmer disclaims responsibility for clearing rights of other persons
    that may apply to the Work or any use thereof, including without
    limitation any person's Copyright and Related Rights in the Work.
    Further, Affirmer disclaims responsibility for obtaining any necessary
    consents, permissions or other rights required for any use of the
    Work.
 d. Affirmer understands and acknowledges that Creative Commons is not a
    party to this document and has no duty or obligation with respect to
    this CC0 or use of the Work.

----

Eclipse Public License - v 1.0

THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.

1. DEFINITIONS

"Contribution" means:

a) in the case of the initial Contributor, the initial code and documentation distributed under this Agreement, and
b) in the case of each subsequent Contributor:

i) changes to the Program, and

ii) additions to the Program;

where such changes and/or additions to the Program originate from and are distributed by that particular Contributor. A Contribution 'originates' from a Contributor if it was added to the Program by such Contributor itself or anyone acting on such Contributor's behalf. Contributions do not include additions to the Program which: (i) are separate modules of software distributed in conjunction with the Program under their own license agreement, and (ii) are not derivative works of the Program.

"Contributor" means any person or entity that distributes the Program.

"Licensed Patents " mean patent claims licensable by a Contributor which are necessarily infringed by the use or sale of its Contribution alone or when combined with the Program.

"Program" means the Contributions distributed in accordance with this Agreement.

"Recipient" means anyone who receives the Program under this Agreement, including all Contributors.

2. GRANT OF RIGHTS

a) Subject to the terms of this Agreement, each Contributor hereby grants Recipient a non-exclusive, worldwide, royalty-free copyright license to reproduce, prepare derivative works of, publicly display, publicly perform, distribute and sublicense the Contribution of such Contributor, if any, and such derivative works, in source code and object code form.

b) Subject to the terms of this Agreement, each Contributor hereby grants Recipient a non-exclusive, worldwide, royalty-free patent license under Licensed Patents to make, use, sell, offer to sell, import and otherwise transfer the Contribution of such Contributor, if any, in source code and object code form. This patent license shall apply to the combination of the Contribution and the Program if, at the time the Contribution is added by the Contributor, such addition of the Contribution causes such combination to be covered by the Licensed Patents. The patent license shall not apply to any other combinations which include the Contribution. No hardware per se is licensed hereunder.

c) Recipient understands that although each Contributor grants the licenses to its Contributions set forth herein, no assurances are provided by any Contributor that the Program does not infringe the patent or other intellectual property rights of any other entity. Each Contributor disclaims any liability to Recipient for claims brought by any other entity based on infringement of intellectual property rights or otherwise. As a condition to exercising the rights and licenses granted hereunder, each Recipient hereby assumes sole responsibility to secure any other intellectual property rights needed, if any. For example, if a third party patent license is required to allow Recipient to distribute the Program, it is Recipient's responsibility to acquire that license before distributing the Program.

d) Each Contributor represents that to its knowledge it has sufficient copyright rights in its Contribution, if any, to grant the copyright license set forth in this Agreement.

3. REQUIREMENTS

A Contributor may choose to distribute the Program in object code form under its own license agreement, provided that:

a) it complies with the terms and conditions of this Agreement; and

b) its license agreement:

i) effectively disclaims on behalf of all Contributors all warranties and conditions, express and implied, including warranties or conditions of title and non-infringement, and implied warranties or conditions of merchantability and fitness for a particular purpose;

ii) effectively excludes on behalf of all Contributors all liability for damages, including direct, indirect, special, incidental and consequential damages, such as lost profits;

iii) states that any provisions which differ from this Agreement are offered by that Contributor alone and not by any other party; and

iv) states that source code for the Program is available from such Contributor, and informs licensees how to obtain it in a reasonable manner on or through a medium customarily used for software exchange.

When the Program is made available in source code form:

a) it must be made available under this Agreement; and

b) a copy of this Agreement must be included with each copy of the Program.

Contributors may not remove or alter any copyright notices contained within the Program.

Each Contributor must identify itself as the originator of its Contribution, if any, in a manner that reasonably allows subsequent Recipients to identify the originator of the Contribution.

4. COMMERCIAL DISTRIBUTION

Commercial distributors of software may accept certain responsibilities with respect to end users, business partners and the like. While this license is intended to facilitate the commercial use of the Program, the Contributor who includes the Program in a commercial product offering should do so in a manner which does not create potential liability for other Contributors. Therefore, if a Contributor includes the Program in a commercial product offering, such Contributor ("Commercial Contributor") hereby agrees to defend and indemnify every other Contributor ("Indemnified Contributor") against any losses, damages and costs (collectively "Losses") arising from claims, lawsuits and other legal actions brought by a third party against the Indemnified Contributor to the extent caused by the acts or omissions of such Commercial Contributor in connection with its distribution of the Program in a commercial product offering. The obligations in this section do not apply to any claims or Losses relating to any actual or alleged intellectual property infringement. In order to qualify, an Indemnified Contributor must: a) promptly notify the Commercial Contributor in writing of such claim, and b) allow the Commercial Contributor to control, and cooperate with the Commercial Contributor in, the defense and any related settlement negotiations. The Indemnified Contributor may participate in any such claim at its own expense.

For example, a Contributor might include the Program in a commercial product offering, Product X. That Contributor is then a Commercial Contributor. If that Commercial Contributor then makes performance claims, or offers warranties related to Product X, those performance claims and warranties are such Commercial Contributor's responsibility alone. Under this section, the Commercial Contributor would have to defend claims against the other Contributors related to those performance claims and warranties, and if a court requires any other Contributor to pay any damages as a result, the Commercial Contributor must pay those damages.

5. NO WARRANTY

EXCEPT AS EXPRESSLY SET FORTH IN THIS AGREEMENT, THE PROGRAM IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED INCLUDING, WITHOUT LIMITATION, ANY WARRANTIES OR CONDITIONS OF TITLE, NON-INFRINGEMENT, MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Each Recipient is solely responsible for determining the appropriateness of using and distributing the Program and assumes all risks associated with its exercise of rights under this Agreement , including but not limited to the risks and costs of program errors, compliance with applicable laws, damage to or loss of data, programs or equipment, and unavailability or interruption of operations.

6. DISCLAIMER OF LIABILITY

EXCEPT AS EXPRESSLY SET FORTH IN THIS AGREEMENT, NEITHER RECIPIENT NOR ANY CONTRIBUTORS SHALL HAVE ANY LIABILITY FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING WITHOUT LIMITATION LOST PROFITS), HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OR DISTRIBUTION OF THE PROGRAM OR THE EXERCISE OF ANY RIGHTS GRANTED HEREUNDER, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.

7. GENERAL

If any provision of this Agreement is invalid or unenforceable under applicable law, it shall not affect the validity or enforceability of the remainder of the terms of this Agreement, and without further action by the parties hereto, such provision shall be reformed to the minimum extent necessary to make such provision valid and enforceable.

If Recipient institutes patent litigation against any entity (including a cross-claim or counterclaim in a lawsuit) alleging that the Program itself (excluding combinations of the Program with other software or hardware) infringes such Recipient's patent(s), then such Recipient's rights granted under Section 2(b) shall terminate as of the date such litigation is filed.

All Recipient's rights under this Agreement shall terminate if it fails to comply with any of the material terms or conditions of this Agreement and does not cure such failure in a reasonable period of time after becoming aware of such noncompliance. If all Recipient's rights under this Agreement terminate, Recipient agrees to cease use and distribution of the Program as soon as reasonably practicable. However, Recipient's obligations under this Agreement and any licenses granted by Recipient relating to the Program shall continue and survive.

Everyone is permitted to copy and distribute copies of this Agreement, but in order to avoid inconsistency the Agreement is copyrighted and may only be modified in the following manner. The Agreement Steward reserves the right to publish new versions (including revisions) of this Agreement from time to time. No one other than the Agreement Steward has the right to modify this Agreement. The Eclipse Foundation is the initial Agreement Steward. The Eclipse Foundation may assign the responsibility to serve as the Agreement Steward to a suitable separate entity. Each new version of the Agreement will be given a distinguishing version number. The Program (including Contributions) may always be distributed subject to the version of the Agreement under which it was received. In addition, after a new version of the Agreement is published, Contributor may elect to distribute the Program (including its Contributions) under the new version. Except as expressly stated in Sections 2(a) and 2(b) above, Recipient receives no rights or licenses to the intellectual property of any Contributor under this Agreement, whether expressly, by implication, estoppel or otherwise. All rights in the Program not expressly granted under this Agreement are reserved.

This Agreement is governed by the laws of the State of New York and the intellectual property laws of the United States of America. No party to this Agreement will bring a legal action under this Agreement more than one year after the cause of action arose. Each party waives its rights to a jury trial in any resulting litigation.

----

Eclipse Public License - v 2.0

    THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
    PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
    OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.

1. DEFINITIONS

"Contribution" means:

  a) in the case of the initial Contributor, the initial content
     Distributed under this Agreement, and

  b) in the case of each subsequent Contributor:
     i) changes to the Program, and
     ii) additions to the Program;
  where such changes and/or additions to the Program originate from
  and are Distributed by that particular Contributor. A Contribution
  "originates" from a Contributor if it was added to the Program by
  such Contributor itself or anyone acting on such Contributor's behalf.
  Contributions do not include changes or additions to the Program that
  are not Modified Works.

"Contributor" means any person or entity that Distributes the Program.

"Licensed Patents" mean patent claims licensable by a Contributor which
are necessarily infringed by the use or sale of its Contribution alone
or when combined with the Program.

"Program" means the Contributions Distributed in accordance with this
Agreement.

"Recipient" means anyone who receives the Program under this Agreement
or any Secondary License (as applicable), including Contributors.

"Derivative Works" shall mean any work, whether in Source Code or other
form, that is based on (or derived from) the Program and for which the
editorial revisions, annotations, elaborations, or other modifications
represent, as a whole, an original work of authorship.

"Modified Works" shall mean any work in Source Code or other form that
results from an addition to, deletion from, or modification of the
contents of the Program, including, for purposes of clarity any new file
in Source Code form that contains any contents of the Program. Modified
Works shall not include works that contain only declarations,
interfaces, types, classes, structures, or files of the Program solely
in each case in order to link to, bind by name, or subclass the Program
or Modified Works thereof.

"Distribute" means the acts of a) distributing or b) making available
in any manner that enables the transfer of a copy.

"Source Code" means the form of a Program preferred for making
modifications, including but not limited to software source code,
documentation source, and configuration files.

"Secondary License" means either the GNU General Public License,
Version 2.0, or any later versions of that license, including any
exceptions or additional permissions as identified by the initial
Contributor.

2. GRANT OF RIGHTS

  a) Subject to the terms of this Agreement, each Contributor hereby
  grants Recipient a non-exclusive, worldwide, royalty-free copyright
  license to reproduce, prepare Derivative Works of, publicly display,
  publicly perform, Distribute and sublicense the Contribution of such
  Contributor, if any, and such Derivative Works.

  b) Subject to the terms of this Agreement, each Contributor hereby
  grants Recipient a non-exclusive, worldwide, royalty-free patent
  license under Licensed Patents to make, use, sell, offer to sell,
  import and otherwise transfer the Contribution of such Contributor,
  if any, in Source Code or other form. This patent license shall
  apply to the combination of the Contribution and the Program if, at
  the time the Contribution is added by the Contributor, such addition
  of the Contribution causes such combination to be covered by the
  Licensed Patents. The patent license shall not apply to any other
  combinations which include the Contribution. No hardware per se is
  licensed hereunder.

  c) Recipient understands that although each Contributor grants the
  licenses to its Contributions set forth herein, no assurances are
  provided by any Contributor that the Program does not infringe the
  patent or other intellectual property rights of any other entity.
  Each Contributor disclaims any liability to Recipient for claims
  brought by any other entity based on infringement of intellectual
  property rights or otherwise. As a condition to exercising the
  rights and licenses granted hereunder, each Recipient hereby
  assumes sole responsibility to secure any other intellectual
  property rights needed, if any. For example, if a third party
  patent license is required to allow Recipient to Distribute the
  Program, it is Recipient's responsibility to acquire that license
  before distributing the Program.

  d) Each Contributor represents that to its knowledge it has
  sufficient copyright rights in its Contribution, if any, to grant
  the copyright license set forth in this Agreement.

  e) Notwithstanding the terms of any Secondary License, no
  Contributor makes additional grants to any Recipient (other than
  those set forth in this Agreement) as a result of such Recipient's
  receipt of the Program under the terms of a Secondary License
  (if permitted under the terms of Section 3).

3. REQUIREMENTS

3.1 If a Contributor Distributes the Program in any form, then:

  a) the Program must also be made available as Source Code, in
  accordance with section 3.2, and the Contributor must accompany
  the Program with a statement that the Source Code for the Program
  is available under this Agreement, and informs Recipients how to
  obtain it in a reasonable manner on or through a medium customarily
  used for software exchange; and

  b) the Contributor may Distribute the Program under a license
  different than this Agreement, provided that such license:
     i) effectively disclaims on behalf of all other Contributors all
     warranties and conditions, express and implied, including
     warranties or conditions of title and non-infringement, and
     implied warranties or conditions of merchantability and fitness
     for a particular purpose;

     ii) effectively excludes on behalf of all other Contributors all
     liability for damages, including direct, indirect, special,
     incidental and consequential damages, such as lost profits;

     iii) does not attempt to limit or alter the recipients' rights
     in the Source Code under section 3.2; and

     iv) requires any subsequent distribution of the Program by any
     party to be under a license that satisfies the requirements
     of this section 3.

3.2 When the Program is Distributed as Source Code:

  a) it must be made available under this Agreement, or if the
  Program (i) is combined with other material in a separate file or
  files made available under a Secondary License, and (ii) the initial
  Contributor attached to the Source Code the notice described in
  Exhibit A of this Agreement, then the Program may be made available
  under the terms of such Secondary Licenses, and

  b) a copy of this Agreement must be included with each copy of
  the Program.

3.3 Contributors may not remove or alter any copyright, patent,
trademark, attribution notices, disclaimers of warranty, or limitations
of liability ("notices") contained within the Program from any copy of
the Program which they Distribute, provided that Contributors may add
their own appropriate notices.

4. COMMERCIAL DISTRIBUTION

Commercial distributors of software may accept certain responsibilities
with respect to end users, business partners and the like. While this
license is intended to facilitate the commercial use of the Program,
the Contributor who includes the Program in a commercial product
offering should do so in a manner which does not create potential
liability for other Contributors. Therefore, if a Contributor includes
the Program in a commercial product offering, such Contributor
("Commercial Contributor") hereby agrees to defend and indemnify every
other Contributor ("Indemnified Contributor") against any losses,
damages and costs (collectively "Losses") arising from claims, lawsuits
and other legal actions brought by a third party against the Indemnified
Contributor to the extent caused by the acts or omissions of such
Commercial Contributor in connection with its distribution of the Program
in a commercial product offering. The obligations in this section do not
apply to any claims or Losses relating to any actual or alleged
intellectual property infringement. In order to qualify, an Indemnified
Contributor must: a) promptly notify the Commercial Contributor in
writing of such claim, and b) allow the Commercial Contributor to control,
and cooperate with the Commercial Contributor in, the defense and any
related settlement negotiations. The Indemnified Contributor may
participate in any such claim at its own expense.

For example, a Contributor might include the Program in a commercial
product offering, Product X. That Contributor is then a Commercial
Contributor. If that Commercial Contributor then makes performance
claims, or offers warranties related to Product X, those performance
claims and warranties are such Commercial Contributor's responsibility
alone. Under this section, the Commercial Contributor would have to
defend claims against the other Contributors related to those performance
claims and warranties, and if a court requires any other Contributor to
pay any damages as a result, the Commercial Contributor must pay
those damages.

5. NO WARRANTY

EXCEPT AS EXPRESSLY SET FORTH IN THIS AGREEMENT, AND TO THE EXTENT
PERMITTED BY APPLICABLE LAW, THE PROGRAM IS PROVIDED ON AN "AS IS"
BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, EITHER EXPRESS OR
IMPLIED INCLUDING, WITHOUT LIMITATION, ANY WARRANTIES OR CONDITIONS OF
TITLE, NON-INFRINGEMENT, MERCHANTABILITY OR FITNESS FOR A PARTICULAR
PURPOSE. Each Recipient is solely responsible for determining the
appropriateness of using and distributing the Program and assumes all
risks associated with its exercise of rights under this Agreement,
including but not limited to the risks and costs of program errors,
compliance with applicable laws, damage to or loss of data, programs
or equipment, and unavailability or interruption of operations.

6. DISCLAIMER OF LIABILITY

EXCEPT AS EXPRESSLY SET FORTH IN THIS AGREEMENT, AND TO THE EXTENT
PERMITTED BY APPLICABLE LAW, NEITHER RECIPIENT NOR ANY CONTRIBUTORS
SHALL HAVE ANY LIABILITY FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING WITHOUT LIMITATION LOST
PROFITS), HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OR DISTRIBUTION OF THE PROGRAM OR THE
EXERCISE OF ANY RIGHTS GRANTED HEREUNDER, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGES.

7. GENERAL

If any provision of this Agreement is invalid or unenforceable under
applicable law, it shall not affect the validity or enforceability of
the remainder of the terms of this Agreement, and without further
action by the parties hereto, such provision shall be reformed to the
minimum extent necessary to make such provision valid and enforceable.

If Recipient institutes patent litigation against any entity
(including a cross-claim or counterclaim in a lawsuit) alleging that the
Program itself (excluding combinations of the Program with other software
or hardware) infringes such Recipient's patent(s), then such Recipient's
rights granted under Section 2(b) shall terminate as of the date such
litigation is filed.

All Recipient's rights under this Agreement shall terminate if it
fails to comply with any of the material terms or conditions of this
Agreement and does not cure such failure in a reasonable period of
time after becoming aware of such noncompliance. If all Recipient's
rights under this Agreement terminate, Recipient agrees to cease use
and distribution of the Program as soon as reasonably practicable.
However, Recipient's obligations under this Agreement and any licenses
granted by Recipient relating to the Program shall continue and survive.

Everyone is permitted to copy and distribute copies of this Agreement,
but in order to avoid inconsistency the Agreement is copyrighted and
may only be modified in the following manner. The Agreement Steward
reserves the right to publish new versions (including revisions) of
this Agreement from time to time. No one other than the Agreement
Steward has the right to modify this Agreement. The Eclipse Foundation
is the initial Agreement Steward. The Eclipse Foundation may assign the
responsibility to serve as the Agreement Steward to a suitable separate
entity. Each new version of the Agreement will be given a distinguishing
version number. The Program (including Contributions) may always be
Distributed subject to the version of the Agreement under which it was
received. In addition, after a new version of the Agreement is published,
Contributor may elect to Distribute the Program (including its
Contributions) under the new version.

Except as expressly stated in Sections 2(a) and 2(b) above, Recipient
receives no rights or licenses to the intellectual property of any
Contributor under this Agreement, whether expressly, by implication,
estoppel or otherwise. All rights in the Program not expressly granted
under this Agreement are reserved. Nothing in this Agreement is intended
to be enforceable by any entity that is not a Contributor or Recipient.
No third-party beneficiary rights are created under this Agreement.

Exhibit A - Form of Secondary Licenses Notice

"This Source Code is also Distributed under one
or more Secondary Licenses, as those terms are defined by
the Eclipse Public License, v. 2.0: {name license(s),version(s),
and exceptions or additional permissions here}."

  Simply including a copy of this Agreement, including this Exhibit A
  is not sufficient to license the Source Code under Secondary Licenses.

  If it is not possible or desirable to put the notice in a particular
  file, then You may include the notice in a location (such as a LICENSE
  file in a relevant directory) where a recipient would be likely to
  look for such a notice.

  You may add additional accurate notices of copyright ownership.

----

                    GNU GENERAL PUBLIC LICENSE
                       Version 2, June 1991

 Copyright (C) 1989, 1991 Free Software Foundation, Inc.,
 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 Everyone is permitted to copy and distribute verbatim copies
 of this license document, but changing it is not allowed.

                            Preamble

  The licenses for most software are designed to take away your
freedom to share and change it.  By contrast, the GNU General Public
License is intended to guarantee your freedom to share and change free
software--to make sure the software is free for all its users.  This
General Public License applies to most of the Free Software
Foundation's software and to any other program whose authors commit to
using it.  (Some other Free Software Foundation software is covered by
the GNU Lesser General Public License instead.)  You can apply it to
your programs, too.

  When we speak of free software, we are referring to freedom, not
price.  Our General Public Licenses are designed to make sure that you
have the freedom to distribute copies of free software (and charge for
this service if you wish), that you receive source code or can get it
if you want it, that you can change the software or use pieces of it
in new free programs; and that you know you can do these things.

  To protect your rights, we need to make restrictions that forbid
anyone to deny you these rights or to ask you to surrender the rights.
These restrictions translate to certain responsibilities for you if you
distribute copies of the software, or if you modify it.

  For example, if you distribute copies of such a program, whether
gratis or for a fee, you must give the recipients all the rights that
you have.  You must make sure that they, too, receive or can get the
source code.  And you must show them these terms so they know their
rights.

  We protect your rights with two steps: (1) copyright the software, and
(2) offer you this license which gives you legal permission to copy,
distribute and/or modify the software.

  Also, for each author's protection and ours, we want to make certain
that everyone understands that there is no warranty for this free
software.  If the software is modified by someone else and passed on, we
want its recipients to know that what they have is not the original, so
that any problems introduced by others will not reflect on the original
authors' reputations.

  Finally, any free program is threatened constantly by software
patents.  We wish to avoid the danger that redistributors of a free
program will individually obtain patent licenses, in effect making the
program proprietary.  To prevent this, we have made it clear that any
patent must be licensed for everyone's free use or not licensed at all.

  The precise terms and conditions for copying, distribution and
modification follow.

                    GNU GENERAL PUBLIC LICENSE
   TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION

  0. This License applies to any program or other work which contains
a notice placed by the copyright holder saying it may be distributed
under the terms of this General Public License.  The "Program", below,
refers to any such program or work, and a "work based on the Program"
means either the Program or any derivative work under copyright law:
that is to say, a work containing the Program or a portion of it,
either verbatim or with modifications and/or translated into another
language.  (Hereinafter, translation is included without limitation in
the term "modification".)  Each licensee is addressed as "you".

Activities other than copying, distribution and modification are not
covered by this License; they are outside its scope.  The act of
running the Program is not restricted, and the output from the Program
is covered only if its contents constitute a work based on the
Program (independent of having been made by running the Program).
Whether that is true depends on what the Program does.

  1. You may copy and distribute verbatim copies of the Program's
source code as you receive it, in any medium, provided that you
conspicuously and appropriately publish on each copy an appropriate
copyright notice and disclaimer of warranty; keep intact all the
notices that refer to this License and to the absence of any warranty;
and give any other recipients of the Program a copy of this License
along with the Program.

You may charge a fee for the physical act of transferring a copy, and
you may at your option offer warranty protection in exchange for a fee.

  2. You may modify your copy or copies of the Program or any portion
of it, thus forming a work based on the Program, and copy and
distribute such modifications or work under the terms of Section 1
above, provided that you also meet all of these conditions:

    a) You must cause the modified files to carry prominent notices
    stating that you changed the files and the date of any change.

    b) You must cause any work that you distribute or publish, that in
    whole or in part contains or is derived from the Program or any
    part thereof, to be licensed as a whole at no charge to all third
    parties under the terms of this License.

    c) If the modified program normally reads commands interactively
    when run, you must cause it, when started running for such
    interactive use in the most ordinary way, to print or display an
    announcement including an appropriate copyright notice and a
    notice that there is no warranty (or else, saying that you provide
    a warranty) and that users may redistribute the program under
    these conditions, and telling the user how to view a copy of this
    License.  (Exception: if the Program itself is interactive but
    does not normally print such an announcement, your work based on
    the Program is not required to print an announcement.)

These requirements apply to the modified work as a whole.  If
identifiable sections of that work are not derived from the Program,
and can be reasonably considered independent and separate works in
themselves, then this License, and its terms, do not apply to those
sections when you distribute them as separate works.  But when you
distribute the same sections as part of a whole which is a work based
on the Program, the distribution of the whole must be on the terms of
this License, whose permissions for other licensees extend to the
entire whole, and thus to each and every part regardless of who wrote it.

Thus, it is not the intent of this section to claim rights or contest
your rights to work written entirely by you; rather, the intent is to
exercise the right to control the distribution of derivative or
collective works based on the Program.

In addition, mere aggregation of another work not based on the Program
with the Program (or with a work based on the Program) on a volume of
a storage or distribution medium does not bring the other work under
the scope of this License.

  3. You may copy and distribute the Program (or a work based on it,
under Section 2) in object code or executable form under the terms of
Sections 1 and 2 above provided that you also do one of the following:

    a) Accompany it with the complete corresponding machine-readable
    source code, which must be distributed under the terms of Sections
    1 and 2 above on a medium customarily used for software interchange; or,

    b) Accompany it with a written offer, valid for at least three
    years, to give any third party, for a charge no more than your
    cost of physically performing source distribution, a complete
    machine-readable copy of the corresponding source code, to be
    distributed under the terms of Sections 1 and 2 above on a medium
    customarily used for software interchange; or,

    c) Accompany it with the information you received as to the offer
    to distribute corresponding source code.  (This alternative is
    allowed only for noncommercial distribution and only if you
    received the program in object code or executable form with such
    an offer, in accord with Subsection b above.)

The source code for a work means the preferred form of the work for
making modifications to it.  For an executable work, complete source
code means all the source code for all modules it contains, plus any
associated interface definition files, plus the scripts used to
control compilation and installation of the executable.  However, as a
special exception, the source code distributed need not include
anything that is normally distributed (in either source or binary
form) with the major components (compiler, kernel, and so on) of the
operating system on which the executable runs, unless that component
itself accompanies the executable.

If distribution of executable or object code is made by offering
access to copy from a designated place, then offering equivalent
access to copy the source code from the same place counts as
distribution of the source code, even though third parties are not
compelled to copy the source along with the object code.

  4. You may not copy, modify, sublicense, or distribute the Program
except as expressly provided under this License.  Any attempt
otherwise to copy, modify, sublicense or distribute the Program is
void, and will automatically terminate your rights under this License.
However, parties who have received copies, or rights, from you under
this License will not have their licenses terminated so long as such
parties remain in full compliance.

  5. You are not required to accept this License, since you have not
signed it.  However, nothing else grants you permission to modify or
distribute the Program or its derivative works.  These actions are
prohibited by law if you do not accept this License.  Therefore, by
modifying or distributing the Program (or any work based on the
Program), you indicate your acceptance of this License to do so, and
all its terms and conditions for copying, distributing or modifying
the Program or works based on it.

  6. Each time you redistribute the Program (or any work based on the
Program), the recipient automatically receives a license from the
original licensor to copy, distribute or modify the Program subject to
these terms and conditions.  You may not impose any further
restrictions on the recipients' exercise of the rights granted herein.
You are not responsible for enforcing compliance by third parties to
this License.

  7. If, as a consequence of a court judgment or allegation of patent
infringement or for any other reason (not limited to patent issues),
conditions are imposed on you (whether by court order, agreement or
otherwise) that contradict the conditions of this License, they do not
excuse you from the conditions of this License.  If you cannot
distribute so as to satisfy simultaneously your obligations under this
License and any other pertinent obligations, then as a consequence you
may not distribute the Program at all.  For example, if a patent
license would not permit royalty-free redistribution of the Program by
all those who receive copies directly or indirectly through you, then
the only way you could satisfy both it and this License would be to
refrain entirely from distribution of the Program.

If any portion of this section is held invalid or unenforceable under
any particular circumstance, the balance of the section is intended to
apply and the section as a whole is intended to apply in other
circumstances.

It is not the purpose of this section to induce you to infringe any
patents or other property right claims or to contest validity of any
such claims; this section has the sole purpose of protecting the
integrity of the free software distribution system, which is
implemented by public license practices.  Many people have made
generous contributions to the wide range of software distributed
through that system in reliance on consistent application of that
system; it is up to the author/donor to decide if he or she is willing
to distribute software through any other system and a licensee cannot
impose that choice.

This section is intended to make thoroughly clear what is believed to
be a consequence of the rest of this License.

  8. If the distribution and/or use of the Program is restricted in
certain countries either by patents or by copyrighted interfaces, the
original copyright holder who places the Program under this License
may add an explicit geographical distribution limitation excluding
those countries, so that distribution is permitted only in or among
countries not thus excluded.  In such case, this License incorporates
the limitation as if written in the body of this License.

  9. The Free Software Foundation may publish revised and/or new versions
of the General Public License from time to time.  Such new versions will
be similar in spirit to the present version, but may differ in detail to
address new problems or concerns.

Each version is given a distinguishing version number.  If the Program
specifies a version number of this License which applies to it and "any
later version", you have the option of following the terms and conditions
either of that version or of any later version published by the Free
Software Foundation.  If the Program does not specify a version number of
this License, you may choose any version ever published by the Free Software
Foundation.

  10. If you wish to incorporate parts of the Program into other free
programs whose distribution conditions are different, write to the author
to ask for permission.  For software which is copyrighted by the Free
Software Foundation, write to the Free Software Foundation; we sometimes
make exceptions for this.  Our decision will be guided by the two goals
of preserving the free status of all derivatives of our free software and
of promoting the sharing and reuse of software generally.

                            NO WARRANTY

  11. BECAUSE THE PROGRAM IS LICENSED FREE OF CHARGE, THERE IS NO WARRANTY
FOR THE PROGRAM, TO THE EXTENT PERMITTED BY APPLICABLE LAW.  EXCEPT WHEN
OTHERWISE STATED IN WRITING THE COPYRIGHT HOLDERS AND/OR OTHER PARTIES
PROVIDE THE PROGRAM "AS IS" WITHOUT WARRANTY OF ANY KIND, EITHER EXPRESSED
OR IMPLIED, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE.  THE ENTIRE RISK AS
TO THE QUALITY AND PERFORMANCE OF THE PROGRAM IS WITH YOU.  SHOULD THE
PROGRAM PROVE DEFECTIVE, YOU ASSUME THE COST OF ALL NECESSARY SERVICING,
REPAIR OR CORRECTION.

  12. IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN WRITING
WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MAY MODIFY AND/OR
REDISTRIBUTE THE PROGRAM AS PERMITTED ABOVE, BE LIABLE TO YOU FOR DAMAGES,
INCLUDING ANY GENERAL, SPECIAL, INCIDENTAL OR CONSEQUENTIAL DAMAGES ARISING
OUT OF THE USE OR INABILITY TO USE THE PROGRAM (INCLUDING BUT NOT LIMITED
TO LOSS OF DATA OR DATA BEING RENDERED INACCURATE OR LOSSES SUSTAINED BY
YOU OR THIRD PARTIES OR A FAILURE OF THE PROGRAM TO OPERATE WITH ANY OTHER
PROGRAMS), EVEN IF SUCH HOLDER OR OTHER PARTY HAS BEEN ADVISED OF THE
POSSIBILITY OF SUCH DAMAGES.

                     END OF TERMS AND CONDITIONS

            How to Apply These Terms to Your New Programs

  If you develop a new program, and you want it to be of the greatest
possible use to the public, the best way to achieve this is to make it
free software which everyone can redistribute and change under these terms.

  To do so, attach the following notices to the program.  It is safest
to attach them to the start of each source file to most effectively
convey the exclusion of warranty; and each file should have at least
the "copyright" line and a pointer to where the full notice is found.

    <one line to give the program's name and a brief idea of what it does.>
    Copyright (C) <year>  <name of author>

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along
    with this program; if not, write to the Free Software Foundation, Inc.,
    51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.

Also add information on how to contact you by electronic and paper mail.

If the program is interactive, make it output a short notice like this
when it starts in an interactive mode:

    Gnomovision version 69, Copyright (C) year name of author
    Gnomovision comes with ABSOLUTELY NO WARRANTY; for details type `show w'.
    This is free software, and you are welcome to redistribute it
    under certain conditions; type `show c' for details.

The hypothetical commands `show w' and `show c' should show the appropriate
parts of the General Public License.  Of course, the commands you use may
be called something other than `show w' and `show c'; they could even be
mouse-clicks or menu items--whatever suits your program.

You should also get your employer (if you work as a programmer) or your
school, if any, to sign a "copyright disclaimer" for the program, if
necessary.  Here is a sample; alter the names:

  Yoyodyne, Inc., hereby disclaims all copyright interest in the program
  `Gnomovision' (which makes passes at compilers) written by James Hacker.

  <signature of Ty Coon>, 1 April 1989
  Ty Coon, President of Vice

This General Public License does not permit incorporating your program into
proprietary programs.  If your program is a subroutine library, you may
consider it more useful to permit linking proprietary applications with the
library.  If this is what you want to do, use the GNU Lesser General
Public License instead of this License.

----

                    GNU GENERAL PUBLIC LICENSE
                       Version 2, June 1991

 Copyright (C) 1989, 1991 Free Software Foundation, Inc.,
 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 Everyone is permitted to copy and distribute verbatim copies
 of this license document, but changing it is not allowed.

                            Preamble

  The licenses for most software are designed to take away your
freedom to share and change it.  By contrast, the GNU General Public
License is intended to guarantee your freedom to share and change free
software--to make sure the software is free for all its users.  This
General Public License applies to most of the Free Software
Foundation's software and to any other program whose authors commit to
using it.  (Some other Free Software Foundation software is covered by
the GNU Lesser General Public License instead.)  You can apply it to
your programs, too.

  When we speak of free software, we are referring to freedom, not
price.  Our General Public Licenses are designed to make sure that you
have the freedom to distribute copies of free software (and charge for
this service if you wish), that you receive source code or can get it
if you want it, that you can change the software or use pieces of it
in new free programs; and that you know you can do these things.

  To protect your rights, we need to make restrictions that forbid
anyone to deny you these rights or to ask you to surrender the rights.
These restrictions translate to certain responsibilities for you if you
distribute copies of the software, or if you modify it.

  For example, if you distribute copies of such a program, whether
gratis or for a fee, you must give the recipients all the rights that
you have.  You must make sure that they, too, receive or can get the
source code.  And you must show them these terms so they know their
rights.

  We protect your rights with two steps: (1) copyright the software, and
(2) offer you this license which gives you legal permission to copy,
distribute and/or modify the software.

  Also, for each author's protection and ours, we want to make certain
that everyone understands that there is no warranty for this free
software.  If the software is modified by someone else and passed on, we
want its recipients to know that what they have is not the original, so
that any problems introduced by others will not reflect on the original
authors' reputations.

  Finally, any free program is threatened constantly by software
patents.  We wish to avoid the danger that redistributors of a free
program will individually obtain patent licenses, in effect making the
program proprietary.  To prevent this, we have made it clear that any
patent must be licensed for everyone's free use or not licensed at all.

  The precise terms and conditions for copying, distribution and
modification follow.

                    GNU GENERAL PUBLIC LICENSE
   TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION

  0. This License applies to any program or other work which contains
a notice placed by the copyright holder saying it may be distributed
under the terms of this General Public License.  The "Program", below,
refers to any such program or work, and a "work based on the Program"
means either the Program or any derivative work under copyright law:
that is to say, a work containing the Program or a portion of it,
either verbatim or with modifications and/or translated into another
language.  (Hereinafter, translation is included without limitation in
the term "modification".)  Each licensee is addressed as "you".

Activities other than copying, distribution and modification are not
covered by this License; they are outside its scope.  The act of
running the Program is not restricted, and the output from the Program
is covered only if its contents constitute a work based on the
Program (independent of having been made by running the Program).
Whether that is true depends on what the Program does.

  1. You may copy and distribute verbatim copies of the Program's
source code as you receive it, in any medium, provided that you
conspicuously and appropriately publish on each copy an appropriate
copyright notice and disclaimer of warranty; keep intact all the
notices that refer to this License and to the absence of any warranty;
and give any other recipients of the Program a copy of this License
along with the Program.

You may charge a fee for the physical act of transferring a copy, and
you may at your option offer warranty protection in exchange for a fee.

  2. You may modify your copy or copies of the Program or any portion
of it, thus forming a work based on the Program, and copy and
distribute such modifications or work under the terms of Section 1
above, provided that you also meet all of these conditions:

    a) You must cause the modified files to carry prominent notices
    stating that you changed the files and the date of any change.

    b) You must cause any work that you distribute or publish, that in
    whole or in part contains or is derived from the Program or any
    part thereof, to be licensed as a whole at no charge to all third
    parties under the terms of this License.

    c) If the modified program normally reads commands interactively
    when run, you must cause it, when started running for such
    interactive use in the most ordinary way, to print or display an
    announcement including an appropriate copyright notice and a
    notice that there is no warranty (or else, saying that you provide
    a warranty) and that users may redistribute the program under
    these conditions, and telling the user how to view a copy of this
    License.  (Exception: if the Program itself is interactive but
    does not normally print such an announcement, your work based on
    the Program is not required to print an announcement.)

These requirements apply to the modified work as a whole.  If
identifiable sections of that work are not derived from the Program,
and can be reasonably considered independent and separate works in
themselves, then this License, and its terms, do not apply to those
sections when you distribute them as separate works.  But when you
distribute the same sections as part of a whole which is a work based
on the Program, the distribution of the whole must be on the terms of
this License, whose permissions for other licensees extend to the
entire whole, and thus to each and every part regardless of who wrote it.

Thus, it is not the intent of this section to claim rights or contest
your rights to work written entirely by you; rather, the intent is to
exercise the right to control the distribution of derivative or
collective works based on the Program.

In addition, mere aggregation of another work not based on the Program
with the Program (or with a work based on the Program) on a volume of
a storage or distribution medium does not bring the other work under
the scope of this License.

  3. You may copy and distribute the Program (or a work based on it,
under Section 2) in object code or executable form under the terms of
Sections 1 and 2 above provided that you also do one of the following:

    a) Accompany it with the complete corresponding machine-readable
    source code, which must be distributed under the terms of Sections
    1 and 2 above on a medium customarily used for software interchange; or,

    b) Accompany it with a written offer, valid for at least three
    years, to give any third party, for a charge no more than your
    cost of physically performing source distribution, a complete
    machine-readable copy of the corresponding source code, to be
    distributed under the terms of Sections 1 and 2 above on a medium
    customarily used for software interchange; or,

    c) Accompany it with the information you received as to the offer
    to distribute corresponding source code.  (This alternative is
    allowed only for noncommercial distribution and only if you
    received the program in object code or executable form with such
    an offer, in accord with Subsection b above.)

The source code for a work means the preferred form of the work for
making modifications to it.  For an executable work, complete source
code means all the source code for all modules it contains, plus any
associated interface definition files, plus the scripts used to
control compilation and installation of the executable.  However, as a
special exception, the source code distributed need not include
anything that is normally distributed (in either source or binary
form) with the major components (compiler, kernel, and so on) of the
operating system on which the executable runs, unless that component
itself accompanies the executable.

If distribution of executable or object code is made by offering
access to copy from a designated place, then offering equivalent
access to copy the source code from the same place counts as
distribution of the source code, even though third parties are not
compelled to copy the source along with the object code.

  4. You may not copy, modify, sublicense, or distribute the Program
except as expressly provided under this License.  Any attempt
otherwise to copy, modify, sublicense or distribute the Program is
void, and will automatically terminate your rights under this License.
However, parties who have received copies, or rights, from you under
this License will not have their licenses terminated so long as such
parties remain in full compliance.

  5. You are not required to accept this License, since you have not
signed it.  However, nothing else grants you permission to modify or
distribute the Program or its derivative works.  These actions are
prohibited by law if you do not accept this License.  Therefore, by
modifying or distributing the Program (or any work based on the
Program), you indicate your acceptance of this License to do so, and
all its terms and conditions for copying, distributing or modifying
the Program or works based on it.

  6. Each time you redistribute the Program (or any work based on the
Program), the recipient automatically receives a license from the
original licensor to copy, distribute or modify the Program subject to
these terms and conditions.  You may not impose any further
restrictions on the recipients' exercise of the rights granted herein.
You are not responsible for enforcing compliance by third parties to
this License.

  7. If, as a consequence of a court judgment or allegation of patent
infringement or for any other reason (not limited to patent issues),
conditions are imposed on you (whether by court order, agreement or
otherwise) that contradict the conditions of this License, they do not
excuse you from the conditions of this License.  If you cannot
distribute so as to satisfy simultaneously your obligations under this
License and any other pertinent obligations, then as a consequence you
may not distribute the Program at all.  For example, if a patent
license would not permit royalty-free redistribution of the Program by
all those who receive copies directly or indirectly through you, then
the only way you could satisfy both it and this License would be to
refrain entirely from distribution of the Program.

If any portion of this section is held invalid or unenforceable under
any particular circumstance, the balance of the section is intended to
apply and the section as a whole is intended to apply in other
circumstances.

It is not the purpose of this section to induce you to infringe any
patents or other property right claims or to contest validity of any
such claims; this section has the sole purpose of protecting the
integrity of the free software distribution system, which is
implemented by public license practices.  Many people have made
generous contributions to the wide range of software distributed
through that system in reliance on consistent application of that
system; it is up to the author/donor to decide if he or she is willing
to distribute software through any other system and a licensee cannot
impose that choice.

This section is intended to make thoroughly clear what is believed to
be a consequence of the rest of this License.

  8. If the distribution and/or use of the Program is restricted in
certain countries either by patents or by copyrighted interfaces, the
original copyright holder who places the Program under this License
may add an explicit geographical distribution limitation excluding
those countries, so that distribution is permitted only in or among
countries not thus excluded.  In such case, this License incorporates
the limitation as if written in the body of this License.

  9. The Free Software Foundation may publish revised and/or new versions
of the General Public License from time to time.  Such new versions will
be similar in spirit to the present version, but may differ in detail to
address new problems or concerns.

Each version is given a distinguishing version number.  If the Program
specifies a version number of this License which applies to it and "any
later version", you have the option of following the terms and conditions
either of that version or of any later version published by the Free
Software Foundation.  If the Program does not specify a version number of
this License, you may choose any version ever published by the Free Software
Foundation.

  10. If you wish to incorporate parts of the Program into other free
programs whose distribution conditions are different, write to the author
to ask for permission.  For software which is copyrighted by the Free
Software Foundation, write to the Free Software Foundation; we sometimes
make exceptions for this.  Our decision will be guided by the two goals
of preserving the free status of all derivatives of our free software and
of promoting the sharing and reuse of software generally.

                            NO WARRANTY

  11. BECAUSE THE PROGRAM IS LICENSED FREE OF CHARGE, THERE IS NO WARRANTY
FOR THE PROGRAM, TO THE EXTENT PERMITTED BY APPLICABLE LAW.  EXCEPT WHEN
OTHERWISE STATED IN WRITING THE COPYRIGHT HOLDERS AND/OR OTHER PARTIES
PROVIDE THE PROGRAM "AS IS" WITHOUT WARRANTY OF ANY KIND, EITHER EXPRESSED
OR IMPLIED, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE.  THE ENTIRE RISK AS
TO THE QUALITY AND PERFORMANCE OF THE PROGRAM IS WITH YOU.  SHOULD THE
PROGRAM PROVE DEFECTIVE, YOU ASSUME THE COST OF ALL NECESSARY SERVICING,
REPAIR OR CORRECTION.

  12. IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN WRITING
WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MAY MODIFY AND/OR
REDISTRIBUTE THE PROGRAM AS PERMITTED ABOVE, BE LIABLE TO YOU FOR DAMAGES,
INCLUDING ANY GENERAL, SPECIAL, INCIDENTAL OR CONSEQUENTIAL DAMAGES ARISING
OUT OF THE USE OR INABILITY TO USE THE PROGRAM (INCLUDING BUT NOT LIMITED
TO LOSS OF DATA OR DATA BEING RENDERED INACCURATE OR LOSSES SUSTAINED BY
YOU OR THIRD PARTIES OR A FAILURE OF THE PROGRAM TO OPERATE WITH ANY OTHER
PROGRAMS), EVEN IF SUCH HOLDER OR OTHER PARTY HAS BEEN ADVISED OF THE
POSSIBILITY OF SUCH DAMAGES.

                     END OF TERMS AND CONDITIONS

            How to Apply These Terms to Your New Programs

  If you develop a new program, and you want it to be of the greatest
possible use to the public, the best way to achieve this is to make it
free software which everyone can redistribute and change under these terms.

  To do so, attach the following notices to the program.  It is safest
to attach them to the start of each source file to most effectively
convey the exclusion of warranty; and each file should have at least
the "copyright" line and a pointer to where the full notice is found.

    <one line to give the program's name and a brief idea of what it does.>
    Copyright (C) <year>  <name of author>

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along
    with this program; if not, write to the Free Software Foundation, Inc.,
    51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.

Also add information on how to contact you by electronic and paper mail.

If the program is interactive, make it output a short notice like this
when it starts in an interactive mode:

    Gnomovision version 69, Copyright (C) year name of author
    Gnomovision comes with ABSOLUTELY NO WARRANTY; for details type `show w'.
    This is free software, and you are welcome to redistribute it
    under certain conditions; type `show c' for details.

The hypothetical commands `show w' and `show c' should show the appropriate
parts of the General Public License.  Of course, the commands you use may
be called something other than `show w' and `show c'; they could even be
mouse-clicks or menu items--whatever suits your program.

You should also get your employer (if you work as a programmer) or your
school, if any, to sign a "copyright disclaimer" for the program, if
necessary.  Here is a sample; alter the names:

  Yoyodyne, Inc., hereby disclaims all copyright interest in the program
  `Gnomovision' (which makes passes at compilers) written by James Hacker.

  <signature of Ty Coon>, 1 April 1989
  Ty Coon, President of Vice

This General Public License does not permit incorporating your program into
proprietary programs.  If your program is a subroutine library, you may
consider it more useful to permit linking proprietary applications with the
library.  If this is what you want to do, use the GNU Lesser General
Public License instead of this License.

Linking this library statically or dynamically with other modules is making a
combined work based on this library. Thus, the terms and conditions of the GNU
General Public License cover the whole combination.

As a special exception, the copyright holders of this library give you
permission to link this library with independent modules to produce an
executable, regardless of the license terms of these independent modules, and to
copy and distribute the resulting executable under terms of your choice,
provided that you also meet, for each linked independent module, the terms and
conditions of the license of that module. An independent module is a module
which is not derived from or based on this library. If you modify this library,
you may extend this exception to your version of the library, but you are not
obligated to do so. If you do not wish to do so, delete this exception statement
from your version.

----

This library is free software; you can redistribute it and/or modify it under
the terms of the GNU Lesser General Public License as published by the Free
Software Foundation; either version 2.1 of the License, or (at your option) any
later version.

This library is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public License along
with this library; if not, write to the Free Software Foundation, Inc., 51
Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA


                  GNU LESSER GENERAL PUBLIC LICENSE
                       Version 2.1, February 1999

 Copyright (C) 1991, 1999 Free Software Foundation, Inc.
 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 Everyone is permitted to copy and distribute verbatim copies
 of this license document, but changing it is not allowed.

[This is the first released version of the Lesser GPL.  It also counts
 as the successor of the GNU Library Public License, version 2, hence
 the version number 2.1.]

                            Preamble

  The licenses for most software are designed to take away your
freedom to share and change it.  By contrast, the GNU General Public
Licenses are intended to guarantee your freedom to share and change
free software--to make sure the software is free for all its users.

  This license, the Lesser General Public License, applies to some
specially designated software packages--typically libraries--of the
Free Software Foundation and other authors who decide to use it.  You
can use it too, but we suggest you first think carefully about whether
this license or the ordinary General Public License is the better
strategy to use in any particular case, based on the explanations below.

  When we speak of free software, we are referring to freedom of use,
not price.  Our General Public Licenses are designed to make sure that
you have the freedom to distribute copies of free software (and charge
for this service if you wish); that you receive source code or can get
it if you want it; that you can change the software and use pieces of
it in new free programs; and that you are informed that you can do
these things.

  To protect your rights, we need to make restrictions that forbid
distributors to deny you these rights or to ask you to surrender these
rights.  These restrictions translate to certain responsibilities for
you if you distribute copies of the library or if you modify it.

  For example, if you distribute copies of the library, whether gratis
or for a fee, you must give the recipients all the rights that we gave
you.  You must make sure that they, too, receive or can get the source
code.  If you link other code with the library, you must provide
complete object files to the recipients, so that they can relink them
with the library after making changes to the library and recompiling
it.  And you must show them these terms so they know their rights.

  We protect your rights with a two-step method: (1) we copyright the
library, and (2) we offer you this license, which gives you legal
permission to copy, distribute and/or modify the library.

  To protect each distributor, we want to make it very clear that
there is no warranty for the free library.  Also, if the library is
modified by someone else and passed on, the recipients should know
that what they have is not the original version, so that the original
author's reputation will not be affected by problems that might be
introduced by others.


  Finally, software patents pose a constant threat to the existence of
any free program.  We wish to make sure that a company cannot
effectively restrict the users of a free program by obtaining a
restrictive license from a patent holder.  Therefore, we insist that
any patent license obtained for a version of the library must be
consistent with the full freedom of use specified in this license.

  Most GNU software, including some libraries, is covered by the
ordinary GNU General Public License.  This license, the GNU Lesser
General Public License, applies to certain designated libraries, and
is quite different from the ordinary General Public License.  We use
this license for certain libraries in order to permit linking those
libraries into non-free programs.

  When a program is linked with a library, whether statically or using
a shared library, the combination of the two is legally speaking a
combined work, a derivative of the original library.  The ordinary
General Public License therefore permits such linking only if the
entire combination fits its criteria of freedom.  The Lesser General
Public License permits more lax criteria for linking other code with
the library.

  We call this license the "Lesser" General Public License because it
does Less to protect the user's freedom than the ordinary General
Public License.  It also provides other free software developers Less
of an advantage over competing non-free programs.  These disadvantages
are the reason we use the ordinary General Public License for many
libraries.  However, the Lesser license provides advantages in certain
special circumstances.

  For example, on rare occasions, there may be a special need to
encourage the widest possible use of a certain library, so that it becomes
a de-facto standard.  To achieve this, non-free programs must be
allowed to use the library.  A more frequent case is that a free
library does the same job as widely used non-free libraries.  In this
case, there is little to gain by limiting the free library to free
software only, so we use the Lesser General Public License.

  In other cases, permission to use a particular library in non-free
programs enables a greater number of people to use a large body of
free software.  For example, permission to use the GNU C Library in
non-free programs enables many more people to use the whole GNU
operating system, as well as its variant, the GNU/Linux operating
system.

  Although the Lesser General Public License is Less protective of the
users' freedom, it does ensure that the user of a program that is
linked with the Library has the freedom and the wherewithal to run
that program using a modified version of the Library.

  The precise terms and conditions for copying, distribution and
modification follow.  Pay close attention to the difference between a
"work based on the library" and a "work that uses the library".  The
former contains code derived from the library, whereas the latter must
be combined with the library in order to run.


                  GNU LESSER GENERAL PUBLIC LICENSE
   TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION

  0. This License Agreement applies to any software library or other
program which contains a notice placed by the copyright holder or
other authorized party saying it may be distributed under the terms of
this Lesser General Public License (also called "this License").
Each licensee is addressed as "you".

  A "library" means a collection of software functions and/or data
prepared so as to be conveniently linked with application programs
(which use some of those functions and data) to form executables.

  The "Library", below, refers to any such software library or work
which has been distributed under these terms.  A "work based on the
Library" means either the Library or any derivative work under
copyright law: that is to say, a work containing the Library or a
portion of it, either verbatim or with modifications and/or translated
straightforwardly into another language.  (Hereinafter, translation is
included without limitation in the term "modification".)

  "Source code" for a work means the preferred form of the work for
making modifications to it.  For a library, complete source code means
all the source code for all modules it contains, plus any associated
interface definition files, plus the scripts used to control compilation
and installation of the library.

  Activities other than copying, distribution and modification are not
covered by this License; they are outside its scope.  The act of
running a program using the Library is not restricted, and output from
such a program is covered only if its contents constitute a work based
on the Library (independent of the use of the Library in a tool for
writing it).  Whether that is true depends on what the Library does
and what the program that uses the Library does.

  1. You may copy and distribute verbatim copies of the Library's
complete source code as you receive it, in any medium, provided that
you conspicuously and appropriately publish on each copy an
appropriate copyright notice and disclaimer of warranty; keep intact
all the notices that refer to this License and to the absence of any
warranty; and distribute a copy of this License along with the
Library.

  You may charge a fee for the physical act of transferring a copy,
and you may at your option offer warranty protection in exchange for a
fee.


  2. You may modify your copy or copies of the Library or any portion
of it, thus forming a work based on the Library, and copy and
distribute such modifications or work under the terms of Section 1
above, provided that you also meet all of these conditions:

    a) The modified work must itself be a software library.

    b) You must cause the files modified to carry prominent notices
    stating that you changed the files and the date of any change.

    c) You must cause the whole of the work to be licensed at no
    charge to all third parties under the terms of this License.

    d) If a facility in the modified Library refers to a function or a
    table of data to be supplied by an application program that uses
    the facility, other than as an argument passed when the facility
    is invoked, then you must make a good faith effort to ensure that,
    in the event an application does not supply such function or
    table, the facility still operates, and performs whatever part of
    its purpose remains meaningful.

    (For example, a function in a library to compute square roots has
    a purpose that is entirely well-defined independent of the
    application.  Therefore, Subsection 2d requires that any
    application-supplied function or table used by this function must
    be optional: if the application does not supply it, the square
    root function must still compute square roots.)

These requirements apply to the modified work as a whole.  If
identifiable sections of that work are not derived from the Library,
and can be reasonably considered independent and separate works in
themselves, then this License, and its terms, do not apply to those
sections when you distribute them as separate works.  But when you
distribute the same sections as part of a whole which is a work based
on the Library, the distribution of the whole must be on the terms of
this License, whose permissions for other licensees extend to the
entire whole, and thus to each and every part regardless of who wrote
it.

Thus, it is not the intent of this section to claim rights or contest
your rights to work written entirely by you; rather, the intent is to
exercise the right to control the distribution of derivative or
collective works based on the Library.

In addition, mere aggregation of another work not based on the Library
with the Library (or with a work based on the Library) on a volume of
a storage or distribution medium does not bring the other work under
the scope of this License.

  3. You may opt to apply the terms of the ordinary GNU General Public
License instead of this License to a given copy of the Library.  To do
this, you must alter all the notices that refer to this License, so
that they refer to the ordinary GNU General Public License, version 2,
instead of to this License.  (If a newer version than version 2 of the
ordinary GNU General Public License has appeared, then you can specify
that version instead if you wish.)  Do not make any other change in
these notices.


  Once this change is made in a given copy, it is irreversible for
that copy, so the ordinary GNU General Public License applies to all
subsequent copies and derivative works made from that copy.

  This option is useful when you wish to copy part of the code of
the Library into a program that is not a library.

  4. You may copy and distribute the Library (or a portion or
derivative of it, under Section 2) in object code or executable form
under the terms of Sections 1 and 2 above provided that you accompany
it with the complete corresponding machine-readable source code, which
must be distributed under the terms of Sections 1 and 2 above on a
medium customarily used for software interchange.

  If distribution of object code is made by offering access to copy
from a designated place, then offering equivalent access to copy the
source code from the same place satisfies the requirement to
distribute the source code, even though third parties are not
compelled to copy the source along with the object code.

  5. A program that contains no derivative of any portion of the
Library, but is designed to work with the Library by being compiled or
linked with it, is called a "work that uses the Library".  Such a
work, in isolation, is not a derivative work of the Library, and
therefore falls outside the scope of this License.

  However, linking a "work that uses the Library" with the Library
creates an executable that is a derivative of the Library (because it
contains portions of the Library), rather than a "work that uses the
library".  The executable is therefore covered by this License.
Section 6 states terms for distribution of such executables.

  When a "work that uses the Library" uses material from a header file
that is part of the Library, the object code for the work may be a
derivative work of the Library even though the source code is not.
Whether this is true is especially significant if the work can be
linked without the Library, or if the work is itself a library.  The
threshold for this to be true is not precisely defined by law.

  If such an object file uses only numerical parameters, data
structure layouts and accessors, and small macros and small inline
functions (ten lines or less in length), then the use of the object
file is unrestricted, regardless of whether it is legally a derivative
work.  (Executables containing this object code plus portions of the
Library will still fall under Section 6.)

  Otherwise, if the work is a derivative of the Library, you may
distribute the object code for the work under the terms of Section 6.
Any executables containing that work also fall under Section 6,
whether or not they are linked directly with the Library itself.


  6. As an exception to the Sections above, you may also combine or
link a "work that uses the Library" with the Library to produce a
work containing portions of the Library, and distribute that work
under terms of your choice, provided that the terms permit
modification of the work for the customer's own use and reverse
engineering for debugging such modifications.

  You must give prominent notice with each copy of the work that the
Library is used in it and that the Library and its use are covered by
this License.  You must supply a copy of this License.  If the work
during execution displays copyright notices, you must include the
copyright notice for the Library among them, as well as a reference
directing the user to the copy of this License.  Also, you must do one
of these things:

    a) Accompany the work with the complete corresponding
    machine-readable source code for the Library including whatever
    changes were used in the work (which must be distributed under
    Sections 1 and 2 above); and, if the work is an executable linked
    with the Library, with the complete machine-readable "work that
    uses the Library", as object code and/or source code, so that the
    user can modify the Library and then relink to produce a modified
    executable containing the modified Library.  (It is understood
    that the user who changes the contents of definitions files in the
    Library will not necessarily be able to recompile the application
    to use the modified definitions.)

    b) Use a suitable shared library mechanism for linking with the
    Library.  A suitable mechanism is one that (1) uses at run time a
    copy of the library already present on the user's computer system,
    rather than copying library functions into the executable, and (2)
    will operate properly with a modified version of the library, if
    the user installs one, as long as the modified version is
    interface-compatible with the version that the work was made with.

    c) Accompany the work with a written offer, valid for at
    least three years, to give the same user the materials
    specified in Subsection 6a, above, for a charge no more
    than the cost of performing this distribution.

    d) If distribution of the work is made by offering access to copy
    from a designated place, offer equivalent access to copy the above
    specified materials from the same place.

    e) Verify that the user has already received a copy of these
    materials or that you have already sent this user a copy.

  For an executable, the required form of the "work that uses the
Library" must include any data and utility programs needed for
reproducing the executable from it.  However, as a special exception,
the materials to be distributed need not include anything that is
normally distributed (in either source or binary form) with the major
components (compiler, kernel, and so on) of the operating system on
which the executable runs, unless that component itself accompanies
the executable.

  It may happen that this requirement contradicts the license
restrictions of other proprietary libraries that do not normally
accompany the operating system.  Such a contradiction means you cannot
use both them and the Library together in an executable that you
distribute.


  7. You may place library facilities that are a work based on the
Library side-by-side in a single library together with other library
facilities not covered by this License, and distribute such a combined
library, provided that the separate distribution of the work based on
the Library and of the other library facilities is otherwise
permitted, and provided that you do these two things:

    a) Accompany the combined library with a copy of the same work
    based on the Library, uncombined with any other library
    facilities.  This must be distributed under the terms of the
    Sections above.

    b) Give prominent notice with the combined library of the fact
    that part of it is a work based on the Library, and explaining
    where to find the accompanying uncombined form of the same work.

  8. You may not copy, modify, sublicense, link with, or distribute
the Library except as expressly provided under this License.  Any
attempt otherwise to copy, modify, sublicense, link with, or
distribute the Library is void, and will automatically terminate your
rights under this License.  However, parties who have received copies,
or rights, from you under this License will not have their licenses
terminated so long as such parties remain in full compliance.

  9. You are not required to accept this License, since you have not
signed it.  However, nothing else grants you permission to modify or
distribute the Library or its derivative works.  These actions are
prohibited by law if you do not accept this License.  Therefore, by
modifying or distributing the Library (or any work based on the
Library), you indicate your acceptance of this License to do so, and
all its terms and conditions for copying, distributing or modifying
the Library or works based on it.

  10. Each time you redistribute the Library (or any work based on the
Library), the recipient automatically receives a license from the
original licensor to copy, distribute, link with or modify the Library
subject to these terms and conditions.  You may not impose any further
restrictions on the recipients' exercise of the rights granted herein.
You are not responsible for enforcing compliance by third parties with
this License.


  11. If, as a consequence of a court judgment or allegation of patent
infringement or for any other reason (not limited to patent issues),
conditions are imposed on you (whether by court order, agreement or
otherwise) that contradict the conditions of this License, they do not
excuse you from the conditions of this License.  If you cannot
distribute so as to satisfy simultaneously your obligations under this
License and any other pertinent obligations, then as a consequence you
may not distribute the Library at all.  For example, if a patent
license would not permit royalty-free redistribution of the Library by
all those who receive copies directly or indirectly through you, then
the only way you could satisfy both it and this License would be to
refrain entirely from distribution of the Library.

If any portion of this section is held invalid or unenforceable under any
particular circumstance, the balance of the section is intended to apply,
and the section as a whole is intended to apply in other circumstances.

It is not the purpose of this section to induce you to infringe any
patents or other property right claims or to contest validity of any
such claims; this section has the sole purpose of protecting the
integrity of the free software distribution system which is
implemented by public license practices.  Many people have made
generous contributions to the wide range of software distributed
through that system in reliance on consistent application of that
system; it is up to the author/donor to decide if he or she is willing
to distribute software through any other system and a licensee cannot
impose that choice.

This section is intended to make thoroughly clear what is believed to
be a consequence of the rest of this License.

  12. If the distribution and/or use of the Library is restricted in
certain countries either by patents or by copyrighted interfaces, the
original copyright holder who places the Library under this License may add
an explicit geographical distribution limitation excluding those countries,
so that distribution is permitted only in or among countries not thus
excluded.  In such case, this License incorporates the limitation as if
written in the body of this License.

  13. The Free Software Foundation may publish revised and/or new
versions of the Lesser General Public License from time to time.
Such new versions will be similar in spirit to the present version,
but may differ in detail to address new problems or concerns.

Each version is given a distinguishing version number.  If the Library
specifies a version number of this License which applies to it and
"any later version", you have the option of following the terms and
conditions either of that version or of any later version published by
the Free Software Foundation.  If the Library does not specify a
license version number, you may choose any version ever published by
the Free Software Foundation.


  14. If you wish to incorporate parts of the Library into other free
programs whose distribution conditions are incompatible with these,
write to the author to ask for permission.  For software which is
copyrighted by the Free Software Foundation, write to the Free
Software Foundation; we sometimes make exceptions for this.  Our
decision will be guided by the two goals of preserving the free status
of all derivatives of our free software and of promoting the sharing
and reuse of software generally.

                            NO WARRANTY

  15. BECAUSE THE LIBRARY IS LICENSED FREE OF CHARGE, THERE IS NO
WARRANTY FOR THE LIBRARY, TO THE EXTENT PERMITTED BY APPLICABLE LAW.
EXCEPT WHEN OTHERWISE STATED IN WRITING THE COPYRIGHT HOLDERS AND/OR
OTHER PARTIES PROVIDE THE LIBRARY "AS IS" WITHOUT WARRANTY OF ANY
KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE.  THE ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE
LIBRARY IS WITH YOU.  SHOULD THE LIBRARY PROVE DEFECTIVE, YOU ASSUME
THE COST OF ALL NECESSARY SERVICING, REPAIR OR CORRECTION.

  16. IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN
WRITING WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MAY MODIFY
AND/OR REDISTRIBUTE THE LIBRARY AS PERMITTED ABOVE, BE LIABLE TO YOU
FOR DAMAGES, INCLUDING ANY GENERAL, SPECIAL, INCIDENTAL OR
CONSEQUENTIAL DAMAGES ARISING OUT OF THE USE OR INABILITY TO USE THE
LIBRARY (INCLUDING BUT NOT LIMITED TO LOSS OF DATA OR DATA BEING
RENDERED INACCURATE OR LOSSES SUSTAINED BY YOU OR THIRD PARTIES OR A
FAILURE OF THE LIBRARY TO OPERATE WITH ANY OTHER SOFTWARE), EVEN IF
SUCH HOLDER OR OTHER PARTY HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH
DAMAGES.

                     END OF TERMS AND CONDITIONS


           How to Apply These Terms to Your New Libraries

  If you develop a new library, and you want it to be of the greatest
possible use to the public, we recommend making it free software that
everyone can redistribute and change.  You can do so by permitting
redistribution under these terms (or, alternatively, under the terms of the
ordinary General Public License).

  To apply these terms, attach the following notices to the library.  It is
safest to attach them to the start of each source file to most effectively
convey the exclusion of warranty; and each file should have at least the
"copyright" line and a pointer to where the full notice is found.

    <one line to give the library's name and a brief idea of what it does.>
    Copyright (C) <year>  <name of author>

    This library is free software; you can redistribute it and/or
    modify it under the terms of the GNU Lesser General Public
    License as published by the Free Software Foundation; either
    version 2.1 of the License, or (at your option) any later version.

    This library is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
    Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public
    License along with this library; if not, write to the Free Software
    Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA

Also add information on how to contact you by electronic and paper mail.

You should also get your employer (if you work as a programmer) or your
school, if any, to sign a "copyright disclaimer" for the library, if
necessary.  Here is a sample; alter the names:

  Yoyodyne, Inc., hereby disclaims all copyright interest in the
  library `Frob' (a library for tweaking knobs) written by James Random Hacker.

  <signature of Ty Coon>, 1 April 1990
  Ty Coon, President of Vice

That's all there is to it!

----

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

----

Permission is hereby granted, free of charge, to any person obtaining a copy of this
software and associated documentation files (the "Software"), to deal in the Software
without restriction, including without limitation the rights to use, copy, modify,
merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

----

Mozilla Public License Version 2.0
==================================

1. Definitions
--------------

1.1. "Contributor"
    means each individual or legal entity that creates, contributes to
    the creation of, or owns Covered Software.

1.2. "Contributor Version"
    means the combination of the Contributions of others (if any) used
    by a Contributor and that particular Contributor's Contribution.

1.3. "Contribution"
    means Covered Software of a particular Contributor.

1.4. "Covered Software"
    means Source Code Form to which the initial Contributor has attached
    the notice in Exhibit A, the Executable Form of such Source Code
    Form, and Modifications of such Source Code Form, in each case
    including portions thereof.

1.5. "Incompatible With Secondary Licenses"
    means

    (a) that the initial Contributor has attached the notice described
        in Exhibit B to the Covered Software; or

    (b) that the Covered Software was made available under the terms of
        version 1.1 or earlier of the License, but not also under the
        terms of a Secondary License.

1.6. "Executable Form"
    means any form of the work other than Source Code Form.

1.7. "Larger Work"
    means a work that combines Covered Software with other material, in
    a separate file or files, that is not Covered Software.

1.8. "License"
    means this document.

1.9. "Licensable"
    means having the right to grant, to the maximum extent possible,
    whether at the time of the initial grant or subsequently, any and
    all of the rights conveyed by this License.

1.10. "Modifications"
    means any of the following:

    (a) any file in Source Code Form that results from an addition to,
        deletion from, or modification of the contents of Covered
        Software; or

    (b) any new file in Source Code Form that contains any Covered
        Software.

1.11. "Patent Claims" of a Contributor
    means any patent claim(s), including without limitation, method,
    process, and apparatus claims, in any patent Licensable by such
    Contributor that would be infringed, but for the grant of the
    License, by the making, using, selling, offering for sale, having
    made, import, or transfer of either its Contributions or its
    Contributor Version.

1.12. "Secondary License"
    means either the GNU General Public License, Version 2.0, the GNU
    Lesser General Public License, Version 2.1, the GNU Affero General
    Public License, Version 3.0, or any later versions of those
    licenses.

1.13. "Source Code Form"
    means the form of the work preferred for making modifications.

1.14. "You" (or "Your")
    means an individual or a legal entity exercising rights under this
    License. For legal entities, "You" includes any entity that
    controls, is controlled by, or is under common control with You. For
    purposes of this definition, "control" means (a) the power, direct
    or indirect, to cause the direction or management of such entity,
    whether by contract or otherwise, or (b) ownership of more than
    fifty percent (50%) of the outstanding shares or beneficial
    ownership of such entity.

2. License Grants and Conditions
--------------------------------

2.1. Grants

Each Contributor hereby grants You a world-wide, royalty-free,
non-exclusive license:

(a) under intellectual property rights (other than patent or trademark)
    Licensable by such Contributor to use, reproduce, make available,
    modify, display, perform, distribute, and otherwise exploit its
    Contributions, either on an unmodified basis, with Modifications, or
    as part of a Larger Work; and

(b) under Patent Claims of such Contributor to make, use, sell, offer
    for sale, have made, import, and otherwise transfer either its
    Contributions or its Contributor Version.

2.2. Effective Date

The licenses granted in Section 2.1 with respect to any Contribution
become effective for each Contribution on the date the Contributor first
distributes such Contribution.

2.3. Limitations on Grant Scope

The licenses granted in this Section 2 are the only rights granted under
this License. No additional rights or licenses will be implied from the
distribution or licensing of Covered Software under this License.
Notwithstanding Section 2.1(b) above, no patent license is granted by a
Contributor:

(a) for any code that a Contributor has removed from Covered Software;
    or

(b) for infringements caused by: (i) Your and any other third party's
    modifications of Covered Software, or (ii) the combination of its
    Contributions with other software (except as part of its Contributor
    Version); or

(c) under Patent Claims infringed by Covered Software in the absence of
    its Contributions.

This License does not grant any rights in the trademarks, service marks,
or logos of any Contributor (except as may be necessary to comply with
the notice requirements in Section 3.4).

2.4. Subsequent Licenses

No Contributor makes additional grants as a result of Your choice to
distribute the Covered Software under a subsequent version of this
License (see Section 10.2) or under the terms of a Secondary License (if
permitted under the terms of Section 3.3).

2.5. Representation

Each Contributor represents that the Contributor believes its
Contributions are its original creation(s) or it has sufficient rights
to grant the rights to its Contributions conveyed by this License.

2.6. Fair Use

This License is not intended to limit any rights You have under
applicable copyright doctrines of fair use, fair dealing, or other
equivalents.

2.7. Conditions

Sections 3.1, 3.2, 3.3, and 3.4 are conditions of the licenses granted
in Section 2.1.

3. Responsibilities
-------------------

3.1. Distribution of Source Form

All distribution of Covered Software in Source Code Form, including any
Modifications that You create or to which You contribute, must be under
the terms of this License. You must inform recipients that the Source
Code Form of the Covered Software is governed by the terms of this
License, and how they can obtain a copy of this License. You may not
attempt to alter or restrict the recipients' rights in the Source Code
Form.

3.2. Distribution of Executable Form

If You distribute Covered Software in Executable Form then:

(a) such Covered Software must also be made available in Source Code
    Form, as described in Section 3.1, and You must inform recipients of
    the Executable Form how they can obtain a copy of such Source Code
    Form by reasonable means in a timely manner, at a charge no more
    than the cost of distribution to the recipient; and

(b) You may distribute such Executable Form under the terms of this
    License, or sublicense it under different terms, provided that the
    license for the Executable Form does not attempt to limit or alter
    the recipients' rights in the Source Code Form under this License.

3.3. Distribution of a Larger Work

You may create and distribute a Larger Work under terms of Your choice,
provided that You also comply with the requirements of this License for
the Covered Software. If the Larger Work is a combination of Covered
Software with a work governed by one or more Secondary Licenses, and the
Covered Software is not Incompatible With Secondary Licenses, this
License permits You to additionally distribute such Covered Software
under the terms of such Secondary License(s), so that the recipient of
the Larger Work may, at their option, further distribute the Covered
Software under the terms of either this License or such Secondary
License(s).

3.4. Notices

You may not remove or alter the substance of any license notices
(including copyright notices, patent notices, disclaimers of warranty,
or limitations of liability) contained within the Source Code Form of
the Covered Software, except that You may alter any license notices to
the extent required to remedy known factual inaccuracies.

3.5. Application of Additional Terms

You may choose to offer, and to charge a fee for, warranty, support,
indemnity or liability obligations to one or more recipients of Covered
Software. However, You may do so only on Your own behalf, and not on
behalf of any Contributor. You must make it absolutely clear that any
such warranty, support, indemnity, or liability obligation is offered by
You alone, and You hereby agree to indemnify every Contributor for any
liability incurred by such Contributor as a result of warranty, support,
indemnity or liability terms You offer. You may include additional
disclaimers of warranty and limitations of liability specific to any
jurisdiction.

4. Inability to Comply Due to Statute or Regulation
---------------------------------------------------

If it is impossible for You to comply with any of the terms of this
License with respect to some or all of the Covered Software due to
statute, judicial order, or regulation then You must: (a) comply with
the terms of this License to the maximum extent possible; and (b)
describe the limitations and the code they affect. Such description must
be placed in a text file included with all distributions of the Covered
Software under this License. Except to the extent prohibited by statute
or regulation, such description must be sufficiently detailed for a
recipient of ordinary skill to be able to understand it.

5. Termination
--------------

5.1. The rights granted under this License will terminate automatically
if You fail to comply with any of its terms. However, if You become
compliant, then the rights granted under this License from a particular
Contributor are reinstated (a) provisionally, unless and until such
Contributor explicitly and finally terminates Your grants, and (b) on an
ongoing basis, if such Contributor fails to notify You of the
non-compliance by some reasonable means prior to 60 days after You have
come back into compliance. Moreover, Your grants from a particular
Contributor are reinstated on an ongoing basis if such Contributor
notifies You of the non-compliance by some reasonable means, this is the
first time You have received notice of non-compliance with this License
from such Contributor, and You become compliant prior to 30 days after
Your receipt of the notice.

5.2. If You initiate litigation against any entity by asserting a patent
infringement claim (excluding declaratory judgment actions,
counter-claims, and cross-claims) alleging that a Contributor Version
directly or indirectly infringes any patent, then the rights granted to
You by any and all Contributors for the Covered Software under Section
2.1 of this License shall terminate.

5.3. In the event of termination under Sections 5.1 or 5.2 above, all
end user license agreements (excluding distributors and resellers) which
have been validly granted by You or Your distributors under this License
prior to termination shall survive termination.

************************************************************************
*                                                                      *
*  6. Disclaimer of Warranty                                           *
*  -------------------------                                           *
*                                                                      *
*  Covered Software is provided under this License on an "as is"       *
*  basis, without warranty of any kind, either expressed, implied, or  *
*  statutory, including, without limitation, warranties that the       *
*  Covered Software is free of defects, merchantable, fit for a        *
*  particular purpose or non-infringing. The entire risk as to the     *
*  quality and performance of the Covered Software is with You.        *
*  Should any Covered Software prove defective in any respect, You     *
*  (not any Contributor) assume the cost of any necessary servicing,   *
*  repair, or correction. This disclaimer of warranty constitutes an   *
*  essential part of this License. No use of any Covered Software is   *
*  authorized under this License except under this disclaimer.         *
*                                                                      *
************************************************************************

************************************************************************
*                                                                      *
*  7. Limitation of Liability                                          *
*  --------------------------                                          *
*                                                                      *
*  Under no circumstances and under no legal theory, whether tort      *
*  (including negligence), contract, or otherwise, shall any           *
*  Contributor, or anyone who distributes Covered Software as          *
*  permitted above, be liable to You for any direct, indirect,         *
*  special, incidental, or consequential damages of any character      *
*  including, without limitation, damages for lost profits, loss of    *
*  goodwill, work stoppage, computer failure or malfunction, or any    *
*  and all other commercial damages or losses, even if such party      *
*  shall have been informed of the possibility of such damages. This   *
*  limitation of liability shall not apply to liability for death or   *
*  personal injury resulting from such party's negligence to the       *
*  extent applicable law prohibits such limitation. Some               *
*  jurisdictions do not allow the exclusion or limitation of           *
*  incidental or consequential damages, so this exclusion and          *
*  limitation may not apply to You.                                    *
*                                                                      *
************************************************************************

8. Litigation
-------------

Any litigation relating to this License may be brought only in the
courts of a jurisdiction where the defendant maintains its principal
place of business and such litigation shall be governed by laws of that
jurisdiction, without reference to its conflict-of-law provisions.
Nothing in this Section shall prevent a party's ability to bring
cross-claims or counter-claims.

9. Miscellaneous
----------------

This License represents the complete agreement concerning the subject
matter hereof. If any provision of this License is held to be
unenforceable, such provision shall be reformed only to the extent
necessary to make it enforceable. Any law or regulation which provides
that the language of a contract shall be construed against the drafter
shall not be used to construe this License against a Contributor.

10. Versions of the License
---------------------------

10.1. New Versions

Mozilla Foundation is the license steward. Except as provided in Section
10.3, no one other than the license steward has the right to modify or
publish new versions of this License. Each version will be given a
distinguishing version number.

10.2. Effect of New Versions

You may distribute the Covered Software under the terms of the version
of the License under which You originally received the Covered Software,
or under the terms of any subsequent version published by the license
steward.

10.3. Modified Versions

If you create software not governed by this License, and you want to
create a new license for such software, you may create and use a
modified version of this License if you rename the license and remove
any references to the name of the license steward (except to note that
such modified license differs from this License).

10.4. Distributing Source Code Form that is Incompatible With Secondary
Licenses

If You choose to distribute Source Code Form that is Incompatible With
Secondary Licenses under the terms of this version of the License, the
notice described in Exhibit B of this License must be attached.

Exhibit A - Source Code Form License Notice
-------------------------------------------

  This Source Code Form is subject to the terms of the Mozilla Public
  License, v. 2.0. If a copy of the MPL was not distributed with this
  file, You can obtain one at http://mozilla.org/MPL/2.0/.

If it is not possible or desirable to put the notice in a particular
file, then You may include the notice in a location (such as a LICENSE
file in a relevant directory) where a recipient would be likely to look
for such a notice.

You may add additional accurate notices of copyright ownership.

Exhibit B - "Incompatible With Secondary Licenses" Notice
---------------------------------------------------------

  This Source Code Form is "Incompatible With Secondary Licenses", as
  defined by the Mozilla Public License, v. 2.0.


````

## Avisos dos artefatos e fontes oficiais

Arquivos idênticos são apresentados uma única vez, com seus componentes associados.
Foram normalizados os finais de linha e espaços finais para apresentação em Markdown.

### Aviso 1

- `logback-classic-1.5.22.jar` — https://raw.githubusercontent.com/qos-ch/logback/v_1.5.22/LICENSE.txt
- `logback-core-1.5.22.jar` — https://raw.githubusercontent.com/qos-ch/logback/v_1.5.22/LICENSE.txt

````text
Logback LICENSE
---------------

Logback: the reliable, generic, fast and flexible logging framework.
Copyright (C) 1999-2024, QOS.ch. All rights reserved.

This program and the accompanying materials are dual-licensed under
either the terms of the Eclipse Public License v1.0 as published by
the Eclipse Foundation

  or (per the licensee's choosing)

under the terms of the GNU Lesser General Public License version 2.1
as published by the Free Software Foundation.
````

### Aviso 2

- `jackson-annotations-2.18.5.jar` — META-INF/LICENSE
- `jackson-core-2.18.5.jar` — META-INF/LICENSE
- `jackson-databind-2.18.5.jar` — META-INF/LICENSE
- `caffeine-3.1.8.jar` — META-INF/LICENSE
- `jakarta.inject-api-2.0.1.jar` — META-INF/LICENSE.txt
- `commons-lang3-3.17.0.jar` — META-INF/LICENSE.txt
- `log4j-api-2.24.3.jar` — META-INF/LICENSE
- `log4j-to-slf4j-2.24.3.jar` — META-INF/LICENSE
- `tomcat-embed-el-10.1.50.jar` — META-INF/LICENSE
- `tomcat-embed-websocket-10.1.50.jar` — META-INF/LICENSE
- `attoparser-2.0.7.RELEASE.jar` — META-INF/LICENSE.txt
- `springdoc-openapi-starter-common-2.8.3.jar` — META-INF/LICENSE
- `springdoc-openapi-starter-webflux-api-2.8.3.jar` — META-INF/LICENSE
- `springdoc-openapi-starter-webflux-ui-2.8.3.jar` — META-INF/LICENSE
- `springdoc-openapi-starter-webmvc-api-2.8.3.jar` — META-INF/LICENSE
- `springdoc-openapi-starter-webmvc-ui-2.8.3.jar` — META-INF/LICENSE
- `spring-modulith-api-1.3.2.jar` — META-INF/LICENSE
- `thymeleaf-spring6-3.1.3.RELEASE.jar` — META-INF/LICENSE.txt
- `unbescape-1.1.6.RELEASE.jar` — META-INF/LICENSE.txt
- `swagger-ui-5.18.2.jar` — https://raw.githubusercontent.com/swagger-api/swagger-ui/v5.18.2/LICENSE

````text
Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
````

### Aviso 3

- `jackson-annotations-2.18.5.jar` — META-INF/NOTICE
- `jackson-databind-2.18.5.jar` — META-INF/NOTICE

````text
# Jackson JSON processor

Jackson is a high-performance, Free/Open Source JSON processing library.
It was originally written by Tatu Saloranta (tatu.saloranta@iki.fi), and has
been in development since 2007.
It is currently developed by a community of developers.

## Copyright

Copyright 2007-, Tatu Saloranta (tatu.saloranta@iki.fi)

## Licensing

Jackson 2.x core and extension components are licensed under Apache License 2.0
To find the details that apply to this artifact see the accompanying LICENSE file.

## Credits

A list of contributors may be found from CREDITS(-2.x) file, which is included
in some artifacts (usually source distributions); but is always available
from the source code management (SCM) system project uses.
````

### Aviso 4

- `jackson-core-2.18.5.jar` — META-INF/NOTICE

````text
# Jackson JSON processor

Jackson is a high-performance, Free/Open Source JSON processing library.
It was originally written by Tatu Saloranta (tatu.saloranta@iki.fi), and has
been in development since 2007.
It is currently developed by a community of developers.

## Copyright

Copyright 2007-, Tatu Saloranta (tatu.saloranta@iki.fi)

## Licensing

Jackson 2.x core and extension components are licensed under Apache License 2.0
To find the details that apply to this artifact see the accompanying LICENSE file.

## Credits

A list of contributors may be found from CREDITS(-2.x) file, which is included
in some artifacts (usually source distributions); but is always available
from the source code management (SCM) system project uses.

## FastDoubleParser

jackson-core bundles a shaded copy of FastDoubleParser <https://github.com/wrandelshofer/FastDoubleParser>.
That code is available under an MIT license <https://github.com/wrandelshofer/FastDoubleParser/blob/main/LICENSE>
under the following copyright.

Copyright © 2023 Werner Randelshofer, Switzerland. MIT License.

See FastDoubleParser-NOTICE for details of other source code included in FastDoubleParser
and the licenses and copyrights that apply to that code.
````

### Aviso 5

- `jackson-dataformat-toml-2.18.5.jar` — META-INF/LICENSE

````text
This copy of Jackson JSON processor TOML module is licensed under the
Apache (Software) License, version 2.0 ("the License").
See the License for details about distribution rights, and the
specific rights regarding derivative works.

You may obtain a copy of the License at:

http://www.apache.org/licenses/LICENSE-2.0
````

### Aviso 6

- `jackson-dataformat-toml-2.18.5.jar` — META-INF/NOTICE
- `jackson-dataformat-yaml-2.18.5.jar` — META-INF/NOTICE

````text
# Jackson JSON processor

Jackson is a high-performance, Free/Open Source JSON processing library.
It was originally written by Tatu Saloranta (tatu.saloranta@iki.fi), and has
been in development since 2007.
It is currently developed by a community of developers.

## Copyright

Copyright 2007-, Tatu Saloranta (tatu.saloranta@iki.fi)

## Licensing

Jackson components are licensed under Apache (Software) License, version 2.0,
as per accompanying LICENSE file.

## Credits

A list of contributors may be found from CREDITS file, which is included
in some artifacts (usually source distributions); but is always available
from the source code management (SCM) system project uses.
````

### Aviso 7

- `jackson-dataformat-yaml-2.18.5.jar` — META-INF/LICENSE

````text
This copy of Jackson JSON processor YAML module is licensed under the
Apache (Software) License, version 2.0 ("the License").
See the License for details about distribution rights, and the
specific rights regarding derivative works.

You may obtain a copy of the License at:

http://www.apache.org/licenses/LICENSE-2.0
````

### Aviso 8

- `jackson-datatype-jdk8-2.18.5.jar` — META-INF/LICENSE

````text
This copy of Jackson JSON processor Java 8 datatype module is licensed under the
Apache (Software) License, version 2.0 ("the License").
See the License for details about distribution rights, and the
specific rights regarding derivative works.

You may obtain a copy of the License at:

http://www.apache.org/licenses/LICENSE-2.0
````

### Aviso 9

- `jackson-datatype-jdk8-2.18.5.jar` — META-INF/NOTICE
- `jackson-datatype-jsr310-2.18.5.jar` — META-INF/NOTICE
- `jackson-module-parameter-names-2.18.5.jar` — META-INF/NOTICE

````text
# Jackson JSON processor

Jackson is a high-performance, Free/Open Source JSON processing library.
It was originally written by Tatu Saloranta (tatu.saloranta@iki.fi), and has
been in development since 2007.
It is currently developed by a community of developers.

## Licensing

Jackson components are licensed under Apache (Software) License, version 2.0,
as per accompanying LICENSE file.

## Credits

A list of contributors may be found from CREDITS file, which is included
in some artifacts (usually source distributions); but is always available
from the source code management (SCM) system project uses.
````

### Aviso 10

- `jackson-datatype-jsr310-2.18.5.jar` — META-INF/LICENSE

````text
This copy of Jackson JSON processor Java 8 Date/Time module is licensed under the
Apache (Software) License, version 2.0 ("the License").
See the License for details about distribution rights, and the
specific rights regarding derivative works.

You may obtain a copy of the License at:

http://www.apache.org/licenses/LICENSE-2.0
````

### Aviso 11

- `jackson-module-parameter-names-2.18.5.jar` — META-INF/LICENSE

````text
This copy of Jackson JSON processor Java 8 parameter names module is licensed under the
Apache (Software) License, version 2.0 ("the License").
See the License for details about distribution rights, and the
specific rights regarding derivative works.

You may obtain a copy of the License at:

http://www.apache.org/licenses/LICENSE-2.0
````

### Aviso 12

- `classmate-1.7.1.jar` — META-INF/LICENSE

````text
This copy of Java ClassMate library is licensed under Apache (Software) License,
version 2.0 ("the License").
See the License for details about distribution rights, and the specific rights regarding derivate works.

You may obtain a copy of the License at:

http://www.apache.org/licenses/LICENSE-2.0
````

### Aviso 13

- `classmate-1.7.1.jar` — META-INF/NOTICE

````text
Java ClassMate library was originally written by Tatu Saloranta (tatu.saloranta@iki.fi)

Other developers who have contributed code are:

* Brian Langel

## Copyright

Copyright 2007-, Tatu Saloranta (tatu.saloranta@iki.fi)
````

### Aviso 14

- `amqp-client-5.22.0.jar` — https://raw.githubusercontent.com/rabbitmq/rabbitmq-java-client/v5.22.0/LICENSE

````text
This package, the RabbitMQ Java client library, is triple-licensed under
the Mozilla Public License 2.0 ("MPL"), the GNU General Public License
version 2 ("GPL") and the Apache License version 2 ("ASL"). For the MPL,
please see LICENSE-MPL-RabbitMQ. For the GPL, please see LICENSE-GPL2.
For the ASL, please see LICENSE-APACHE2.

The RabbitMQ Java client library includes third-party software under the
ASL. For this license, please see LICENSE-APACHE2. For attribution of
copyright and other details of provenance, please refer to the source code.

If you have any questions regarding licensing, please contact us at
info@rabbitmq.com.
````

### Aviso 15

- `amqp-client-5.22.0.jar` — https://raw.githubusercontent.com/rabbitmq/rabbitmq-java-client/v5.22.0/LICENSE-APACHE2
- `lettuce-core-6.4.2.RELEASE.jar` — META-INF/LICENSE
- `spring-amqp-3.2.8.jar` — META-INF/LICENSE.txt
- `spring-rabbit-3.2.8.jar` — META-INF/LICENSE.txt
- `spring-data-redis-3.4.13.jar` — license.txt
- `spring-retry-2.0.12.jar` — META-INF/LICENSE-2.0.txt

````text
Apache License
                           Version 2.0, January 2004
                        https://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
````

### Aviso 16

- `amqp-client-5.22.0.jar` — https://raw.githubusercontent.com/rabbitmq/rabbitmq-java-client/v5.22.0/LICENSE-GPL2

````text
GNU GENERAL PUBLIC LICENSE
		       Version 2, June 1991

 Copyright (C) 1989, 1991 Free Software Foundation, Inc.,
 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 Everyone is permitted to copy and distribute verbatim copies
 of this license document, but changing it is not allowed.

			    Preamble

  The licenses for most software are designed to take away your
freedom to share and change it.  By contrast, the GNU General Public
License is intended to guarantee your freedom to share and change free
software--to make sure the software is free for all its users.  This
General Public License applies to most of the Free Software
Foundation's software and to any other program whose authors commit to
using it.  (Some other Free Software Foundation software is covered by
the GNU Lesser General Public License instead.)  You can apply it to
your programs, too.

  When we speak of free software, we are referring to freedom, not
price.  Our General Public Licenses are designed to make sure that you
have the freedom to distribute copies of free software (and charge for
this service if you wish), that you receive source code or can get it
if you want it, that you can change the software or use pieces of it
in new free programs; and that you know you can do these things.

  To protect your rights, we need to make restrictions that forbid
anyone to deny you these rights or to ask you to surrender the rights.
These restrictions translate to certain responsibilities for you if you
distribute copies of the software, or if you modify it.

  For example, if you distribute copies of such a program, whether
gratis or for a fee, you must give the recipients all the rights that
you have.  You must make sure that they, too, receive or can get the
source code.  And you must show them these terms so they know their
rights.

  We protect your rights with two steps: (1) copyright the software, and
(2) offer you this license which gives you legal permission to copy,
distribute and/or modify the software.

  Also, for each author's protection and ours, we want to make certain
that everyone understands that there is no warranty for this free
software.  If the software is modified by someone else and passed on, we
want its recipients to know that what they have is not the original, so
that any problems introduced by others will not reflect on the original
authors' reputations.

  Finally, any free program is threatened constantly by software
patents.  We wish to avoid the danger that redistributors of a free
program will individually obtain patent licenses, in effect making the
program proprietary.  To prevent this, we have made it clear that any
patent must be licensed for everyone's free use or not licensed at all.

  The precise terms and conditions for copying, distribution and
modification follow.

		    GNU GENERAL PUBLIC LICENSE
   TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION

  0. This License applies to any program or other work which contains
a notice placed by the copyright holder saying it may be distributed
under the terms of this General Public License.  The "Program", below,
refers to any such program or work, and a "work based on the Program"
means either the Program or any derivative work under copyright law:
that is to say, a work containing the Program or a portion of it,
either verbatim or with modifications and/or translated into another
language.  (Hereinafter, translation is included without limitation in
the term "modification".)  Each licensee is addressed as "you".

Activities other than copying, distribution and modification are not
covered by this License; they are outside its scope.  The act of
running the Program is not restricted, and the output from the Program
is covered only if its contents constitute a work based on the
Program (independent of having been made by running the Program).
Whether that is true depends on what the Program does.

  1. You may copy and distribute verbatim copies of the Program's
source code as you receive it, in any medium, provided that you
conspicuously and appropriately publish on each copy an appropriate
copyright notice and disclaimer of warranty; keep intact all the
notices that refer to this License and to the absence of any warranty;
and give any other recipients of the Program a copy of this License
along with the Program.

You may charge a fee for the physical act of transferring a copy, and
you may at your option offer warranty protection in exchange for a fee.

  2. You may modify your copy or copies of the Program or any portion
of it, thus forming a work based on the Program, and copy and
distribute such modifications or work under the terms of Section 1
above, provided that you also meet all of these conditions:

    a) You must cause the modified files to carry prominent notices
    stating that you changed the files and the date of any change.

    b) You must cause any work that you distribute or publish, that in
    whole or in part contains or is derived from the Program or any
    part thereof, to be licensed as a whole at no charge to all third
    parties under the terms of this License.

    c) If the modified program normally reads commands interactively
    when run, you must cause it, when started running for such
    interactive use in the most ordinary way, to print or display an
    announcement including an appropriate copyright notice and a
    notice that there is no warranty (or else, saying that you provide
    a warranty) and that users may redistribute the program under
    these conditions, and telling the user how to view a copy of this
    License.  (Exception: if the Program itself is interactive but
    does not normally print such an announcement, your work based on
    the Program is not required to print an announcement.)

These requirements apply to the modified work as a whole.  If
identifiable sections of that work are not derived from the Program,
and can be reasonably considered independent and separate works in
themselves, then this License, and its terms, do not apply to those
sections when you distribute them as separate works.  But when you
distribute the same sections as part of a whole which is a work based
on the Program, the distribution of the whole must be on the terms of
this License, whose permissions for other licensees extend to the
entire whole, and thus to each and every part regardless of who wrote it.

Thus, it is not the intent of this section to claim rights or contest
your rights to work written entirely by you; rather, the intent is to
exercise the right to control the distribution of derivative or
collective works based on the Program.

In addition, mere aggregation of another work not based on the Program
with the Program (or with a work based on the Program) on a volume of
a storage or distribution medium does not bring the other work under
the scope of this License.

  3. You may copy and distribute the Program (or a work based on it,
under Section 2) in object code or executable form under the terms of
Sections 1 and 2 above provided that you also do one of the following:

    a) Accompany it with the complete corresponding machine-readable
    source code, which must be distributed under the terms of Sections
    1 and 2 above on a medium customarily used for software interchange; or,

    b) Accompany it with a written offer, valid for at least three
    years, to give any third party, for a charge no more than your
    cost of physically performing source distribution, a complete
    machine-readable copy of the corresponding source code, to be
    distributed under the terms of Sections 1 and 2 above on a medium
    customarily used for software interchange; or,

    c) Accompany it with the information you received as to the offer
    to distribute corresponding source code.  (This alternative is
    allowed only for noncommercial distribution and only if you
    received the program in object code or executable form with such
    an offer, in accord with Subsection b above.)

The source code for a work means the preferred form of the work for
making modifications to it.  For an executable work, complete source
code means all the source code for all modules it contains, plus any
associated interface definition files, plus the scripts used to
control compilation and installation of the executable.  However, as a
special exception, the source code distributed need not include
anything that is normally distributed (in either source or binary
form) with the major components (compiler, kernel, and so on) of the
operating system on which the executable runs, unless that component
itself accompanies the executable.

If distribution of executable or object code is made by offering
access to copy from a designated place, then offering equivalent
access to copy the source code from the same place counts as
distribution of the source code, even though third parties are not
compelled to copy the source along with the object code.

  4. You may not copy, modify, sublicense, or distribute the Program
except as expressly provided under this License.  Any attempt
otherwise to copy, modify, sublicense or distribute the Program is
void, and will automatically terminate your rights under this License.
However, parties who have received copies, or rights, from you under
this License will not have their licenses terminated so long as such
parties remain in full compliance.

  5. You are not required to accept this License, since you have not
signed it.  However, nothing else grants you permission to modify or
distribute the Program or its derivative works.  These actions are
prohibited by law if you do not accept this License.  Therefore, by
modifying or distributing the Program (or any work based on the
Program), you indicate your acceptance of this License to do so, and
all its terms and conditions for copying, distributing or modifying
the Program or works based on it.

  6. Each time you redistribute the Program (or any work based on the
Program), the recipient automatically receives a license from the
original licensor to copy, distribute or modify the Program subject to
these terms and conditions.  You may not impose any further
restrictions on the recipients' exercise of the rights granted herein.
You are not responsible for enforcing compliance by third parties to
this License.

  7. If, as a consequence of a court judgment or allegation of patent
infringement or for any other reason (not limited to patent issues),
conditions are imposed on you (whether by court order, agreement or
otherwise) that contradict the conditions of this License, they do not
excuse you from the conditions of this License.  If you cannot
distribute so as to satisfy simultaneously your obligations under this
License and any other pertinent obligations, then as a consequence you
may not distribute the Program at all.  For example, if a patent
license would not permit royalty-free redistribution of the Program by
all those who receive copies directly or indirectly through you, then
the only way you could satisfy both it and this License would be to
refrain entirely from distribution of the Program.

If any portion of this section is held invalid or unenforceable under
any particular circumstance, the balance of the section is intended to
apply and the section as a whole is intended to apply in other
circumstances.

It is not the purpose of this section to induce you to infringe any
patents or other property right claims or to contest validity of any
such claims; this section has the sole purpose of protecting the
integrity of the free software distribution system, which is
implemented by public license practices.  Many people have made
generous contributions to the wide range of software distributed
through that system in reliance on consistent application of that
system; it is up to the author/donor to decide if he or she is willing
to distribute software through any other system and a licensee cannot
impose that choice.

This section is intended to make thoroughly clear what is believed to
be a consequence of the rest of this License.

  8. If the distribution and/or use of the Program is restricted in
certain countries either by patents or by copyrighted interfaces, the
original copyright holder who places the Program under this License
may add an explicit geographical distribution limitation excluding
those countries, so that distribution is permitted only in or among
countries not thus excluded.  In such case, this License incorporates
the limitation as if written in the body of this License.

  9. The Free Software Foundation may publish revised and/or new versions
of the General Public License from time to time.  Such new versions will
be similar in spirit to the present version, but may differ in detail to
address new problems or concerns.

Each version is given a distinguishing version number.  If the Program
specifies a version number of this License which applies to it and "any
later version", you have the option of following the terms and conditions
either of that version or of any later version published by the Free
Software Foundation.  If the Program does not specify a version number of
this License, you may choose any version ever published by the Free Software
Foundation.

  10. If you wish to incorporate parts of the Program into other free
programs whose distribution conditions are different, write to the author
to ask for permission.  For software which is copyrighted by the Free
Software Foundation, write to the Free Software Foundation; we sometimes
make exceptions for this.  Our decision will be guided by the two goals
of preserving the free status of all derivatives of our free software and
of promoting the sharing and reuse of software generally.

			    NO WARRANTY

  11. BECAUSE THE PROGRAM IS LICENSED FREE OF CHARGE, THERE IS NO WARRANTY
FOR THE PROGRAM, TO THE EXTENT PERMITTED BY APPLICABLE LAW.  EXCEPT WHEN
OTHERWISE STATED IN WRITING THE COPYRIGHT HOLDERS AND/OR OTHER PARTIES
PROVIDE THE PROGRAM "AS IS" WITHOUT WARRANTY OF ANY KIND, EITHER EXPRESSED
OR IMPLIED, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE.  THE ENTIRE RISK AS
TO THE QUALITY AND PERFORMANCE OF THE PROGRAM IS WITH YOU.  SHOULD THE
PROGRAM PROVE DEFECTIVE, YOU ASSUME THE COST OF ALL NECESSARY SERVICING,
REPAIR OR CORRECTION.

  12. IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN WRITING
WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MAY MODIFY AND/OR
REDISTRIBUTE THE PROGRAM AS PERMITTED ABOVE, BE LIABLE TO YOU FOR DAMAGES,
INCLUDING ANY GENERAL, SPECIAL, INCIDENTAL OR CONSEQUENTIAL DAMAGES ARISING
OUT OF THE USE OR INABILITY TO USE THE PROGRAM (INCLUDING BUT NOT LIMITED
TO LOSS OF DATA OR DATA BEING RENDERED INACCURATE OR LOSSES SUSTAINED BY
YOU OR THIRD PARTIES OR A FAILURE OF THE PROGRAM TO OPERATE WITH ANY OTHER
PROGRAMS), EVEN IF SUCH HOLDER OR OTHER PARTY HAS BEEN ADVISED OF THE
POSSIBILITY OF SUCH DAMAGES.

		     END OF TERMS AND CONDITIONS

	    How to Apply These Terms to Your New Programs

  If you develop a new program, and you want it to be of the greatest
possible use to the public, the best way to achieve this is to make it
free software which everyone can redistribute and change under these terms.

  To do so, attach the following notices to the program.  It is safest
to attach them to the start of each source file to most effectively
convey the exclusion of warranty; and each file should have at least
the "copyright" line and a pointer to where the full notice is found.

    <one line to give the program's name and a brief idea of what it does.>
    Copyright (C) <year>  <name of author>

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along
    with this program; if not, write to the Free Software Foundation, Inc.,
    51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.

Also add information on how to contact you by electronic and paper mail.

If the program is interactive, make it output a short notice like this
when it starts in an interactive mode:

    Gnomovision version 69, Copyright (C) year name of author
    Gnomovision comes with ABSOLUTELY NO WARRANTY; for details type `show w'.
    This is free software, and you are welcome to redistribute it
    under certain conditions; type `show c' for details.

The hypothetical commands `show w' and `show c' should show the appropriate
parts of the General Public License.  Of course, the commands you use may
be called something other than `show w' and `show c'; they could even be
mouse-clicks or menu items--whatever suits your program.

You should also get your employer (if you work as a programmer) or your
school, if any, to sign a "copyright disclaimer" for the program, if
necessary.  Here is a sample; alter the names:

  Yoyodyne, Inc., hereby disclaims all copyright interest in the program
  `Gnomovision' (which makes passes at compilers) written by James Hacker.

  <signature of Ty Coon>, 1 April 1989
  Ty Coon, President of Vice

This General Public License does not permit incorporating your program into
proprietary programs.  If your program is a subroutine library, you may
consider it more useful to permit linking proprietary applications with the
library.  If this is what you want to do, use the GNU Lesser General
Public License instead of this License.
````

### Aviso 17

- `amqp-client-5.22.0.jar` — https://raw.githubusercontent.com/rabbitmq/rabbitmq-java-client/v5.22.0/LICENSE-MPL-RabbitMQ

````text
Mozilla Public License Version 2.0
==================================

1. Definitions
--------------

1.1. "Contributor"
    means each individual or legal entity that creates, contributes to
    the creation of, or owns Covered Software.

1.2. "Contributor Version"
    means the combination of the Contributions of others (if any) used
    by a Contributor and that particular Contributor's Contribution.

1.3. "Contribution"
    means Covered Software of a particular Contributor.

1.4. "Covered Software"
    means Source Code Form to which the initial Contributor has attached
    the notice in Exhibit A, the Executable Form of such Source Code
    Form, and Modifications of such Source Code Form, in each case
    including portions thereof.

1.5. "Incompatible With Secondary Licenses"
    means

    (a) that the initial Contributor has attached the notice described
        in Exhibit B to the Covered Software; or

    (b) that the Covered Software was made available under the terms of
        version 1.1 or earlier of the License, but not also under the
        terms of a Secondary License.

1.6. "Executable Form"
    means any form of the work other than Source Code Form.

1.7. "Larger Work"
    means a work that combines Covered Software with other material, in
    a separate file or files, that is not Covered Software.

1.8. "License"
    means this document.

1.9. "Licensable"
    means having the right to grant, to the maximum extent possible,
    whether at the time of the initial grant or subsequently, any and
    all of the rights conveyed by this License.

1.10. "Modifications"
    means any of the following:

    (a) any file in Source Code Form that results from an addition to,
        deletion from, or modification of the contents of Covered
        Software; or

    (b) any new file in Source Code Form that contains any Covered
        Software.

1.11. "Patent Claims" of a Contributor
    means any patent claim(s), including without limitation, method,
    process, and apparatus claims, in any patent Licensable by such
    Contributor that would be infringed, but for the grant of the
    License, by the making, using, selling, offering for sale, having
    made, import, or transfer of either its Contributions or its
    Contributor Version.

1.12. "Secondary License"
    means either the GNU General Public License, Version 2.0, the GNU
    Lesser General Public License, Version 2.1, the GNU Affero General
    Public License, Version 3.0, or any later versions of those
    licenses.

1.13. "Source Code Form"
    means the form of the work preferred for making modifications.

1.14. "You" (or "Your")
    means an individual or a legal entity exercising rights under this
    License. For legal entities, "You" includes any entity that
    controls, is controlled by, or is under common control with You. For
    purposes of this definition, "control" means (a) the power, direct
    or indirect, to cause the direction or management of such entity,
    whether by contract or otherwise, or (b) ownership of more than
    fifty percent (50%) of the outstanding shares or beneficial
    ownership of such entity.

2. License Grants and Conditions
--------------------------------

2.1. Grants

Each Contributor hereby grants You a world-wide, royalty-free,
non-exclusive license:

(a) under intellectual property rights (other than patent or trademark)
    Licensable by such Contributor to use, reproduce, make available,
    modify, display, perform, distribute, and otherwise exploit its
    Contributions, either on an unmodified basis, with Modifications, or
    as part of a Larger Work; and

(b) under Patent Claims of such Contributor to make, use, sell, offer
    for sale, have made, import, and otherwise transfer either its
    Contributions or its Contributor Version.

2.2. Effective Date

The licenses granted in Section 2.1 with respect to any Contribution
become effective for each Contribution on the date the Contributor first
distributes such Contribution.

2.3. Limitations on Grant Scope

The licenses granted in this Section 2 are the only rights granted under
this License. No additional rights or licenses will be implied from the
distribution or licensing of Covered Software under this License.
Notwithstanding Section 2.1(b) above, no patent license is granted by a
Contributor:

(a) for any code that a Contributor has removed from Covered Software;
    or

(b) for infringements caused by: (i) Your and any other third party's
    modifications of Covered Software, or (ii) the combination of its
    Contributions with other software (except as part of its Contributor
    Version); or

(c) under Patent Claims infringed by Covered Software in the absence of
    its Contributions.

This License does not grant any rights in the trademarks, service marks,
or logos of any Contributor (except as may be necessary to comply with
the notice requirements in Section 3.4).

2.4. Subsequent Licenses

No Contributor makes additional grants as a result of Your choice to
distribute the Covered Software under a subsequent version of this
License (see Section 10.2) or under the terms of a Secondary License (if
permitted under the terms of Section 3.3).

2.5. Representation

Each Contributor represents that the Contributor believes its
Contributions are its original creation(s) or it has sufficient rights
to grant the rights to its Contributions conveyed by this License.

2.6. Fair Use

This License is not intended to limit any rights You have under
applicable copyright doctrines of fair use, fair dealing, or other
equivalents.

2.7. Conditions

Sections 3.1, 3.2, 3.3, and 3.4 are conditions of the licenses granted
in Section 2.1.

3. Responsibilities
-------------------

3.1. Distribution of Source Form

All distribution of Covered Software in Source Code Form, including any
Modifications that You create or to which You contribute, must be under
the terms of this License. You must inform recipients that the Source
Code Form of the Covered Software is governed by the terms of this
License, and how they can obtain a copy of this License. You may not
attempt to alter or restrict the recipients' rights in the Source Code
Form.

3.2. Distribution of Executable Form

If You distribute Covered Software in Executable Form then:

(a) such Covered Software must also be made available in Source Code
    Form, as described in Section 3.1, and You must inform recipients of
    the Executable Form how they can obtain a copy of such Source Code
    Form by reasonable means in a timely manner, at a charge no more
    than the cost of distribution to the recipient; and

(b) You may distribute such Executable Form under the terms of this
    License, or sublicense it under different terms, provided that the
    license for the Executable Form does not attempt to limit or alter
    the recipients' rights in the Source Code Form under this License.

3.3. Distribution of a Larger Work

You may create and distribute a Larger Work under terms of Your choice,
provided that You also comply with the requirements of this License for
the Covered Software. If the Larger Work is a combination of Covered
Software with a work governed by one or more Secondary Licenses, and the
Covered Software is not Incompatible With Secondary Licenses, this
License permits You to additionally distribute such Covered Software
under the terms of such Secondary License(s), so that the recipient of
the Larger Work may, at their option, further distribute the Covered
Software under the terms of either this License or such Secondary
License(s).

3.4. Notices

You may not remove or alter the substance of any license notices
(including copyright notices, patent notices, disclaimers of warranty,
or limitations of liability) contained within the Source Code Form of
the Covered Software, except that You may alter any license notices to
the extent required to remedy known factual inaccuracies.

3.5. Application of Additional Terms

You may choose to offer, and to charge a fee for, warranty, support,
indemnity or liability obligations to one or more recipients of Covered
Software. However, You may do so only on Your own behalf, and not on
behalf of any Contributor. You must make it absolutely clear that any
such warranty, support, indemnity, or liability obligation is offered by
You alone, and You hereby agree to indemnify every Contributor for any
liability incurred by such Contributor as a result of warranty, support,
indemnity or liability terms You offer. You may include additional
disclaimers of warranty and limitations of liability specific to any
jurisdiction.

4. Inability to Comply Due to Statute or Regulation
---------------------------------------------------

If it is impossible for You to comply with any of the terms of this
License with respect to some or all of the Covered Software due to
statute, judicial order, or regulation then You must: (a) comply with
the terms of this License to the maximum extent possible; and (b)
describe the limitations and the code they affect. Such description must
be placed in a text file included with all distributions of the Covered
Software under this License. Except to the extent prohibited by statute
or regulation, such description must be sufficiently detailed for a
recipient of ordinary skill to be able to understand it.

5. Termination
--------------

5.1. The rights granted under this License will terminate automatically
if You fail to comply with any of its terms. However, if You become
compliant, then the rights granted under this License from a particular
Contributor are reinstated (a) provisionally, unless and until such
Contributor explicitly and finally terminates Your grants, and (b) on an
ongoing basis, if such Contributor fails to notify You of the
non-compliance by some reasonable means prior to 60 days after You have
come back into compliance. Moreover, Your grants from a particular
Contributor are reinstated on an ongoing basis if such Contributor
notifies You of the non-compliance by some reasonable means, this is the
first time You have received notice of non-compliance with this License
from such Contributor, and You become compliant prior to 30 days after
Your receipt of the notice.

5.2. If You initiate litigation against any entity by asserting a patent
infringement claim (excluding declaratory judgment actions,
counter-claims, and cross-claims) alleging that a Contributor Version
directly or indirectly infringes any patent, then the rights granted to
You by any and all Contributors for the Covered Software under Section
2.1 of this License shall terminate.

5.3. In the event of termination under Sections 5.1 or 5.2 above, all
end user license agreements (excluding distributors and resellers) which
have been validly granted by You or Your distributors under this License
prior to termination shall survive termination.

************************************************************************
*                                                                      *
*  6. Disclaimer of Warranty                                           *
*  -------------------------                                           *
*                                                                      *
*  Covered Software is provided under this License on an "as is"       *
*  basis, without warranty of any kind, either expressed, implied, or  *
*  statutory, including, without limitation, warranties that the       *
*  Covered Software is free of defects, merchantable, fit for a        *
*  particular purpose or non-infringing. The entire risk as to the     *
*  quality and performance of the Covered Software is with You.        *
*  Should any Covered Software prove defective in any respect, You     *
*  (not any Contributor) assume the cost of any necessary servicing,   *
*  repair, or correction. This disclaimer of warranty constitutes an   *
*  essential part of this License. No use of any Covered Software is   *
*  authorized under this License except under this disclaimer.         *
*                                                                      *
************************************************************************

************************************************************************
*                                                                      *
*  7. Limitation of Liability                                          *
*  --------------------------                                          *
*                                                                      *
*  Under no circumstances and under no legal theory, whether tort      *
*  (including negligence), contract, or otherwise, shall any           *
*  Contributor, or anyone who distributes Covered Software as          *
*  permitted above, be liable to You for any direct, indirect,         *
*  special, incidental, or consequential damages of any character      *
*  including, without limitation, damages for lost profits, loss of    *
*  goodwill, work stoppage, computer failure or malfunction, or any    *
*  and all other commercial damages or losses, even if such party      *
*  shall have been informed of the possibility of such damages. This   *
*  limitation of liability shall not apply to liability for death or   *
*  personal injury resulting from such party's negligence to the       *
*  extent applicable law prohibits such limitation. Some               *
*  jurisdictions do not allow the exclusion or limitation of           *
*  incidental or consequential damages, so this exclusion and          *
*  limitation may not apply to You.                                    *
*                                                                      *
************************************************************************

8. Litigation
-------------

Any litigation relating to this License may be brought only in the
courts of a jurisdiction where the defendant maintains its principal
place of business and such litigation shall be governed by laws of that
jurisdiction, without reference to its conflict-of-law provisions.
Nothing in this Section shall prevent a party's ability to bring
cross-claims or counter-claims.

9. Miscellaneous
----------------

This License represents the complete agreement concerning the subject
matter hereof. If any provision of this License is held to be
unenforceable, such provision shall be reformed only to the extent
necessary to make it enforceable. Any law or regulation which provides
that the language of a contract shall be construed against the drafter
shall not be used to construe this License against a Contributor.

10. Versions of the License
---------------------------

10.1. New Versions

Mozilla Foundation is the license steward. Except as provided in Section
10.3, no one other than the license steward has the right to modify or
publish new versions of this License. Each version will be given a
distinguishing version number.

10.2. Effect of New Versions

You may distribute the Covered Software under the terms of the version
of the License under which You originally received the Covered Software,
or under the terms of any subsequent version published by the license
steward.

10.3. Modified Versions

If you create software not governed by this License, and you want to
create a new license for such software, you may create and use a
modified version of this License if you rename the license and remove
any references to the name of the license steward (except to note that
such modified license differs from this License).

10.4. Distributing Source Code Form that is Incompatible With Secondary
Licenses

If You choose to distribute Source Code Form that is Incompatible With
Secondary Licenses under the terms of this version of the License, the
notice described in Exhibit B of this License must be attached.

Exhibit A - Source Code Form License Notice
-------------------------------------------

  This Source Code Form is subject to the terms of the Mozilla Public
  License, v. 2.0. If a copy of the MPL was not distributed with this
  file, You can obtain one at http://mozilla.org/MPL/2.0/.

If it is not possible or desirable to put the notice in a particular
file, then You may include the notice in a location (such as a LICENSE
file in a relevant directory) where a recipient would be likely to look
for such a notice.

Copyright (c) 2007-2023 Broadcom. All Rights Reserved.
The term "Broadcom" refers to Broadcom Inc. and/or its subsidiaries.
````

### Aviso 18

- `istack-commons-runtime-4.1.2.jar` — https://raw.githubusercontent.com/eclipse-ee4j/jaxb-istack-commons/4.1.2/LICENSE.md

````text
Copyright (c) 2017 Oracle and/or its affiliates. All rights reserved.

    Redistribution and use in source and binary forms, with or without
    modification, are permitted provided that the following conditions
    are met:

      - Redistributions of source code must retain the above copyright
        notice, this list of conditions and the following disclaimer.

      - Redistributions in binary form must reproduce the above copyright
        notice, this list of conditions and the following disclaimer in the
        documentation and/or other materials provided with the distribution.

      - Neither the name of the Eclipse Foundation, Inc. nor the names of its
        contributors may be used to endorse or promote products derived
        from this software without specific prior written permission.

    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
    IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
    THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
    PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
    CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
    EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
    PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
    PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
    LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
    NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
    SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
````

### Aviso 19

- `istack-commons-runtime-4.1.2.jar` — https://raw.githubusercontent.com/eclipse-ee4j/jaxb-istack-commons/4.1.2/NOTICE.md

````text
[//]: # " Copyright (c) 2018, 2020 Oracle and/or its affiliates. All rights reserved. "
[//]: # "  "
[//]: # " This program and the accompanying materials are made available under the "
[//]: # " terms of the Eclipse Distribution License v. 1.0, which is available at "
[//]: # " http://www.eclipse.org/org/documents/edl-v10.php. "
[//]: # "  "
[//]: # " SPDX-License-Identifier: BSD-3-Clause "

# Notices for Eclipse Implementation of JAXB

This content is produced and maintained by the Eclipse Implementation of JAXB
project.

* Project home: https://projects.eclipse.org/projects/ee4j.jaxb-impl

## Trademarks

Eclipse Implementation of JAXB is a trademark of the Eclipse Foundation.

## Copyright

All content is the property of the respective authors or their employers. For
more information regarding authorship of content, please consult the listed
source code repository logs.

## Declared Project Licenses

This program and the accompanying materials are made available under the terms
of the Eclipse Distribution License v. 1.0 which is available at
http://www.eclipse.org/org/documents/edl-v10.php.

SPDX-License-Identifier: BSD-3-Clause

## Source Code

The project maintains the following source code repositories:

* https://github.com/eclipse-ee4j/jaxb-ri
* https://github.com/eclipse-ee4j/jaxb-istack-commons
* https://github.com/eclipse-ee4j/jaxb-dtd-parser
* https://github.com/eclipse-ee4j/jaxb-fi
* https://github.com/eclipse-ee4j/jaxb-stax-ex
* https://github.com/eclipse-ee4j/jax-rpc-ri

## Third-party Content

This project leverages the following third party content.

Apache Ant (1.10.2)

* License: Apache-2.0 AND W3C AND LicenseRef-Public-Domain

Apache Ant (1.10.2)

* License: Apache-2.0 AND W3C AND LicenseRef-Public-Domain

Apache Felix (1.2.0)

* License: Apache License, 2.0

args4j (2.33)

* License: MIT License

dom4j (1.6.1)

* License: Custom license based on Apache 1.1

file-management (3.0.0)

* License: Apache-2.0
* Project: https://maven.apache.org/shared/file-management/
* Source:
   https://svn.apache.org/viewvc/maven/shared/tags/file-management-3.0.0/

JUnit (4.12)

* License: Eclipse Public License

JUnit (4.12)

* License: Eclipse Public License

maven-compat (3.5.2)

* License: Apache-2.0
* Project: https://maven.apache.org/ref/3.5.2/maven-compat/
* Source:
   https://mvnrepository.com/artifact/org.apache.maven/maven-compat/3.5.2

maven-core (3.5.2)

* License: Apache-2.0
* Project: https://maven.apache.org/ref/3.5.2/maven-core/index.html
* Source: https://mvnrepository.com/artifact/org.apache.maven/maven-core/3.5.2

maven-plugin-annotations (3.5)

* License: Apache-2.0
* Project: https://maven.apache.org/plugin-tools/maven-plugin-annotations/
* Source:
   https://github.com/apache/maven-plugin-tools/tree/master/maven-plugin-annotations

maven-plugin-api (3.5.2)

* License: Apache-2.0

maven-resolver-api (1.1.1)

* License: Apache-2.0

maven-resolver-api (1.1.1)

* License: Apache-2.0

maven-resolver-connector-basic (1.1.1)

* License: Apache-2.0

maven-resolver-impl (1.1.1)

* License: Apache-2.0

maven-resolver-spi (1.1.1)

* License: Apache-2.0

maven-resolver-transport-file (1.1.1)

* License: Apache-2.0
* Project: https://maven.apache.org/resolver/maven-resolver-transport-file/
* Source:
   https://github.com/apache/maven-resolver/tree/master/maven-resolver-transport-file

maven-resolver-util (1.1.1)

* License: Apache-2.0

maven-settings (3.5.2)

* License: Apache-2.0
* Source:
   https://mvnrepository.com/artifact/org.apache.maven/maven-settings/3.5.2

OSGi Service Platform Core Companion Code (6.0)

* License: Apache License, 2.0

plexus-archiver (3.5)

* License: Apache-2.0
* Project: https://codehaus-plexus.github.io/plexus-archiver/
* Source: https://github.com/codehaus-plexus/plexus-archiver

plexus-io (3.0.0)

* License: Apache-2.0

plexus-utils (3.1.0)

* License: Apache- 2.0 or Apache- 1.1 or BSD or Public Domain or Indiana
   University Extreme! Lab Software License V1.1.1 (Apache 1.1 style)

relaxng-datatype (1.0)

* License: New BSD license

Sax (0.2)

* License: SAX-PD
* Project: http://www.megginson.com/downloads/SAX/
* Source: http://sourceforge.net/project/showfiles.php?group_id=29449

testng (6.14.2)

* License: Apache-2.0 AND (MIT OR GPL-1.0+)
* Project: https://testng.org/doc/index.html
* Source: https://github.com/cbeust/testng

wagon-http-lightweight (3.0.0)

* License: Pending
* Project: https://maven.apache.org/wagon/
* Source:
   https://mvnrepository.com/artifact/org.apache.maven.wagon/wagon-http-lightweight/3.0.0

xz for java (1.8)

* License: LicenseRef-Public-Domain

## Cryptography

Content may contain encryption software. The country in which you are currently
may have restrictions on the import, possession, and use, and/or re-export to
another country, of encryption software. BEFORE using any encryption software,
please check the country's laws, regulations and policies concerning the import,
possession, or use, and re-export of encryption software, to see if this is
permitted.
````

### Aviso 20

- `lettuce-core-6.4.2.RELEASE.jar` — META-INF/NOTICE

````text
Lettuce Java Redis Client 6.4.2.RELEASE
Copyright (c) 2011-2020 Mark Paluch

This product is licensed to you under the Apache License, Version 2.0
(the "License"). You may not use this product except in compliance with
the License.

This product may include a number of subcomponents with separate
copyright notices and license terms. Your use of the source code for
these subcomponents is subject to the terms and conditions of the
subcomponent's license, as noted in the license file.
````

### Aviso 21

- `micrometer-commons-1.14.14.jar` — META-INF/LICENSE
- `micrometer-core-1.14.14.jar` — META-INF/LICENSE
- `micrometer-jakarta9-1.14.14.jar` — META-INF/LICENSE
- `micrometer-observation-1.14.14.jar` — META-INF/LICENSE
- `micrometer-registry-prometheus-1.14.14.jar` — META-INF/LICENSE
- `reactor-extra-3.5.4.jar` — META-INF/LICENSE
- `reactor-netty-core-1.2.13.jar` — META-INF/LICENSE
- `reactor-netty-http-1.2.13.jar` — META-INF/LICENSE
- `reactor-core-3.7.14.jar` — META-INF/LICENSE
- `spring-boot-actuator-autoconfigure-3.4.13.jar` — META-INF/LICENSE.txt
- `spring-boot-actuator-3.4.13.jar` — META-INF/LICENSE.txt
- `spring-boot-autoconfigure-3.4.13.jar` — META-INF/LICENSE.txt
- `spring-boot-jarmode-tools-3.4.13.jar` — META-INF/LICENSE.txt
- `spring-boot-3.4.13.jar` — META-INF/LICENSE.txt
- `spring-security-config-6.4.13.jar` — META-INF/LICENSE.txt
- `spring-security-core-6.4.13.jar` — META-INF/LICENSE.txt
- `spring-security-crypto-6.4.13.jar` — META-INF/LICENSE.txt
- `spring-security-oauth2-client-6.4.13.jar` — META-INF/LICENSE.txt
- `spring-security-oauth2-core-6.4.13.jar` — META-INF/LICENSE.txt
- `spring-security-oauth2-jose-6.4.13.jar` — META-INF/LICENSE.txt
- `spring-security-oauth2-resource-server-6.4.13.jar` — META-INF/LICENSE.txt
- `spring-security-web-6.4.13.jar` — META-INF/LICENSE.txt

````text
Apache License
                           Version 2.0, January 2004
                        https://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "{}"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright {yyyy} {name of copyright owner}

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
````

### Aviso 22

- `micrometer-commons-1.14.14.jar` — META-INF/NOTICE
- `micrometer-core-1.14.14.jar` — META-INF/NOTICE
- `micrometer-jakarta9-1.14.14.jar` — META-INF/NOTICE
- `micrometer-observation-1.14.14.jar` — META-INF/NOTICE
- `micrometer-registry-prometheus-1.14.14.jar` — META-INF/NOTICE

````text
Micrometer

Copyright (c) 2017-Present VMware, Inc. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

-------------------------------------------------------------------------------

This product contains a modified portion of 'io.netty.util.internal.logging',
in the Netty/Common library distributed by The Netty Project:

  * Copyright 2013 The Netty Project
  * License: Apache License v2.0
  * Homepage: https://netty.io

This product contains a modified portion of 'StringUtils.isBlank()',
in the Commons Lang library distributed by The Apache Software Foundation:

  * Copyright 2001-2019 The Apache Software Foundation
  * License: Apache License v2.0
  * Homepage: https://commons.apache.org/proper/commons-lang/

This product contains a modified portion of 'JsonUtf8Writer',
in the Moshi library distributed by Square, Inc:

  * Copyright 2010 Google Inc.
  * License: Apache License v2.0
  * Homepage: https://github.com/square/moshi

This product contains a modified portion of the 'org.springframework.lang'
package in the Spring Framework library, distributed by VMware, Inc:

  * Copyright 2002-2019 the original author or authors.
  * License: Apache License v2.0
  * Homepage: https://spring.io/projects/spring-framework
````

### Aviso 23

- `netty-buffer-4.1.130.Final.jar` — https://raw.githubusercontent.com/netty/netty/netty-4.1.130.Final/NOTICE.txt
- `netty-codec-dns-4.1.130.Final.jar` — https://raw.githubusercontent.com/netty/netty/netty-4.1.130.Final/NOTICE.txt
- `netty-codec-http2-4.1.130.Final.jar` — https://raw.githubusercontent.com/netty/netty/netty-4.1.130.Final/NOTICE.txt
- `netty-codec-http-4.1.130.Final.jar` — https://raw.githubusercontent.com/netty/netty/netty-4.1.130.Final/NOTICE.txt
- `netty-codec-socks-4.1.130.Final.jar` — https://raw.githubusercontent.com/netty/netty/netty-4.1.130.Final/NOTICE.txt
- `netty-codec-4.1.130.Final.jar` — https://raw.githubusercontent.com/netty/netty/netty-4.1.130.Final/NOTICE.txt
- `netty-common-4.1.130.Final.jar` — https://raw.githubusercontent.com/netty/netty/netty-4.1.130.Final/NOTICE.txt
- `netty-handler-proxy-4.1.130.Final.jar` — https://raw.githubusercontent.com/netty/netty/netty-4.1.130.Final/NOTICE.txt
- `netty-handler-4.1.130.Final.jar` — https://raw.githubusercontent.com/netty/netty/netty-4.1.130.Final/NOTICE.txt
- `netty-resolver-dns-classes-macos-4.1.130.Final.jar` — https://raw.githubusercontent.com/netty/netty/netty-4.1.130.Final/NOTICE.txt
- `netty-resolver-dns-native-macos-4.1.130.Final-osx-x86_64.jar` — https://raw.githubusercontent.com/netty/netty/netty-4.1.130.Final/NOTICE.txt
- `netty-resolver-dns-4.1.130.Final.jar` — https://raw.githubusercontent.com/netty/netty/netty-4.1.130.Final/NOTICE.txt
- `netty-resolver-4.1.130.Final.jar` — https://raw.githubusercontent.com/netty/netty/netty-4.1.130.Final/NOTICE.txt
- `netty-transport-classes-epoll-4.1.130.Final.jar` — https://raw.githubusercontent.com/netty/netty/netty-4.1.130.Final/NOTICE.txt
- `netty-transport-native-epoll-4.1.130.Final-linux-x86_64.jar` — https://raw.githubusercontent.com/netty/netty/netty-4.1.130.Final/NOTICE.txt
- `netty-transport-native-unix-common-4.1.130.Final.jar` — https://raw.githubusercontent.com/netty/netty/netty-4.1.130.Final/NOTICE.txt
- `netty-transport-4.1.130.Final.jar` — https://raw.githubusercontent.com/netty/netty/netty-4.1.130.Final/NOTICE.txt

````text
The Netty Project
                            =================

Please visit the Netty web site for more information:

  * https://netty.io/

Copyright 2014 The Netty Project

The Netty Project licenses this file to you under the Apache License,
version 2.0 (the "License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at:

  https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
License for the specific language governing permissions and limitations
under the License.

Also, please refer to each LICENSE.<component>.txt file, which is located in
the 'license' directory of the distribution file, for the license terms of the
components that this product depends on.

-------------------------------------------------------------------------------
This product contains the extensions to Java Collections Framework which has
been derived from the works by JSR-166 EG, Doug Lea, and Jason T. Greene:

  * LICENSE:
    * license/LICENSE.jsr166y.txt (Public Domain)
  * HOMEPAGE:
    * http://gee.cs.oswego.edu/cgi-bin/viewcvs.cgi/jsr166/
    * http://viewvc.jboss.org/cgi-bin/viewvc.cgi/jbosscache/experimental/jsr166/

This product contains a modified version of Robert Harder's Public Domain
Base64 Encoder and Decoder, which can be obtained at:

  * LICENSE:
    * license/LICENSE.base64.txt (Public Domain)
  * HOMEPAGE:
    * http://iharder.sourceforge.net/current/java/base64/

This product contains a modified portion of 'Webbit', an event based
WebSocket and HTTP server, which can be obtained at:

  * LICENSE:
    * license/LICENSE.webbit.txt (BSD License)
  * HOMEPAGE:
    * https://github.com/joewalnes/webbit

This product contains a modified portion of 'SLF4J', a simple logging
facade for Java, which can be obtained at:

  * LICENSE:
    * license/LICENSE.slf4j.txt (MIT License)
  * HOMEPAGE:
    * https://www.slf4j.org/

This product contains a modified portion of 'Apache Harmony', an open source
Java SE, which can be obtained at:

  * NOTICE:
    * license/NOTICE.harmony.txt
  * LICENSE:
    * license/LICENSE.harmony.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://archive.apache.org/dist/harmony/

This product contains a modified portion of 'jbzip2', a Java bzip2 compression
and decompression library written by Matthew J. Francis. It can be obtained at:

  * LICENSE:
    * license/LICENSE.jbzip2.txt (MIT License)
  * HOMEPAGE:
    * https://code.google.com/p/jbzip2/

This product contains a modified portion of 'libdivsufsort', a C API library to construct
the suffix array and the Burrows-Wheeler transformed string for any input string of
a constant-size alphabet written by Yuta Mori. It can be obtained at:

  * LICENSE:
    * license/LICENSE.libdivsufsort.txt (MIT License)
  * HOMEPAGE:
    * https://github.com/y-256/libdivsufsort

This product contains a modified portion of Nitsan Wakart's 'JCTools', Java Concurrency Tools for the JVM,
 which can be obtained at:

  * LICENSE:
    * license/LICENSE.jctools.txt (ASL2 License)
  * HOMEPAGE:
    * https://github.com/JCTools/JCTools

This product optionally depends on 'JZlib', a re-implementation of zlib in
pure Java, which can be obtained at:

  * LICENSE:
    * license/LICENSE.jzlib.txt (BSD style License)
  * HOMEPAGE:
    * http://www.jcraft.com/jzlib/

This product optionally depends on 'Compress-LZF', a Java library for encoding and
decoding data in LZF format, written by Tatu Saloranta. It can be obtained at:

  * LICENSE:
    * license/LICENSE.compress-lzf.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://github.com/ning/compress

This product optionally depends on 'lz4', a LZ4 Java compression
and decompression library written by Adrien Grand. It can be obtained at:

  * LICENSE:
    * license/LICENSE.lz4.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://github.com/jpountz/lz4-java

This product optionally depends on 'lzma-java', a LZMA Java compression
and decompression library, which can be obtained at:

  * LICENSE:
    * license/LICENSE.lzma-java.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://github.com/jponge/lzma-java

This product optionally depends on 'zstd-jni', a zstd-jni Java compression
and decompression library, which can be obtained at:

  * LICENSE:
    * license/LICENSE.zstd-jni.txt (BSD)
  * HOMEPAGE:
    * https://github.com/luben/zstd-jni

This product contains a modified portion of 'jfastlz', a Java port of FastLZ compression
and decompression library written by William Kinney. It can be obtained at:

  * LICENSE:
    * license/LICENSE.jfastlz.txt (MIT License)
  * HOMEPAGE:
    * https://code.google.com/p/jfastlz/

This product contains a modified portion of and optionally depends on 'Protocol Buffers', Google's data
interchange format, which can be obtained at:

  * LICENSE:
    * license/LICENSE.protobuf.txt (New BSD License)
  * HOMEPAGE:
    * https://github.com/google/protobuf

This product optionally depends on 'Bouncy Castle Crypto APIs' to generate
a temporary self-signed X.509 certificate when the JVM does not provide the
equivalent functionality.  It can be obtained at:

  * LICENSE:
    * license/LICENSE.bouncycastle.txt (MIT License)
  * HOMEPAGE:
    * https://www.bouncycastle.org/

This product optionally depends on 'Snappy', a compression library produced
by Google Inc, which can be obtained at:

  * LICENSE:
    * license/LICENSE.snappy.txt (New BSD License)
  * HOMEPAGE:
    * https://github.com/google/snappy

This product optionally depends on 'JBoss Marshalling', an alternative Java
serialization API, which can be obtained at:

  * LICENSE:
    * license/LICENSE.jboss-marshalling.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://github.com/jboss-remoting/jboss-marshalling

This product optionally depends on 'Caliper', Google's micro-
benchmarking framework, which can be obtained at:

  * LICENSE:
    * license/LICENSE.caliper.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://github.com/google/caliper

This product optionally depends on 'Apache Commons Logging', a logging
framework, which can be obtained at:

  * LICENSE:
    * license/LICENSE.commons-logging.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://commons.apache.org/logging/

This product optionally depends on 'Apache Log4J', a logging framework, which
can be obtained at:

  * LICENSE:
    * license/LICENSE.log4j.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://logging.apache.org/log4j/

This product optionally depends on 'Aalto XML', an ultra-high performance
non-blocking XML processor, which can be obtained at:

  * LICENSE:
    * license/LICENSE.aalto-xml.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://wiki.fasterxml.com/AaltoHome

This product contains a modified version of 'HPACK', a Java implementation of
the HTTP/2 HPACK algorithm written by Twitter. It can be obtained at:

  * LICENSE:
    * license/LICENSE.hpack.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://github.com/twitter/hpack

This product contains a modified version of 'HPACK', a Java implementation of
the HTTP/2 HPACK algorithm written by Cory Benfield. It can be obtained at:

  * LICENSE:
    * license/LICENSE.hyper-hpack.txt (MIT License)
  * HOMEPAGE:
    * https://github.com/python-hyper/hpack/

This product contains a modified version of 'HPACK', a Java implementation of
the HTTP/2 HPACK algorithm written by Tatsuhiro Tsujikawa. It can be obtained at:

  * LICENSE:
    * license/LICENSE.nghttp2-hpack.txt (MIT License)
  * HOMEPAGE:
    * https://github.com/nghttp2/nghttp2/

This product contains a modified portion of 'Apache Commons Lang', a Java library
provides utilities for the java.lang API, which can be obtained at:

  * LICENSE:
    * license/LICENSE.commons-lang.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://commons.apache.org/proper/commons-lang/


This product contains the Maven wrapper scripts from 'Maven Wrapper', that provides an easy way to ensure a user has everything necessary to run the Maven build.

  * LICENSE:
    * license/LICENSE.mvn-wrapper.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://github.com/takari/maven-wrapper

This product contains the dnsinfo.h header file, that provides a way to retrieve the system DNS configuration on MacOS.
This private header is also used by Apple's open source
 mDNSResponder (https://opensource.apple.com/tarballs/mDNSResponder/).

 * LICENSE:
    * license/LICENSE.dnsinfo.txt (Apple Public Source License 2.0)
  * HOMEPAGE:
    * https://www.opensource.apple.com/source/configd/configd-453.19/dnsinfo/dnsinfo.h

This product optionally depends on 'Brotli4j', Brotli compression and
decompression for Java., which can be obtained at:

  * LICENSE:
    * license/LICENSE.brotli4j.txt (Apache License 2.0)
  * HOMEPAGE:
    * https://github.com/hyperxpro/Brotli4j
````

### Aviso 24

- `swagger-annotations-jakarta-2.2.27.jar` — META-INF/LICENSE
- `swagger-core-jakarta-2.2.27.jar` — META-INF/LICENSE
- `swagger-models-jakarta-2.2.27.jar` — META-INF/LICENSE

````text
Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright (c) 2015. SmartBear Software Inc.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
````

### Aviso 25

- `swagger-annotations-jakarta-2.2.27.jar` — META-INF/NOTICE

````text
Swagger Core - swagger-annotations
Copyright (c) 2015. SmartBear Software Inc.
Swagger Core - swagger-annotations  is licensed under Apache 2.0 license.
Copy of the Apache 2.0 license can be found in `LICENSE` file.
````

### Aviso 26

- `swagger-core-jakarta-2.2.27.jar` — META-INF/NOTICE

````text
Swagger Core - swagger-core
Copyright (c) 2015. SmartBear Software Inc.
Swagger Core - swagger-core  is licensed under Apache 2.0 license.
Copy of the Apache 2.0 license can be found in `LICENSE` file.
````

### Aviso 27

- `swagger-models-jakarta-2.2.27.jar` — META-INF/NOTICE

````text
Swagger Core - swagger-models
Copyright (c) 2015. SmartBear Software Inc.
Swagger Core - swagger-models  is licensed under Apache 2.0 license.
Copy of the Apache 2.0 license can be found in `LICENSE` file.
````

### Aviso 28

- `jakarta.activation-api-2.1.4.jar` — META-INF/LICENSE.md
- `angus-activation-2.0.3.jar` — META-INF/LICENSE.md

````text
Copyright (c) 2018 Oracle and/or its affiliates. All rights reserved.

    Redistribution and use in source and binary forms, with or without
    modification, are permitted provided that the following conditions
    are met:

      - Redistributions of source code must retain the above copyright
        notice, this list of conditions and the following disclaimer.

      - Redistributions in binary form must reproduce the above copyright
        notice, this list of conditions and the following disclaimer in the
        documentation and/or other materials provided with the distribution.

      - Neither the name of the Eclipse Foundation, Inc. nor the names of its
        contributors may be used to endorse or promote products derived
        from this software without specific prior written permission.

    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
    IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
    THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
    PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
    CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
    EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
    PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
    PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
    LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
    NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
    SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
````

### Aviso 29

- `jakarta.activation-api-2.1.4.jar` — META-INF/NOTICE.md

````text
# Notices for Jakarta Activation

This content is produced and maintained by the Jakarta Activation project.

* Project home: https://projects.eclipse.org/projects/ee4j.jaf

## Trademarks

Jakarta Activation is a trademark of the Eclipse Foundation.

## Copyright

All content is the property of the respective authors or their employers. For
more information regarding authorship of content, please consult the listed
source code repository logs.

## Declared Project Licenses

This program and the accompanying materials are made available under the terms
of the Eclipse Public License v. 2.0 which is available at
https://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License v1.0
which is available at https://www.eclipse.org/org/documents/edl-v10.php. This
Source Code may also be made available under the following Secondary Licenses
when the conditions for such availability set forth in the Eclipse Public
License v. 2.0 are satisfied: (secondary) GPL-2.0 with Classpath-exception-2.0
which is available at https://openjdk.java.net/legal/gplv2+ce.html.

SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause OR GPL-2.0-only with
Classpath-exception-2.0

## Source Code

The project maintains the following source code repositories:

* https://github.com/jakartaee/jaf-api
* https://github.com/jakartaee/jaf-tck

## Third-party Content

This project leverages the following third party content.

Apache Ant (1.9.6)

* License: Apache License, 2.0, W3C License, Public Domain

Apache Ant (1.9.6)

* License: Apache License, 2.0, W3C License, Public Domain

Apache commons-lang (3.5)

* License: Apache-2.0

font-awesome (4.7.0)

* License: OFL-1.1 AND MIT

jsoup (1.10.2)

* License: MIT

JTHarness (5.0)

* License: (GPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0)
* Project: https://wiki.openjdk.java.net/display/CodeTools/JT+Harness
* Source: http://hg.openjdk.java.net/code-tools/jtharness/

JUnit (4.12)

* License: Eclipse Public License

normalize.css (3.0.2)

* License: MIT
* Project: http://necolas.github.io/normalize.css/
* Source: http://necolas.github.io/normalize.css/

SigTest (4.0)

* License: GPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
* Project: https://wiki.openjdk.java.net/display/CodeTools/sigtest
* Source: http://hg.openjdk.java.net/code-tools/sigtest/file/c57f97e2ac2f

## Cryptography

Content may contain encryption software. The country in which you are currently
may have restrictions on the import, possession, and use, and/or re-export to
another country, of encryption software. BEFORE using any encryption software,
please check the country's laws, regulations and policies concerning the import,
possession, or use, and re-export of encryption software, to see if this is
permitted.
````

### Aviso 30

- `jakarta.annotation-api-2.1.1.jar` — META-INF/LICENSE.md
- `jakarta.transaction-api-2.0.1.jar` — META-INF/LICENSE.md

````text
# Eclipse Public License - v 2.0

        THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
        PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
        OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.

    1. DEFINITIONS

    "Contribution" means:

      a) in the case of the initial Contributor, the initial content
         Distributed under this Agreement, and

      b) in the case of each subsequent Contributor:
         i) changes to the Program, and
         ii) additions to the Program;
      where such changes and/or additions to the Program originate from
      and are Distributed by that particular Contributor. A Contribution
      "originates" from a Contributor if it was added to the Program by
      such Contributor itself or anyone acting on such Contributor's behalf.
      Contributions do not include changes or additions to the Program that
      are not Modified Works.

    "Contributor" means any person or entity that Distributes the Program.

    "Licensed Patents" mean patent claims licensable by a Contributor which
    are necessarily infringed by the use or sale of its Contribution alone
    or when combined with the Program.

    "Program" means the Contributions Distributed in accordance with this
    Agreement.

    "Recipient" means anyone who receives the Program under this Agreement
    or any Secondary License (as applicable), including Contributors.

    "Derivative Works" shall mean any work, whether in Source Code or other
    form, that is based on (or derived from) the Program and for which the
    editorial revisions, annotations, elaborations, or other modifications
    represent, as a whole, an original work of authorship.

    "Modified Works" shall mean any work in Source Code or other form that
    results from an addition to, deletion from, or modification of the
    contents of the Program, including, for purposes of clarity any new file
    in Source Code form that contains any contents of the Program. Modified
    Works shall not include works that contain only declarations,
    interfaces, types, classes, structures, or files of the Program solely
    in each case in order to link to, bind by name, or subclass the Program
    or Modified Works thereof.

    "Distribute" means the acts of a) distributing or b) making available
    in any manner that enables the transfer of a copy.

    "Source Code" means the form of a Program preferred for making
    modifications, including but not limited to software source code,
    documentation source, and configuration files.

    "Secondary License" means either the GNU General Public License,
    Version 2.0, or any later versions of that license, including any
    exceptions or additional permissions as identified by the initial
    Contributor.

    2. GRANT OF RIGHTS

      a) Subject to the terms of this Agreement, each Contributor hereby
      grants Recipient a non-exclusive, worldwide, royalty-free copyright
      license to reproduce, prepare Derivative Works of, publicly display,
      publicly perform, Distribute and sublicense the Contribution of such
      Contributor, if any, and such Derivative Works.

      b) Subject to the terms of this Agreement, each Contributor hereby
      grants Recipient a non-exclusive, worldwide, royalty-free patent
      license under Licensed Patents to make, use, sell, offer to sell,
      import and otherwise transfer the Contribution of such Contributor,
      if any, in Source Code or other form. This patent license shall
      apply to the combination of the Contribution and the Program if, at
      the time the Contribution is added by the Contributor, such addition
      of the Contribution causes such combination to be covered by the
      Licensed Patents. The patent license shall not apply to any other
      combinations which include the Contribution. No hardware per se is
      licensed hereunder.

      c) Recipient understands that although each Contributor grants the
      licenses to its Contributions set forth herein, no assurances are
      provided by any Contributor that the Program does not infringe the
      patent or other intellectual property rights of any other entity.
      Each Contributor disclaims any liability to Recipient for claims
      brought by any other entity based on infringement of intellectual
      property rights or otherwise. As a condition to exercising the
      rights and licenses granted hereunder, each Recipient hereby
      assumes sole responsibility to secure any other intellectual
      property rights needed, if any. For example, if a third party
      patent license is required to allow Recipient to Distribute the
      Program, it is Recipient's responsibility to acquire that license
      before distributing the Program.

      d) Each Contributor represents that to its knowledge it has
      sufficient copyright rights in its Contribution, if any, to grant
      the copyright license set forth in this Agreement.

      e) Notwithstanding the terms of any Secondary License, no
      Contributor makes additional grants to any Recipient (other than
      those set forth in this Agreement) as a result of such Recipient's
      receipt of the Program under the terms of a Secondary License
      (if permitted under the terms of Section 3).

    3. REQUIREMENTS

    3.1 If a Contributor Distributes the Program in any form, then:

      a) the Program must also be made available as Source Code, in
      accordance with section 3.2, and the Contributor must accompany
      the Program with a statement that the Source Code for the Program
      is available under this Agreement, and informs Recipients how to
      obtain it in a reasonable manner on or through a medium customarily
      used for software exchange; and

      b) the Contributor may Distribute the Program under a license
      different than this Agreement, provided that such license:
         i) effectively disclaims on behalf of all other Contributors all
         warranties and conditions, express and implied, including
         warranties or conditions of title and non-infringement, and
         implied warranties or conditions of merchantability and fitness
         for a particular purpose;

         ii) effectively excludes on behalf of all other Contributors all
         liability for damages, including direct, indirect, special,
         incidental and consequential damages, such as lost profits;

         iii) does not attempt to limit or alter the recipients' rights
         in the Source Code under section 3.2; and

         iv) requires any subsequent distribution of the Program by any
         party to be under a license that satisfies the requirements
         of this section 3.

    3.2 When the Program is Distributed as Source Code:

      a) it must be made available under this Agreement, or if the
      Program (i) is combined with other material in a separate file or
      files made available under a Secondary License, and (ii) the initial
      Contributor attached to the Source Code the notice described in
      Exhibit A of this Agreement, then the Program may be made available
      under the terms of such Secondary Licenses, and

      b) a copy of this Agreement must be included with each copy of
      the Program.

    3.3 Contributors may not remove or alter any copyright, patent,
    trademark, attribution notices, disclaimers of warranty, or limitations
    of liability ("notices") contained within the Program from any copy of
    the Program which they Distribute, provided that Contributors may add
    their own appropriate notices.

    4. COMMERCIAL DISTRIBUTION

    Commercial distributors of software may accept certain responsibilities
    with respect to end users, business partners and the like. While this
    license is intended to facilitate the commercial use of the Program,
    the Contributor who includes the Program in a commercial product
    offering should do so in a manner which does not create potential
    liability for other Contributors. Therefore, if a Contributor includes
    the Program in a commercial product offering, such Contributor
    ("Commercial Contributor") hereby agrees to defend and indemnify every
    other Contributor ("Indemnified Contributor") against any losses,
    damages and costs (collectively "Losses") arising from claims, lawsuits
    and other legal actions brought by a third party against the Indemnified
    Contributor to the extent caused by the acts or omissions of such
    Commercial Contributor in connection with its distribution of the Program
    in a commercial product offering. The obligations in this section do not
    apply to any claims or Losses relating to any actual or alleged
    intellectual property infringement. In order to qualify, an Indemnified
    Contributor must: a) promptly notify the Commercial Contributor in
    writing of such claim, and b) allow the Commercial Contributor to control,
    and cooperate with the Commercial Contributor in, the defense and any
    related settlement negotiations. The Indemnified Contributor may
    participate in any such claim at its own expense.

    For example, a Contributor might include the Program in a commercial
    product offering, Product X. That Contributor is then a Commercial
    Contributor. If that Commercial Contributor then makes performance
    claims, or offers warranties related to Product X, those performance
    claims and warranties are such Commercial Contributor's responsibility
    alone. Under this section, the Commercial Contributor would have to
    defend claims against the other Contributors related to those performance
    claims and warranties, and if a court requires any other Contributor to
    pay any damages as a result, the Commercial Contributor must pay
    those damages.

    5. NO WARRANTY

    EXCEPT AS EXPRESSLY SET FORTH IN THIS AGREEMENT, AND TO THE EXTENT
    PERMITTED BY APPLICABLE LAW, THE PROGRAM IS PROVIDED ON AN "AS IS"
    BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, EITHER EXPRESS OR
    IMPLIED INCLUDING, WITHOUT LIMITATION, ANY WARRANTIES OR CONDITIONS OF
    TITLE, NON-INFRINGEMENT, MERCHANTABILITY OR FITNESS FOR A PARTICULAR
    PURPOSE. Each Recipient is solely responsible for determining the
    appropriateness of using and distributing the Program and assumes all
    risks associated with its exercise of rights under this Agreement,
    including but not limited to the risks and costs of program errors,
    compliance with applicable laws, damage to or loss of data, programs
    or equipment, and unavailability or interruption of operations.

    6. DISCLAIMER OF LIABILITY

    EXCEPT AS EXPRESSLY SET FORTH IN THIS AGREEMENT, AND TO THE EXTENT
    PERMITTED BY APPLICABLE LAW, NEITHER RECIPIENT NOR ANY CONTRIBUTORS
    SHALL HAVE ANY LIABILITY FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
    EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING WITHOUT LIMITATION LOST
    PROFITS), HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
    CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
    ARISING IN ANY WAY OUT OF THE USE OR DISTRIBUTION OF THE PROGRAM OR THE
    EXERCISE OF ANY RIGHTS GRANTED HEREUNDER, EVEN IF ADVISED OF THE
    POSSIBILITY OF SUCH DAMAGES.

    7. GENERAL

    If any provision of this Agreement is invalid or unenforceable under
    applicable law, it shall not affect the validity or enforceability of
    the remainder of the terms of this Agreement, and without further
    action by the parties hereto, such provision shall be reformed to the
    minimum extent necessary to make such provision valid and enforceable.

    If Recipient institutes patent litigation against any entity
    (including a cross-claim or counterclaim in a lawsuit) alleging that the
    Program itself (excluding combinations of the Program with other software
    or hardware) infringes such Recipient's patent(s), then such Recipient's
    rights granted under Section 2(b) shall terminate as of the date such
    litigation is filed.

    All Recipient's rights under this Agreement shall terminate if it
    fails to comply with any of the material terms or conditions of this
    Agreement and does not cure such failure in a reasonable period of
    time after becoming aware of such noncompliance. If all Recipient's
    rights under this Agreement terminate, Recipient agrees to cease use
    and distribution of the Program as soon as reasonably practicable.
    However, Recipient's obligations under this Agreement and any licenses
    granted by Recipient relating to the Program shall continue and survive.

    Everyone is permitted to copy and distribute copies of this Agreement,
    but in order to avoid inconsistency the Agreement is copyrighted and
    may only be modified in the following manner. The Agreement Steward
    reserves the right to publish new versions (including revisions) of
    this Agreement from time to time. No one other than the Agreement
    Steward has the right to modify this Agreement. The Eclipse Foundation
    is the initial Agreement Steward. The Eclipse Foundation may assign the
    responsibility to serve as the Agreement Steward to a suitable separate
    entity. Each new version of the Agreement will be given a distinguishing
    version number. The Program (including Contributions) may always be
    Distributed subject to the version of the Agreement under which it was
    received. In addition, after a new version of the Agreement is published,
    Contributor may elect to Distribute the Program (including its
    Contributions) under the new version.

    Except as expressly stated in Sections 2(a) and 2(b) above, Recipient
    receives no rights or licenses to the intellectual property of any
    Contributor under this Agreement, whether expressly, by implication,
    estoppel or otherwise. All rights in the Program not expressly granted
    under this Agreement are reserved. Nothing in this Agreement is intended
    to be enforceable by any entity that is not a Contributor or Recipient.
    No third-party beneficiary rights are created under this Agreement.

    Exhibit A - Form of Secondary Licenses Notice

    "This Source Code may also be made available under the following
    Secondary Licenses when the conditions for such availability set forth
    in the Eclipse Public License, v. 2.0 are satisfied: {name license(s),
    version(s), and exceptions or additional permissions here}."

      Simply including a copy of this Agreement, including this Exhibit A
      is not sufficient to license the Source Code under Secondary Licenses.

      If it is not possible or desirable to put the notice in a particular
      file, then You may include the notice in a location (such as a LICENSE
      file in a relevant directory) where a recipient would be likely to
      look for such a notice.

      You may add additional accurate notices of copyright ownership.

---

##    The GNU General Public License (GPL) Version 2, June 1991

    Copyright (C) 1989, 1991 Free Software Foundation, Inc.
    51 Franklin Street, Fifth Floor
    Boston, MA 02110-1335
    USA

    Everyone is permitted to copy and distribute verbatim copies
    of this license document, but changing it is not allowed.

    Preamble

    The licenses for most software are designed to take away your freedom to
    share and change it. By contrast, the GNU General Public License is
    intended to guarantee your freedom to share and change free software--to
    make sure the software is free for all its users. This General Public
    License applies to most of the Free Software Foundation's software and
    to any other program whose authors commit to using it. (Some other Free
    Software Foundation software is covered by the GNU Library General
    Public License instead.) You can apply it to your programs, too.

    When we speak of free software, we are referring to freedom, not price.
    Our General Public Licenses are designed to make sure that you have the
    freedom to distribute copies of free software (and charge for this
    service if you wish), that you receive source code or can get it if you
    want it, that you can change the software or use pieces of it in new
    free programs; and that you know you can do these things.

    To protect your rights, we need to make restrictions that forbid anyone
    to deny you these rights or to ask you to surrender the rights. These
    restrictions translate to certain responsibilities for you if you
    distribute copies of the software, or if you modify it.

    For example, if you distribute copies of such a program, whether gratis
    or for a fee, you must give the recipients all the rights that you have.
    You must make sure that they, too, receive or can get the source code.
    And you must show them these terms so they know their rights.

    We protect your rights with two steps: (1) copyright the software, and
    (2) offer you this license which gives you legal permission to copy,
    distribute and/or modify the software.

    Also, for each author's protection and ours, we want to make certain
    that everyone understands that there is no warranty for this free
    software. If the software is modified by someone else and passed on, we
    want its recipients to know that what they have is not the original, so
    that any problems introduced by others will not reflect on the original
    authors' reputations.

    Finally, any free program is threatened constantly by software patents.
    We wish to avoid the danger that redistributors of a free program will
    individually obtain patent licenses, in effect making the program
    proprietary. To prevent this, we have made it clear that any patent must
    be licensed for everyone's free use or not licensed at all.

    The precise terms and conditions for copying, distribution and
    modification follow.

    TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION

    0. This License applies to any program or other work which contains a
    notice placed by the copyright holder saying it may be distributed under
    the terms of this General Public License. The "Program", below, refers
    to any such program or work, and a "work based on the Program" means
    either the Program or any derivative work under copyright law: that is
    to say, a work containing the Program or a portion of it, either
    verbatim or with modifications and/or translated into another language.
    (Hereinafter, translation is included without limitation in the term
    "modification".) Each licensee is addressed as "you".

    Activities other than copying, distribution and modification are not
    covered by this License; they are outside its scope. The act of running
    the Program is not restricted, and the output from the Program is
    covered only if its contents constitute a work based on the Program
    (independent of having been made by running the Program). Whether that
    is true depends on what the Program does.

    1. You may copy and distribute verbatim copies of the Program's source
    code as you receive it, in any medium, provided that you conspicuously
    and appropriately publish on each copy an appropriate copyright notice
    and disclaimer of warranty; keep intact all the notices that refer to
    this License and to the absence of any warranty; and give any other
    recipients of the Program a copy of this License along with the Program.

    You may charge a fee for the physical act of transferring a copy, and
    you may at your option offer warranty protection in exchange for a fee.

    2. You may modify your copy or copies of the Program or any portion of
    it, thus forming a work based on the Program, and copy and distribute
    such modifications or work under the terms of Section 1 above, provided
    that you also meet all of these conditions:

        a) You must cause the modified files to carry prominent notices
        stating that you changed the files and the date of any change.

        b) You must cause any work that you distribute or publish, that in
        whole or in part contains or is derived from the Program or any part
        thereof, to be licensed as a whole at no charge to all third parties
        under the terms of this License.

        c) If the modified program normally reads commands interactively
        when run, you must cause it, when started running for such
        interactive use in the most ordinary way, to print or display an
        announcement including an appropriate copyright notice and a notice
        that there is no warranty (or else, saying that you provide a
        warranty) and that users may redistribute the program under these
        conditions, and telling the user how to view a copy of this License.
        (Exception: if the Program itself is interactive but does not
        normally print such an announcement, your work based on the Program
        is not required to print an announcement.)

    These requirements apply to the modified work as a whole. If
    identifiable sections of that work are not derived from the Program, and
    can be reasonably considered independent and separate works in
    themselves, then this License, and its terms, do not apply to those
    sections when you distribute them as separate works. But when you
    distribute the same sections as part of a whole which is a work based on
    the Program, the distribution of the whole must be on the terms of this
    License, whose permissions for other licensees extend to the entire
    whole, and thus to each and every part regardless of who wrote it.

    Thus, it is not the intent of this section to claim rights or contest
    your rights to work written entirely by you; rather, the intent is to
    exercise the right to control the distribution of derivative or
    collective works based on the Program.

    In addition, mere aggregation of another work not based on the Program
    with the Program (or with a work based on the Program) on a volume of a
    storage or distribution medium does not bring the other work under the
    scope of this License.

    3. You may copy and distribute the Program (or a work based on it,
    under Section 2) in object code or executable form under the terms of
    Sections 1 and 2 above provided that you also do one of the following:

        a) Accompany it with the complete corresponding machine-readable
        source code, which must be distributed under the terms of Sections 1
        and 2 above on a medium customarily used for software interchange; or,

        b) Accompany it with a written offer, valid for at least three
        years, to give any third party, for a charge no more than your cost
        of physically performing source distribution, a complete
        machine-readable copy of the corresponding source code, to be
        distributed under the terms of Sections 1 and 2 above on a medium
        customarily used for software interchange; or,

        c) Accompany it with the information you received as to the offer to
        distribute corresponding source code. (This alternative is allowed
        only for noncommercial distribution and only if you received the
        program in object code or executable form with such an offer, in
        accord with Subsection b above.)

    The source code for a work means the preferred form of the work for
    making modifications to it. For an executable work, complete source code
    means all the source code for all modules it contains, plus any
    associated interface definition files, plus the scripts used to control
    compilation and installation of the executable. However, as a special
    exception, the source code distributed need not include anything that is
    normally distributed (in either source or binary form) with the major
    components (compiler, kernel, and so on) of the operating system on
    which the executable runs, unless that component itself accompanies the
    executable.

    If distribution of executable or object code is made by offering access
    to copy from a designated place, then offering equivalent access to copy
    the source code from the same place counts as distribution of the source
    code, even though third parties are not compelled to copy the source
    along with the object code.

    4. You may not copy, modify, sublicense, or distribute the Program
    except as expressly provided under this License. Any attempt otherwise
    to copy, modify, sublicense or distribute the Program is void, and will
    automatically terminate your rights under this License. However, parties
    who have received copies, or rights, from you under this License will
    not have their licenses terminated so long as such parties remain in
    full compliance.

    5. You are not required to accept this License, since you have not
    signed it. However, nothing else grants you permission to modify or
    distribute the Program or its derivative works. These actions are
    prohibited by law if you do not accept this License. Therefore, by
    modifying or distributing the Program (or any work based on the
    Program), you indicate your acceptance of this License to do so, and all
    its terms and conditions for copying, distributing or modifying the
    Program or works based on it.

    6. Each time you redistribute the Program (or any work based on the
    Program), the recipient automatically receives a license from the
    original licensor to copy, distribute or modify the Program subject to
    these terms and conditions. You may not impose any further restrictions
    on the recipients' exercise of the rights granted herein. You are not
    responsible for enforcing compliance by third parties to this License.

    7. If, as a consequence of a court judgment or allegation of patent
    infringement or for any other reason (not limited to patent issues),
    conditions are imposed on you (whether by court order, agreement or
    otherwise) that contradict the conditions of this License, they do not
    excuse you from the conditions of this License. If you cannot distribute
    so as to satisfy simultaneously your obligations under this License and
    any other pertinent obligations, then as a consequence you may not
    distribute the Program at all. For example, if a patent license would
    not permit royalty-free redistribution of the Program by all those who
    receive copies directly or indirectly through you, then the only way you
    could satisfy both it and this License would be to refrain entirely from
    distribution of the Program.

    If any portion of this section is held invalid or unenforceable under
    any particular circumstance, the balance of the section is intended to
    apply and the section as a whole is intended to apply in other
    circumstances.

    It is not the purpose of this section to induce you to infringe any
    patents or other property right claims or to contest validity of any
    such claims; this section has the sole purpose of protecting the
    integrity of the free software distribution system, which is implemented
    by public license practices. Many people have made generous
    contributions to the wide range of software distributed through that
    system in reliance on consistent application of that system; it is up to
    the author/donor to decide if he or she is willing to distribute
    software through any other system and a licensee cannot impose that choice.

    This section is intended to make thoroughly clear what is believed to be
    a consequence of the rest of this License.

    8. If the distribution and/or use of the Program is restricted in
    certain countries either by patents or by copyrighted interfaces, the
    original copyright holder who places the Program under this License may
    add an explicit geographical distribution limitation excluding those
    countries, so that distribution is permitted only in or among countries
    not thus excluded. In such case, this License incorporates the
    limitation as if written in the body of this License.

    9. The Free Software Foundation may publish revised and/or new
    versions of the General Public License from time to time. Such new
    versions will be similar in spirit to the present version, but may
    differ in detail to address new problems or concerns.

    Each version is given a distinguishing version number. If the Program
    specifies a version number of this License which applies to it and "any
    later version", you have the option of following the terms and
    conditions either of that version or of any later version published by
    the Free Software Foundation. If the Program does not specify a version
    number of this License, you may choose any version ever published by the
    Free Software Foundation.

    10. If you wish to incorporate parts of the Program into other free
    programs whose distribution conditions are different, write to the
    author to ask for permission. For software which is copyrighted by the
    Free Software Foundation, write to the Free Software Foundation; we
    sometimes make exceptions for this. Our decision will be guided by the
    two goals of preserving the free status of all derivatives of our free
    software and of promoting the sharing and reuse of software generally.

    NO WARRANTY

    11. BECAUSE THE PROGRAM IS LICENSED FREE OF CHARGE, THERE IS NO
    WARRANTY FOR THE PROGRAM, TO THE EXTENT PERMITTED BY APPLICABLE LAW.
    EXCEPT WHEN OTHERWISE STATED IN WRITING THE COPYRIGHT HOLDERS AND/OR
    OTHER PARTIES PROVIDE THE PROGRAM "AS IS" WITHOUT WARRANTY OF ANY KIND,
    EITHER EXPRESSED OR IMPLIED, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
    WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE. THE
    ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE PROGRAM IS WITH
    YOU. SHOULD THE PROGRAM PROVE DEFECTIVE, YOU ASSUME THE COST OF ALL
    NECESSARY SERVICING, REPAIR OR CORRECTION.

    12. IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN
    WRITING WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MAY MODIFY
    AND/OR REDISTRIBUTE THE PROGRAM AS PERMITTED ABOVE, BE LIABLE TO YOU FOR
    DAMAGES, INCLUDING ANY GENERAL, SPECIAL, INCIDENTAL OR CONSEQUENTIAL
    DAMAGES ARISING OUT OF THE USE OR INABILITY TO USE THE PROGRAM
    (INCLUDING BUT NOT LIMITED TO LOSS OF DATA OR DATA BEING RENDERED
    INACCURATE OR LOSSES SUSTAINED BY YOU OR THIRD PARTIES OR A FAILURE OF
    THE PROGRAM TO OPERATE WITH ANY OTHER PROGRAMS), EVEN IF SUCH HOLDER OR
    OTHER PARTY HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.

    END OF TERMS AND CONDITIONS

    How to Apply These Terms to Your New Programs

    If you develop a new program, and you want it to be of the greatest
    possible use to the public, the best way to achieve this is to make it
    free software which everyone can redistribute and change under these terms.

    To do so, attach the following notices to the program. It is safest to
    attach them to the start of each source file to most effectively convey
    the exclusion of warranty; and each file should have at least the
    "copyright" line and a pointer to where the full notice is found.

        One line to give the program's name and a brief idea of what it does.
        Copyright (C) <year> <name of author>

        This program is free software; you can redistribute it and/or modify
        it under the terms of the GNU General Public License as published by
        the Free Software Foundation; either version 2 of the License, or
        (at your option) any later version.

        This program is distributed in the hope that it will be useful, but
        WITHOUT ANY WARRANTY; without even the implied warranty of
        MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
        General Public License for more details.

        You should have received a copy of the GNU General Public License
        along with this program; if not, write to the Free Software
        Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1335 USA

    Also add information on how to contact you by electronic and paper mail.

    If the program is interactive, make it output a short notice like this
    when it starts in an interactive mode:

        Gnomovision version 69, Copyright (C) year name of author
        Gnomovision comes with ABSOLUTELY NO WARRANTY; for details type
        `show w'. This is free software, and you are welcome to redistribute
        it under certain conditions; type `show c' for details.

    The hypothetical commands `show w' and `show c' should show the
    appropriate parts of the General Public License. Of course, the commands
    you use may be called something other than `show w' and `show c'; they
    could even be mouse-clicks or menu items--whatever suits your program.

    You should also get your employer (if you work as a programmer) or your
    school, if any, to sign a "copyright disclaimer" for the program, if
    necessary. Here is a sample; alter the names:

        Yoyodyne, Inc., hereby disclaims all copyright interest in the
        program `Gnomovision' (which makes passes at compilers) written by
        James Hacker.

        signature of Ty Coon, 1 April 1989
        Ty Coon, President of Vice

    This General Public License does not permit incorporating your program
    into proprietary programs. If your program is a subroutine library, you
    may consider it more useful to permit linking proprietary applications
    with the library. If this is what you want to do, use the GNU Library
    General Public License instead of this License.

---

## CLASSPATH EXCEPTION

    Linking this library statically or dynamically with other modules is
    making a combined work based on this library.  Thus, the terms and
    conditions of the GNU General Public License version 2 cover the whole
    combination.

    As a special exception, the copyright holders of this library give you
    permission to link this library with independent modules to produce an
    executable, regardless of the license terms of these independent
    modules, and to copy and distribute the resulting executable under
    terms of your choice, provided that you also meet, for each linked
    independent module, the terms and conditions of the license of that
    module.  An independent module is a module which is not derived from or
    based on this library.  If you modify this library, you may extend this
    exception to your version of the library, but you are not obligated to
    do so.  If you do not wish to do so, delete this exception statement
    from your version.
````

### Aviso 31

- `jakarta.annotation-api-2.1.1.jar` — META-INF/NOTICE.md

````text
# Notices for Jakarta Annotations

This content is produced and maintained by the Jakarta Annotations project.

 * Project home: https://projects.eclipse.org/projects/ee4j.ca

## Trademarks

Jakarta Annotations is a trademark of the Eclipse Foundation.

## Declared Project Licenses

This program and the accompanying materials are made available under the terms
of the Eclipse Public License v. 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0. This Source Code may also be made
available under the following Secondary Licenses when the conditions for such
availability set forth in the Eclipse Public License v. 2.0 are satisfied: GNU
General Public License, version 2 with the GNU Classpath Exception which is
available at https://www.gnu.org/software/classpath/license.html.

SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0

## Source Code

The project maintains the following source code repositories:

 * https://github.com/eclipse-ee4j/common-annotations-api

## Third-party Content

## Cryptography

Content may contain encryption software. The country in which you are currently
may have restrictions on the import, possession, and use, and/or re-export to
another country, of encryption software. BEFORE using any encryption software,
please check the country's laws, regulations and policies concerning the import,
possession, or use, and re-export of encryption software, to see if this is
permitted.
````

### Aviso 32

- `jakarta.inject-api-2.0.1.jar` — META-INF/NOTICE.md

````text
# Notices for Eclipse Jakarta Dependency Injection

This content is produced and maintained by the Eclipse Jakarta Dependency Injection project.

* Project home: https://projects.eclipse.org/projects/cdi.batch

## Trademarks

Jakarta Dependency Injection is a trademark of the Eclipse Foundation.

## Copyright

All content is the property of the respective authors or their employers. For
more information regarding authorship of content, please consult the listed
source code repository logs.

## Declared Project Licenses

This program and the accompanying materials are made available under the terms
of the Apache License, Version 2.0 which is available at
https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: Apache-2.0

## Source Code

The project maintains the following source code repositories:

https://github.com/eclipse-ee4j/injection-api
https://github.com/eclipse-ee4j/injection-spec
https://github.com/eclipse-ee4j/injection-tck

## Third-party Content

This project leverages the following third party content.

None

## Cryptography

None
````

### Aviso 33

- `jakarta.persistence-api-3.1.0.jar` — META-INF/LICENSE.md

````text
This program and the accompanying materials are made available under the
terms of the Eclipse Public License v. 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0,
or the Eclipse Distribution License v. 1.0 which is available at
http://www.eclipse.org/org/documents/edl-v10.php.

SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause


Eclipse Public License - v 2.0

    THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
    PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
    OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.

1. DEFINITIONS

"Contribution" means:

  a) in the case of the initial Contributor, the initial content
     Distributed under this Agreement, and

  b) in the case of each subsequent Contributor:
     i) changes to the Program, and
     ii) additions to the Program;
  where such changes and/or additions to the Program originate from
  and are Distributed by that particular Contributor. A Contribution
  "originates" from a Contributor if it was added to the Program by
  such Contributor itself or anyone acting on such Contributor's behalf.
  Contributions do not include changes or additions to the Program that
  are not Modified Works.

"Contributor" means any person or entity that Distributes the Program.

"Licensed Patents" mean patent claims licensable by a Contributor which
are necessarily infringed by the use or sale of its Contribution alone
or when combined with the Program.

"Program" means the Contributions Distributed in accordance with this
Agreement.

"Recipient" means anyone who receives the Program under this Agreement
or any Secondary License (as applicable), including Contributors.

"Derivative Works" shall mean any work, whether in Source Code or other
form, that is based on (or derived from) the Program and for which the
editorial revisions, annotations, elaborations, or other modifications
represent, as a whole, an original work of authorship.

"Modified Works" shall mean any work in Source Code or other form that
results from an addition to, deletion from, or modification of the
contents of the Program, including, for purposes of clarity any new file
in Source Code form that contains any contents of the Program. Modified
Works shall not include works that contain only declarations,
interfaces, types, classes, structures, or files of the Program solely
in each case in order to link to, bind by name, or subclass the Program
or Modified Works thereof.

"Distribute" means the acts of a) distributing or b) making available
in any manner that enables the transfer of a copy.

"Source Code" means the form of a Program preferred for making
modifications, including but not limited to software source code,
documentation source, and configuration files.

"Secondary License" means either the GNU General Public License,
Version 2.0, or any later versions of that license, including any
exceptions or additional permissions as identified by the initial
Contributor.

2. GRANT OF RIGHTS

  a) Subject to the terms of this Agreement, each Contributor hereby
  grants Recipient a non-exclusive, worldwide, royalty-free copyright
  license to reproduce, prepare Derivative Works of, publicly display,
  publicly perform, Distribute and sublicense the Contribution of such
  Contributor, if any, and such Derivative Works.

  b) Subject to the terms of this Agreement, each Contributor hereby
  grants Recipient a non-exclusive, worldwide, royalty-free patent
  license under Licensed Patents to make, use, sell, offer to sell,
  import and otherwise transfer the Contribution of such Contributor,
  if any, in Source Code or other form. This patent license shall
  apply to the combination of the Contribution and the Program if, at
  the time the Contribution is added by the Contributor, such addition
  of the Contribution causes such combination to be covered by the
  Licensed Patents. The patent license shall not apply to any other
  combinations which include the Contribution. No hardware per se is
  licensed hereunder.

  c) Recipient understands that although each Contributor grants the
  licenses to its Contributions set forth herein, no assurances are
  provided by any Contributor that the Program does not infringe the
  patent or other intellectual property rights of any other entity.
  Each Contributor disclaims any liability to Recipient for claims
  brought by any other entity based on infringement of intellectual
  property rights or otherwise. As a condition to exercising the
  rights and licenses granted hereunder, each Recipient hereby
  assumes sole responsibility to secure any other intellectual
  property rights needed, if any. For example, if a third party
  patent license is required to allow Recipient to Distribute the
  Program, it is Recipient's responsibility to acquire that license
  before distributing the Program.

  d) Each Contributor represents that to its knowledge it has
  sufficient copyright rights in its Contribution, if any, to grant
  the copyright license set forth in this Agreement.

  e) Notwithstanding the terms of any Secondary License, no
  Contributor makes additional grants to any Recipient (other than
  those set forth in this Agreement) as a result of such Recipient's
  receipt of the Program under the terms of a Secondary License
  (if permitted under the terms of Section 3).

3. REQUIREMENTS

3.1 If a Contributor Distributes the Program in any form, then:

  a) the Program must also be made available as Source Code, in
  accordance with section 3.2, and the Contributor must accompany
  the Program with a statement that the Source Code for the Program
  is available under this Agreement, and informs Recipients how to
  obtain it in a reasonable manner on or through a medium customarily
  used for software exchange; and

  b) the Contributor may Distribute the Program under a license
  different than this Agreement, provided that such license:
     i) effectively disclaims on behalf of all other Contributors all
     warranties and conditions, express and implied, including
     warranties or conditions of title and non-infringement, and
     implied warranties or conditions of merchantability and fitness
     for a particular purpose;

     ii) effectively excludes on behalf of all other Contributors all
     liability for damages, including direct, indirect, special,
     incidental and consequential damages, such as lost profits;

     iii) does not attempt to limit or alter the recipients' rights
     in the Source Code under section 3.2; and

     iv) requires any subsequent distribution of the Program by any
     party to be under a license that satisfies the requirements
     of this section 3.

3.2 When the Program is Distributed as Source Code:

  a) it must be made available under this Agreement, or if the
  Program (i) is combined with other material in a separate file or
  files made available under a Secondary License, and (ii) the initial
  Contributor attached to the Source Code the notice described in
  Exhibit A of this Agreement, then the Program may be made available
  under the terms of such Secondary Licenses, and

  b) a copy of this Agreement must be included with each copy of
  the Program.

3.3 Contributors may not remove or alter any copyright, patent,
trademark, attribution notices, disclaimers of warranty, or limitations
of liability ("notices") contained within the Program from any copy of
the Program which they Distribute, provided that Contributors may add
their own appropriate notices.

4. COMMERCIAL DISTRIBUTION

Commercial distributors of software may accept certain responsibilities
with respect to end users, business partners and the like. While this
license is intended to facilitate the commercial use of the Program,
the Contributor who includes the Program in a commercial product
offering should do so in a manner which does not create potential
liability for other Contributors. Therefore, if a Contributor includes
the Program in a commercial product offering, such Contributor
("Commercial Contributor") hereby agrees to defend and indemnify every
other Contributor ("Indemnified Contributor") against any losses,
damages and costs (collectively "Losses") arising from claims, lawsuits
and other legal actions brought by a third party against the Indemnified
Contributor to the extent caused by the acts or omissions of such
Commercial Contributor in connection with its distribution of the Program
in a commercial product offering. The obligations in this section do not
apply to any claims or Losses relating to any actual or alleged
intellectual property infringement. In order to qualify, an Indemnified
Contributor must: a) promptly notify the Commercial Contributor in
writing of such claim, and b) allow the Commercial Contributor to control,
and cooperate with the Commercial Contributor in, the defense and any
related settlement negotiations. The Indemnified Contributor may
participate in any such claim at its own expense.

For example, a Contributor might include the Program in a commercial
product offering, Product X. That Contributor is then a Commercial
Contributor. If that Commercial Contributor then makes performance
claims, or offers warranties related to Product X, those performance
claims and warranties are such Commercial Contributor's responsibility
alone. Under this section, the Commercial Contributor would have to
defend claims against the other Contributors related to those performance
claims and warranties, and if a court requires any other Contributor to
pay any damages as a result, the Commercial Contributor must pay
those damages.

5. NO WARRANTY

EXCEPT AS EXPRESSLY SET FORTH IN THIS AGREEMENT, AND TO THE EXTENT
PERMITTED BY APPLICABLE LAW, THE PROGRAM IS PROVIDED ON AN "AS IS"
BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, EITHER EXPRESS OR
IMPLIED INCLUDING, WITHOUT LIMITATION, ANY WARRANTIES OR CONDITIONS OF
TITLE, NON-INFRINGEMENT, MERCHANTABILITY OR FITNESS FOR A PARTICULAR
PURPOSE. Each Recipient is solely responsible for determining the
appropriateness of using and distributing the Program and assumes all
risks associated with its exercise of rights under this Agreement,
including but not limited to the risks and costs of program errors,
compliance with applicable laws, damage to or loss of data, programs
or equipment, and unavailability or interruption of operations.

6. DISCLAIMER OF LIABILITY

EXCEPT AS EXPRESSLY SET FORTH IN THIS AGREEMENT, AND TO THE EXTENT
PERMITTED BY APPLICABLE LAW, NEITHER RECIPIENT NOR ANY CONTRIBUTORS
SHALL HAVE ANY LIABILITY FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING WITHOUT LIMITATION LOST
PROFITS), HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OR DISTRIBUTION OF THE PROGRAM OR THE
EXERCISE OF ANY RIGHTS GRANTED HEREUNDER, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGES.

7. GENERAL

If any provision of this Agreement is invalid or unenforceable under
applicable law, it shall not affect the validity or enforceability of
the remainder of the terms of this Agreement, and without further
action by the parties hereto, such provision shall be reformed to the
minimum extent necessary to make such provision valid and enforceable.

If Recipient institutes patent litigation against any entity
(including a cross-claim or counterclaim in a lawsuit) alleging that the
Program itself (excluding combinations of the Program with other software
or hardware) infringes such Recipient's patent(s), then such Recipient's
rights granted under Section 2(b) shall terminate as of the date such
litigation is filed.

All Recipient's rights under this Agreement shall terminate if it
fails to comply with any of the material terms or conditions of this
Agreement and does not cure such failure in a reasonable period of
time after becoming aware of such noncompliance. If all Recipient's
rights under this Agreement terminate, Recipient agrees to cease use
and distribution of the Program as soon as reasonably practicable.
However, Recipient's obligations under this Agreement and any licenses
granted by Recipient relating to the Program shall continue and survive.

Everyone is permitted to copy and distribute copies of this Agreement,
but in order to avoid inconsistency the Agreement is copyrighted and
may only be modified in the following manner. The Agreement Steward
reserves the right to publish new versions (including revisions) of
this Agreement from time to time. No one other than the Agreement
Steward has the right to modify this Agreement. The Eclipse Foundation
is the initial Agreement Steward. The Eclipse Foundation may assign the
responsibility to serve as the Agreement Steward to a suitable separate
entity. Each new version of the Agreement will be given a distinguishing
version number. The Program (including Contributions) may always be
Distributed subject to the version of the Agreement under which it was
received. In addition, after a new version of the Agreement is published,
Contributor may elect to Distribute the Program (including its
Contributions) under the new version.

Except as expressly stated in Sections 2(a) and 2(b) above, Recipient
receives no rights or licenses to the intellectual property of any
Contributor under this Agreement, whether expressly, by implication,
estoppel or otherwise. All rights in the Program not expressly granted
under this Agreement are reserved. Nothing in this Agreement is intended
to be enforceable by any entity that is not a Contributor or Recipient.
No third-party beneficiary rights are created under this Agreement.

Exhibit A - Form of Secondary Licenses Notice

"This Source Code may also be made available under the following
Secondary Licenses when the conditions for such availability set forth
in the Eclipse Public License, v. 2.0 are satisfied: {name license(s),
version(s), and exceptions or additional permissions here}."

  Simply including a copy of this Agreement, including this Exhibit A
  is not sufficient to license the Source Code under Secondary Licenses.

  If it is not possible or desirable to put the notice in a particular
  file, then You may include the notice in a location (such as a LICENSE
  file in a relevant directory) where a recipient would be likely to
  look for such a notice.

  You may add additional accurate notices of copyright ownership.



Eclipse Distribution License - v 1.0
Copyright (c) 2007, Eclipse Foundation, Inc. and its licensors.

All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice,
this list of conditions and the following disclaimer.
* Redistributions in binary form must reproduce the above copyright notice,
this list of conditions and the following disclaimer in the documentation
and/or other materials provided with the distribution.
* Neither the name of the Eclipse Foundation, Inc. nor the names of its contributors
may be used to endorse or promote products derived from this software
without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY,
OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
````

### Aviso 34

- `jakarta.persistence-api-3.1.0.jar` — META-INF/NOTICE.md

````text
[//]: # " Copyright (c) 2019 Oracle and/or its affiliates. All rights reserved. "
[//]: # "  "
[//]: # " This program and the accompanying materials are made available under the "
[//]: # " terms of the Eclipse Distribution License v. 1.0, which is available at "
[//]: # " http://www.eclipse.org/org/documents/edl-v10.php. "
[//]: # "  "
[//]: # " SPDX-License-Identifier: BSD-3-Clause "

# Notices for Jakarta Persistence

This content is produced and maintained by the Jakarta Persistence
project.

* Project home: https://projects.eclipse.org/projects/ee4j.jpa

## Trademarks

 Jakarta Persistence is a trademark of the Eclipse Foundation.

## Copyright

All content is the property of the respective authors or their employers. For
more information regarding authorship of content, please consult the listed
source code repository logs.

## Declared Project Licenses

This program and the accompanying materials are made available under the terms
of the Eclipse Public License v. 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License v. 1.0
which is available at http://www.eclipse.org/org/documents/edl-v10.php.

SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

## Source Code

The project maintains the following source code repositories:

* https://github.com/eclipse-ee4j/jpa-api

## Third-party Content

This project leverages the following third party content.

None

## Cryptography

Content may contain encryption software. The country in which you are currently
may have restrictions on the import, possession, and use, and/or re-export to
another country, of encryption software. BEFORE using any encryption software,
please check the country's laws, regulations and policies concerning the import,
possession, or use, and re-export of encryption software, to see if this is
permitted.
````

### Aviso 35

- `jakarta.transaction-api-2.0.1.jar` — META-INF/NOTICE.md

````text
# Notices for Jakarta Transactions

This content is produced and maintained by the Jakarta Transactions project.

* Project home: https://projects.eclipse.org/projects/ee4j.jta

## Trademarks

Jakarta Transactions is a trademark of the Eclipse Foundation.

## Copyright

All content is the property of the respective authors or their employers. For
more information regarding authorship of content, please consult the listed
source code repository logs.

## Declared Project Licenses

This program and the accompanying materials are made available under the terms
of the Eclipse Public License v. 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0. This Source Code may also be made
available under the following Secondary Licenses when the conditions for such
availability set forth in the Eclipse Public License v. 2.0 are satisfied: GNU
General Public License, version 2 with the GNU Classpath Exception which is
available at https://www.gnu.org/software/classpath/license.html.

SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0

## Source Code

The project maintains the following source code repositories:

* https://github.com/eclipse-ee4j/jta-api

## Third-party Content

This project leverages the following third party content.

None

## Cryptography

Content may contain encryption software. The country in which you are currently
may have restrictions on the import, possession, and use, and/or re-export to
another country, of encryption software. BEFORE using any encryption software,
please check the country's laws, regulations and policies concerning the import,
possession, or use, and re-export of encryption software, to see if this is
permitted.
````

### Aviso 36

- `jakarta.xml.bind-api-4.0.4.jar` — META-INF/LICENSE.md

````text
Copyright (c) 2017, 2018 Oracle and/or its affiliates. All rights reserved.

    Redistribution and use in source and binary forms, with or without
    modification, are permitted provided that the following conditions
    are met:

      - Redistributions of source code must retain the above copyright
        notice, this list of conditions and the following disclaimer.

      - Redistributions in binary form must reproduce the above copyright
        notice, this list of conditions and the following disclaimer in the
        documentation and/or other materials provided with the distribution.

      - Neither the name of the Eclipse Foundation, Inc. nor the names of its
        contributors may be used to endorse or promote products derived
        from this software without specific prior written permission.

    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
    IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
    THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
    PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
    CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
    EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
    PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
    PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
    LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
    NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
    SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
````

### Aviso 37

- `jakarta.xml.bind-api-4.0.4.jar` — META-INF/NOTICE.md

````text
[//]: # " Copyright (c) 2018, 2024 Oracle and/or its affiliates. All rights reserved. "
[//]: # "  "
[//]: # " This program and the accompanying materials are made available under the "
[//]: # " terms of the Eclipse Distribution License v. 1.0, which is available at "
[//]: # " http://www.eclipse.org/org/documents/edl-v10.php. "
[//]: # "  "
[//]: # " SPDX-License-Identifier: BSD-3-Clause "

# Notices for Jakarta XML Binding

This content is produced and maintained by the Jakarta XML Binding project.

* Project home: https://projects.eclipse.org/projects/ee4j.jaxb

## Trademarks

Jakarta XML Binding™ is a trademark of the Eclipse Foundation.

## Copyright

All content is the property of the respective authors or their employers. For
more information regarding authorship of content, please consult the listed
source code repository logs.

## Declared Project Licenses

This program and the accompanying materials are made available under the terms
of the Eclipse Distribution License v1.0 which is available at
https://www.eclipse.org/org/documents/edl-v10.php.

SPDX-License-Identifier: BSD-3-Clause

## Source Code

The project maintains the following source code repositories:

* https://github.com/jakartaee/jaxb-api
* https://github.com/jakartaee/jaxb-tck

## Cryptography

Content may contain encryption software. The country in which you are currently
may have restrictions on the import, possession, and use, and/or re-export to
another country, of encryption software. BEFORE using any encryption software,
please check the country's laws, regulations and policies concerning the import,
possession, or use, and re-export of encryption software, to see if this is
permitted.
````

### Aviso 38

- `byte-buddy-1.15.11.jar` — META-INF/LICENSE

````text
This product bundles ASM 9.7.1, which is available under a "3-clause BSD"
license. For details, see licenses/ASM. For more information visit ${asm.url}.

Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS
````

### Aviso 39

- `byte-buddy-1.15.11.jar` — META-INF/NOTICE

````text
Copyright 2014 - Present Rafael Winterhalter

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
````

### Aviso 40

- `byte-buddy-1.15.11.jar` — META-INF/licenses/ASM
- `aspectjweaver-1.9.25.1.jar` — licenses/LICENSE-BSD-3-CLAUSE.txt

````text
ASM: a very small and fast Java bytecode manipulation framework
Copyright (c) 2000-2011 INRIA, France Telecom
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:
1. Redistributions of source code must retain the above copyright
  notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright
  notice, this list of conditions and the following disclaimer in the
  documentation and/or other materials provided with the distribution.
3. Neither the name of the copyright holders nor the names of its
  contributors may be used to endorse or promote products derived from
  this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
THE POSSIBILITY OF SUCH DAMAGE.
````

### Aviso 41

- `antlr4-runtime-4.13.0.jar` — https://raw.githubusercontent.com/antlr/antlr4/4.13.0/LICENSE.txt

````text
Copyright (c) 2012-2022 The ANTLR Project. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

1. Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.

3. Neither name of copyright holders nor the names of its contributors
may be used to endorse or promote products derived from this software
without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE REGENTS OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
````

### Aviso 42

- `commons-lang3-3.17.0.jar` — META-INF/NOTICE.txt

````text
Apache Commons Lang
Copyright 2001-2024 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
````

### Aviso 43

- `log4j-api-2.24.3.jar` — META-INF/NOTICE

````text
Apache Log4j API
Copyright 1999-2024 The Apache Software Foundation


This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
````

### Aviso 44

- `log4j-to-slf4j-2.24.3.jar` — META-INF/NOTICE

````text
Log4j API to SLF4J Adapter
Copyright 1999-2024 The Apache Software Foundation


This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
````

### Aviso 45

- `tomcat-embed-core-10.1.50.jar` — META-INF/LICENSE

````text
Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.



APACHE TOMCAT SUBCOMPONENTS:

Apache Tomcat includes a number of subcomponents with separate copyright notices
and license terms. Your use of these subcomponents is subject to the terms and
conditions of the following licenses.


For the following XML Schemas for Java EE Deployment Descriptors:
 - javaee_5.xsd
 - javaee_web_services_1_2.xsd
 - javaee_web_services_client_1_2.xsd
 - javaee_6.xsd
 - javaee_web_services_1_3.xsd
 - javaee_web_services_client_1_3.xsd
 - jsp_2_2.xsd
 - web-app_3_0.xsd
 - web-common_3_0.xsd
 - web-fragment_3_0.xsd
 - javaee_7.xsd
 - javaee_web_services_1_4.xsd
 - javaee_web_services_client_1_4.xsd
 - jsp_2_3.xsd
 - web-app_3_1.xsd
 - web-common_3_1.xsd
 - web-fragment_3_1.xsd
 - javaee_8.xsd
 - web-app_4_0.xsd
 - web-common_4_0.xsd
 - web-fragment_4_0.xsd

COMMON DEVELOPMENT AND DISTRIBUTION LICENSE (CDDL) Version 1.0

1. Definitions.

   1.1. Contributor. means each individual or entity that creates or contributes
        to the creation of Modifications.

   1.2. Contributor Version. means the combination of the Original Software,
        prior Modifications used by a Contributor (if any), and the
        Modifications made by that particular Contributor.

   1.3. Covered Software. means (a) the Original Software, or (b) Modifications,
        or (c) the combination of files containing Original Software with files
        containing Modifications, in each case including portions thereof.

   1.4. Executable. means the Covered Software in any form other than Source
        Code.

   1.5. Initial Developer. means the individual or entity that first makes
        Original Software available under this License.

   1.6. Larger Work. means a work which combines Covered Software or portions
        thereof with code not governed by the terms of this License.

   1.7. License. means this document.

   1.8. Licensable. means having the right to grant, to the maximum extent
        possible, whether at the time of the initial grant or subsequently
        acquired, any and all of the rights conveyed herein.

   1.9. Modifications. means the Source Code and Executable form of any of the
        following:

        A. Any file that results from an addition to, deletion from or
           modification of the contents of a file containing Original Software
           or previous Modifications;

        B. Any new file that contains any part of the Original Software or
           previous Modification; or

        C. Any new file that is contributed or otherwise made available under
           the terms of this License.

   1.10. Original Software. means the Source Code and Executable form of
         computer software code that is originally released under this License.

   1.11. Patent Claims. means any patent claim(s), now owned or hereafter
         acquired, including without limitation, method, process, and apparatus
         claims, in any patent Licensable by grantor.

   1.12. Source Code. means (a) the common form of computer software code in
         which modifications are made and (b) associated documentation included
         in or with such code.

   1.13. You. (or .Your.) means an individual or a legal entity exercising
         rights under, and complying with all of the terms of, this License. For
         legal entities, .You. includes any entity which controls, is controlled
         by, or is under common control with You. For purposes of this
         definition, .control. means (a) the power, direct or indirect, to cause
         the direction or management of such entity, whether by contract or
         otherwise, or (b) ownership of more than fifty percent (50%) of the
         outstanding shares or beneficial ownership of such entity.

2. License Grants.

      2.1. The Initial Developer Grant.

      Conditioned upon Your compliance with Section 3.1 below and subject to
      third party intellectual property claims, the Initial Developer hereby
      grants You a world-wide, royalty-free, non-exclusive license:

        (a) under intellectual property rights (other than patent or trademark)
            Licensable by Initial Developer, to use, reproduce, modify, display,
            perform, sublicense and distribute the Original Software (or
            portions thereof), with or without Modifications, and/or as part of
            a Larger Work; and

        (b) under Patent Claims infringed by the making, using or selling of
            Original Software, to make, have made, use, practice, sell, and
            offer for sale, and/or otherwise dispose of the Original Software
            (or portions thereof).

        (c) The licenses granted in Sections 2.1(a) and (b) are effective on the
            date Initial Developer first distributes or otherwise makes the
            Original Software available to a third party under the terms of this
            License.

        (d) Notwithstanding Section 2.1(b) above, no patent license is granted:
            (1) for code that You delete from the Original Software, or (2) for
            infringements caused by: (i) the modification of the Original
            Software, or (ii) the combination of the Original Software with
            other software or devices.

    2.2. Contributor Grant.

    Conditioned upon Your compliance with Section 3.1 below and subject to third
    party intellectual property claims, each Contributor hereby grants You a
    world-wide, royalty-free, non-exclusive license:

        (a) under intellectual property rights (other than patent or trademark)
            Licensable by Contributor to use, reproduce, modify, display,
            perform, sublicense and distribute the Modifications created by such
            Contributor (or portions thereof), either on an unmodified basis,
            with other Modifications, as Covered Software and/or as part of a
            Larger Work; and

        (b) under Patent Claims infringed by the making, using, or selling of
            Modifications made by that Contributor either alone and/or in
            combination with its Contributor Version (or portions of such
            combination), to make, use, sell, offer for sale, have made, and/or
            otherwise dispose of: (1) Modifications made by that Contributor (or
            portions thereof); and (2) the combination of Modifications made by
            that Contributor with its Contributor Version (or portions of such
            combination).

        (c) The licenses granted in Sections 2.2(a) and 2.2(b) are effective on
            the date Contributor first distributes or otherwise makes the
            Modifications available to a third party.

        (d) Notwithstanding Section 2.2(b) above, no patent license is granted:
            (1) for any code that Contributor has deleted from the Contributor
            Version; (2) for infringements caused by: (i) third party
            modifications of Contributor Version, or (ii) the combination of
            Modifications made by that Contributor with other software (except
            as part of the Contributor Version) or other devices; or (3) under
            Patent Claims infringed by Covered Software in the absence of
            Modifications made by that Contributor.

3. Distribution Obligations.

      3.1. Availability of Source Code.
      Any Covered Software that You distribute or otherwise make available in
      Executable form must also be made available in Source Code form and that
      Source Code form must be distributed only under the terms of this License.
      You must include a copy of this License with every copy of the Source Code
      form of the Covered Software You distribute or otherwise make available.
      You must inform recipients of any such Covered Software in Executable form
      as to how they can obtain such Covered Software in Source Code form in a
      reasonable manner on or through a medium customarily used for software
      exchange.

      3.2. Modifications.
      The Modifications that You create or to which You contribute are governed
      by the terms of this License. You represent that You believe Your
      Modifications are Your original creation(s) and/or You have sufficient
      rights to grant the rights conveyed by this License.

      3.3. Required Notices.
      You must include a notice in each of Your Modifications that identifies
      You as the Contributor of the Modification. You may not remove or alter
      any copyright, patent or trademark notices contained within the Covered
      Software, or any notices of licensing or any descriptive text giving
      attribution to any Contributor or the Initial Developer.

      3.4. Application of Additional Terms.
      You may not offer or impose any terms on any Covered Software in Source
      Code form that alters or restricts the applicable version of this License
      or the recipients. rights hereunder. You may choose to offer, and to
      charge a fee for, warranty, support, indemnity or liability obligations to
      one or more recipients of Covered Software. However, you may do so only on
      Your own behalf, and not on behalf of the Initial Developer or any
      Contributor. You must make it absolutely clear that any such warranty,
      support, indemnity or liability obligation is offered by You alone, and
      You hereby agree to indemnify the Initial Developer and every Contributor
      for any liability incurred by the Initial Developer or such Contributor as
      a result of warranty, support, indemnity or liability terms You offer.

      3.5. Distribution of Executable Versions.
      You may distribute the Executable form of the Covered Software under the
      terms of this License or under the terms of a license of Your choice,
      which may contain terms different from this License, provided that You are
      in compliance with the terms of this License and that the license for the
      Executable form does not attempt to limit or alter the recipient.s rights
      in the Source Code form from the rights set forth in this License. If You
      distribute the Covered Software in Executable form under a different
      license, You must make it absolutely clear that any terms which differ
      from this License are offered by You alone, not by the Initial Developer
      or Contributor. You hereby agree to indemnify the Initial Developer and
      every Contributor for any liability incurred by the Initial Developer or
      such Contributor as a result of any such terms You offer.

      3.6. Larger Works.
      You may create a Larger Work by combining Covered Software with other code
      not governed by the terms of this License and distribute the Larger Work
      as a single product. In such a case, You must make sure the requirements
      of this License are fulfilled for the Covered Software.

4. Versions of the License.

      4.1. New Versions.
      Sun Microsystems, Inc. is the initial license steward and may publish
      revised and/or new versions of this License from time to time. Each
      version will be given a distinguishing version number. Except as provided
      in Section 4.3, no one other than the license steward has the right to
      modify this License.

      4.2. Effect of New Versions.
      You may always continue to use, distribute or otherwise make the Covered
      Software available under the terms of the version of the License under
      which You originally received the Covered Software. If the Initial
      Developer includes a notice in the Original Software prohibiting it from
      being distributed or otherwise made available under any subsequent version
      of the License, You must distribute and make the Covered Software
      available under the terms of the version of the License under which You
      originally received the Covered Software. Otherwise, You may also choose
      to use, distribute or otherwise make the Covered Software available under
      the terms of any subsequent version of the License published by the
      license steward.

      4.3. Modified Versions.
      When You are an Initial Developer and You want to create a new license for
      Your Original Software, You may create and use a modified version of this
      License if You: (a) rename the license and remove any references to the
      name of the license steward (except to note that the license differs from
      this License); and (b) otherwise make it clear that the license contains
      terms which differ from this License.

5. DISCLAIMER OF WARRANTY.

   COVERED SOFTWARE IS PROVIDED UNDER THIS LICENSE ON AN .AS IS. BASIS, WITHOUT
   WARRANTY OF ANY KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, WITHOUT
   LIMITATION, WARRANTIES THAT THE COVERED SOFTWARE IS FREE OF DEFECTS,
   MERCHANTABLE, FIT FOR A PARTICULAR PURPOSE OR NON-INFRINGING. THE ENTIRE RISK
   AS TO THE QUALITY AND PERFORMANCE OF THE COVERED SOFTWARE IS WITH YOU. SHOULD
   ANY COVERED SOFTWARE PROVE DEFECTIVE IN ANY RESPECT, YOU (NOT THE INITIAL
   DEVELOPER OR ANY OTHER CONTRIBUTOR) ASSUME THE COST OF ANY NECESSARY
   SERVICING, REPAIR OR CORRECTION. THIS DISCLAIMER OF WARRANTY CONSTITUTES AN
   ESSENTIAL PART OF THIS LICENSE. NO USE OF ANY COVERED SOFTWARE IS AUTHORIZED
   HEREUNDER EXCEPT UNDER THIS DISCLAIMER.

6. TERMINATION.

      6.1. This License and the rights granted hereunder will terminate
           automatically if You fail to comply with terms herein and fail to
           cure such breach within 30 days of becoming aware of the breach.
           Provisions which, by their nature, must remain in effect beyond the
           termination of this License shall survive.

      6.2. If You assert a patent infringement claim (excluding declaratory
           judgment actions) against Initial Developer or a Contributor (the
           Initial Developer or Contributor against whom You assert such claim
           is referred to as .Participant.) alleging that the Participant
           Software (meaning the Contributor Version where the Participant is a
           Contributor or the Original Software where the Participant is the
           Initial Developer) directly or indirectly infringes any patent, then
           any and all rights granted directly or indirectly to You by such
           Participant, the Initial Developer (if the Initial Developer is not
           the Participant) and all Contributors under Sections 2.1 and/or 2.2
           of this License shall, upon 60 days notice from Participant terminate
           prospectively and automatically at the expiration of such 60 day
           notice period, unless if within such 60 day period You withdraw Your
           claim with respect to the Participant Software against such
           Participant either unilaterally or pursuant to a written agreement
           with Participant.

      6.3. In the event of termination under Sections 6.1 or 6.2 above, all end
           user licenses that have been validly granted by You or any
           distributor hereunder prior to termination (excluding licenses
           granted to You by any distributor) shall survive termination.

7. LIMITATION OF LIABILITY.

   UNDER NO CIRCUMSTANCES AND UNDER NO LEGAL THEORY, WHETHER TORT (INCLUDING
   NEGLIGENCE), CONTRACT, OR OTHERWISE, SHALL YOU, THE INITIAL DEVELOPER, ANY
   OTHER CONTRIBUTOR, OR ANY DISTRIBUTOR OF COVERED SOFTWARE, OR ANY SUPPLIER OF
   ANY OF SUCH PARTIES, BE LIABLE TO ANY PERSON FOR ANY INDIRECT, SPECIAL,
   INCIDENTAL, OR CONSEQUENTIAL DAMAGES OF ANY CHARACTER INCLUDING, WITHOUT
   LIMITATION, DAMAGES FOR LOST PROFITS, LOSS OF GOODWILL, WORK STOPPAGE,
   COMPUTER FAILURE OR MALFUNCTION, OR ANY AND ALL OTHER COMMERCIAL DAMAGES OR
   LOSSES, EVEN IF SUCH PARTY SHALL HAVE BEEN INFORMED OF THE POSSIBILITY OF
   SUCH DAMAGES. THIS LIMITATION OF LIABILITY SHALL NOT APPLY TO LIABILITY FOR
   DEATH OR PERSONAL INJURY RESULTING FROM SUCH PARTY.S NEGLIGENCE TO THE EXTENT
   APPLICABLE LAW PROHIBITS SUCH LIMITATION. SOME JURISDICTIONS DO NOT ALLOW THE
   EXCLUSION OR LIMITATION OF INCIDENTAL OR CONSEQUENTIAL DAMAGES, SO THIS
   EXCLUSION AND LIMITATION MAY NOT APPLY TO YOU.

8. U.S. GOVERNMENT END USERS.

   The Covered Software is a .commercial item,. as that term is defined in 48
   C.F.R. 2.101 (Oct. 1995), consisting of .commercial computer software. (as
   that term is defined at 48 C.F.R. ? 252.227-7014(a)(1)) and commercial
   computer software documentation. as such terms are used in 48 C.F.R. 12.212
   (Sept. 1995). Consistent with 48 C.F.R. 12.212 and 48 C.F.R. 227.7202-1
   through 227.7202-4 (June 1995), all U.S. Government End Users acquire Covered
   Software with only those rights set forth herein. This U.S. Government Rights
   clause is in lieu of, and supersedes, any other FAR, DFAR, or other clause or
   provision that addresses Government rights in computer software under this
   License.

9. MISCELLANEOUS.

   This License represents the complete agreement concerning subject matter
   hereof. If any provision of this License is held to be unenforceable, such
   provision shall be reformed only to the extent necessary to make it
   enforceable. This License shall be governed by the law of the jurisdiction
   specified in a notice contained within the Original Software (except to the
   extent applicable law, if any, provides otherwise), excluding such
   jurisdiction's conflict-of-law provisions. Any litigation relating to this
   License shall be subject to the jurisdiction of the courts located in the
   jurisdiction and venue specified in a notice contained within the Original
   Software, with the losing party responsible for costs, including, without
   limitation, court costs and reasonable attorneys. fees and expenses. The
   application of the United Nations Convention on Contracts for the
   International Sale of Goods is expressly excluded. Any law or regulation
   which provides that the language of a contract shall be construed against
   the drafter shall not apply to this License. You agree that You alone are
   responsible for compliance with the United States export administration
   regulations (and the export control laws and regulation of any other
   countries) when You use, distribute or otherwise make available any Covered
   Software.

10. RESPONSIBILITY FOR CLAIMS.

   As between Initial Developer and the Contributors, each party is responsible
   for claims and damages arising, directly or indirectly, out of its
   utilization of rights under this License and You agree to work with Initial
   Developer and Contributors to distribute such responsibility on an equitable
   basis. Nothing herein is intended or shall be deemed to constitute any
   admission of liability.

   NOTICE PURSUANT TO SECTION 9 OF THE COMMON DEVELOPMENT AND DISTRIBUTION
   LICENSE (CDDL)

   The code released under the CDDL shall be governed by the laws of the State
   of California (excluding conflict-of-law provisions). Any litigation relating
   to this License shall be subject to the jurisdiction of the Federal Courts of
   the Northern District of California and the state courts of the State of
   California, with venue lying in Santa Clara County, California.


For the following Jakarta EE Schemas:
- jakartaee_9.xsd
- jakartaee_10.xsd
- jakarta_web-services_2_0.xsd
- jakarta_web-services_client_2_0.xsd
- jsp_3_0.xsd
- jsp_3_1.xsd
- web-app_5_0.xsd
- web-app_6_0.xsd
- web-commonn_5_0.xsd
- web-commonn_6_0.xsd
- web-fragment_5_0.xsd
- web-fragment_6_0.xsd
- web-jsptaglibrary_3_0.xsd
- web-jsptaglibrary_3_1.xsd

Eclipse Public License - v 2.0

    THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
    PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
    OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.

1. DEFINITIONS

"Contribution" means:

  a) in the case of the initial Contributor, the initial content
     Distributed under this Agreement, and

  b) in the case of each subsequent Contributor:
     i) changes to the Program, and
     ii) additions to the Program;
  where such changes and/or additions to the Program originate from
  and are Distributed by that particular Contributor. A Contribution
  "originates" from a Contributor if it was added to the Program by
  such Contributor itself or anyone acting on such Contributor's behalf.
  Contributions do not include changes or additions to the Program that
  are not Modified Works.

"Contributor" means any person or entity that Distributes the Program.

"Licensed Patents" mean patent claims licensable by a Contributor which
are necessarily infringed by the use or sale of its Contribution alone
or when combined with the Program.

"Program" means the Contributions Distributed in accordance with this
Agreement.

"Recipient" means anyone who receives the Program under this Agreement
or any Secondary License (as applicable), including Contributors.

"Derivative Works" shall mean any work, whether in Source Code or other
form, that is based on (or derived from) the Program and for which the
editorial revisions, annotations, elaborations, or other modifications
represent, as a whole, an original work of authorship.

"Modified Works" shall mean any work in Source Code or other form that
results from an addition to, deletion from, or modification of the
contents of the Program, including, for purposes of clarity any new file
in Source Code form that contains any contents of the Program. Modified
Works shall not include works that contain only declarations,
interfaces, types, classes, structures, or files of the Program solely
in each case in order to link to, bind by name, or subclass the Program
or Modified Works thereof.

"Distribute" means the acts of a) distributing or b) making available
in any manner that enables the transfer of a copy.

"Source Code" means the form of a Program preferred for making
modifications, including but not limited to software source code,
documentation source, and configuration files.

"Secondary License" means either the GNU General Public License,
Version 2.0, or any later versions of that license, including any
exceptions or additional permissions as identified by the initial
Contributor.

2. GRANT OF RIGHTS

  a) Subject to the terms of this Agreement, each Contributor hereby
  grants Recipient a non-exclusive, worldwide, royalty-free copyright
  license to reproduce, prepare Derivative Works of, publicly display,
  publicly perform, Distribute and sublicense the Contribution of such
  Contributor, if any, and such Derivative Works.

  b) Subject to the terms of this Agreement, each Contributor hereby
  grants Recipient a non-exclusive, worldwide, royalty-free patent
  license under Licensed Patents to make, use, sell, offer to sell,
  import and otherwise transfer the Contribution of such Contributor,
  if any, in Source Code or other form. This patent license shall
  apply to the combination of the Contribution and the Program if, at
  the time the Contribution is added by the Contributor, such addition
  of the Contribution causes such combination to be covered by the
  Licensed Patents. The patent license shall not apply to any other
  combinations which include the Contribution. No hardware per se is
  licensed hereunder.

  c) Recipient understands that although each Contributor grants the
  licenses to its Contributions set forth herein, no assurances are
  provided by any Contributor that the Program does not infringe the
  patent or other intellectual property rights of any other entity.
  Each Contributor disclaims any liability to Recipient for claims
  brought by any other entity based on infringement of intellectual
  property rights or otherwise. As a condition to exercising the
  rights and licenses granted hereunder, each Recipient hereby
  assumes sole responsibility to secure any other intellectual
  property rights needed, if any. For example, if a third party
  patent license is required to allow Recipient to Distribute the
  Program, it is Recipient's responsibility to acquire that license
  before distributing the Program.

  d) Each Contributor represents that to its knowledge it has
  sufficient copyright rights in its Contribution, if any, to grant
  the copyright license set forth in this Agreement.

  e) Notwithstanding the terms of any Secondary License, no
  Contributor makes additional grants to any Recipient (other than
  those set forth in this Agreement) as a result of such Recipient's
  receipt of the Program under the terms of a Secondary License
  (if permitted under the terms of Section 3).

3. REQUIREMENTS

3.1 If a Contributor Distributes the Program in any form, then:

  a) the Program must also be made available as Source Code, in
  accordance with section 3.2, and the Contributor must accompany
  the Program with a statement that the Source Code for the Program
  is available under this Agreement, and informs Recipients how to
  obtain it in a reasonable manner on or through a medium customarily
  used for software exchange; and

  b) the Contributor may Distribute the Program under a license
  different than this Agreement, provided that such license:
     i) effectively disclaims on behalf of all other Contributors all
     warranties and conditions, express and implied, including
     warranties or conditions of title and non-infringement, and
     implied warranties or conditions of merchantability and fitness
     for a particular purpose;

     ii) effectively excludes on behalf of all other Contributors all
     liability for damages, including direct, indirect, special,
     incidental and consequential damages, such as lost profits;

     iii) does not attempt to limit or alter the recipients' rights
     in the Source Code under section 3.2; and

     iv) requires any subsequent distribution of the Program by any
     party to be under a license that satisfies the requirements
     of this section 3.

3.2 When the Program is Distributed as Source Code:

  a) it must be made available under this Agreement, or if the
  Program (i) is combined with other material in a separate file or
  files made available under a Secondary License, and (ii) the initial
  Contributor attached to the Source Code the notice described in
  Exhibit A of this Agreement, then the Program may be made available
  under the terms of such Secondary Licenses, and

  b) a copy of this Agreement must be included with each copy of
  the Program.

3.3 Contributors may not remove or alter any copyright, patent,
trademark, attribution notices, disclaimers of warranty, or limitations
of liability ("notices") contained within the Program from any copy of
the Program which they Distribute, provided that Contributors may add
their own appropriate notices.

4. COMMERCIAL DISTRIBUTION

Commercial distributors of software may accept certain responsibilities
with respect to end users, business partners and the like. While this
license is intended to facilitate the commercial use of the Program,
the Contributor who includes the Program in a commercial product
offering should do so in a manner which does not create potential
liability for other Contributors. Therefore, if a Contributor includes
the Program in a commercial product offering, such Contributor
("Commercial Contributor") hereby agrees to defend and indemnify every
other Contributor ("Indemnified Contributor") against any losses,
damages and costs (collectively "Losses") arising from claims, lawsuits
and other legal actions brought by a third party against the Indemnified
Contributor to the extent caused by the acts or omissions of such
Commercial Contributor in connection with its distribution of the Program
in a commercial product offering. The obligations in this section do not
apply to any claims or Losses relating to any actual or alleged
intellectual property infringement. In order to qualify, an Indemnified
Contributor must: a) promptly notify the Commercial Contributor in
writing of such claim, and b) allow the Commercial Contributor to control,
and cooperate with the Commercial Contributor in, the defense and any
related settlement negotiations. The Indemnified Contributor may
participate in any such claim at its own expense.

For example, a Contributor might include the Program in a commercial
product offering, Product X. That Contributor is then a Commercial
Contributor. If that Commercial Contributor then makes performance
claims, or offers warranties related to Product X, those performance
claims and warranties are such Commercial Contributor's responsibility
alone. Under this section, the Commercial Contributor would have to
defend claims against the other Contributors related to those performance
claims and warranties, and if a court requires any other Contributor to
pay any damages as a result, the Commercial Contributor must pay
those damages.

5. NO WARRANTY

EXCEPT AS EXPRESSLY SET FORTH IN THIS AGREEMENT, AND TO THE EXTENT
PERMITTED BY APPLICABLE LAW, THE PROGRAM IS PROVIDED ON AN "AS IS"
BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, EITHER EXPRESS OR
IMPLIED INCLUDING, WITHOUT LIMITATION, ANY WARRANTIES OR CONDITIONS OF
TITLE, NON-INFRINGEMENT, MERCHANTABILITY OR FITNESS FOR A PARTICULAR
PURPOSE. Each Recipient is solely responsible for determining the
appropriateness of using and distributing the Program and assumes all
risks associated with its exercise of rights under this Agreement,
including but not limited to the risks and costs of program errors,
compliance with applicable laws, damage to or loss of data, programs
or equipment, and unavailability or interruption of operations.

6. DISCLAIMER OF LIABILITY

EXCEPT AS EXPRESSLY SET FORTH IN THIS AGREEMENT, AND TO THE EXTENT
PERMITTED BY APPLICABLE LAW, NEITHER RECIPIENT NOR ANY CONTRIBUTORS
SHALL HAVE ANY LIABILITY FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING WITHOUT LIMITATION LOST
PROFITS), HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OR DISTRIBUTION OF THE PROGRAM OR THE
EXERCISE OF ANY RIGHTS GRANTED HEREUNDER, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGES.

7. GENERAL

If any provision of this Agreement is invalid or unenforceable under
applicable law, it shall not affect the validity or enforceability of
the remainder of the terms of this Agreement, and without further
action by the parties hereto, such provision shall be reformed to the
minimum extent necessary to make such provision valid and enforceable.

If Recipient institutes patent litigation against any entity
(including a cross-claim or counterclaim in a lawsuit) alleging that the
Program itself (excluding combinations of the Program with other software
or hardware) infringes such Recipient's patent(s), then such Recipient's
rights granted under Section 2(b) shall terminate as of the date such
litigation is filed.

All Recipient's rights under this Agreement shall terminate if it
fails to comply with any of the material terms or conditions of this
Agreement and does not cure such failure in a reasonable period of
time after becoming aware of such noncompliance. If all Recipient's
rights under this Agreement terminate, Recipient agrees to cease use
and distribution of the Program as soon as reasonably practicable.
However, Recipient's obligations under this Agreement and any licenses
granted by Recipient relating to the Program shall continue and survive.

Everyone is permitted to copy and distribute copies of this Agreement,
but in order to avoid inconsistency the Agreement is copyrighted and
may only be modified in the following manner. The Agreement Steward
reserves the right to publish new versions (including revisions) of
this Agreement from time to time. No one other than the Agreement
Steward has the right to modify this Agreement. The Eclipse Foundation
is the initial Agreement Steward. The Eclipse Foundation may assign the
responsibility to serve as the Agreement Steward to a suitable separate
entity. Each new version of the Agreement will be given a distinguishing
version number. The Program (including Contributions) may always be
Distributed subject to the version of the Agreement under which it was
received. In addition, after a new version of the Agreement is published,
Contributor may elect to Distribute the Program (including its
Contributions) under the new version.

Except as expressly stated in Sections 2(a) and 2(b) above, Recipient
receives no rights or licenses to the intellectual property of any
Contributor under this Agreement, whether expressly, by implication,
estoppel or otherwise. All rights in the Program not expressly granted
under this Agreement are reserved. Nothing in this Agreement is intended
to be enforceable by any entity that is not a Contributor or Recipient.
No third-party beneficiary rights are created under this Agreement.

Exhibit A - Form of Secondary Licenses Notice

"This Source Code may also be made available under the following
Secondary Licenses when the conditions for such availability set forth
in the Eclipse Public License, v. 2.0 are satisfied: {name license(s),
version(s), and exceptions or additional permissions here}."

  Simply including a copy of this Agreement, including this Exhibit A
  is not sufficient to license the Source Code under Secondary Licenses.

  If it is not possible or desirable to put the notice in a particular
  file, then You may include the notice in a location (such as a LICENSE
  file in a relevant directory) where a recipient would be likely to
  look for such a notice.

  You may add additional accurate notices of copyright ownership.
````

### Aviso 46

- `tomcat-embed-core-10.1.50.jar` — META-INF/NOTICE

````text
Apache Tomcat
Copyright 1999-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).

The original XML Schemas for Java EE Deployment Descriptors:
 - javaee_5.xsd
 - javaee_web_services_1_2.xsd
 - javaee_web_services_client_1_2.xsd
 - javaee_6.xsd
 - javaee_web_services_1_3.xsd
 - javaee_web_services_client_1_3.xsd
 - jsp_2_2.xsd
 - web-app_3_0.xsd
 - web-common_3_0.xsd
 - web-fragment_3_0.xsd
 - javaee_7.xsd
 - javaee_web_services_1_4.xsd
 - javaee_web_services_client_1_4.xsd
 - jsp_2_3.xsd
 - web-app_3_1.xsd
 - web-common_3_1.xsd
 - web-fragment_3_1.xsd
 - javaee_8.xsd
 - web-app_4_0.xsd
 - web-common_4_0.xsd
 - web-fragment_4_0.xsd

may be obtained from:
http://www.oracle.com/webfolder/technetwork/jsc/xml/ns/javaee/index.html
````

### Aviso 47

- `tomcat-embed-el-10.1.50.jar` — META-INF/NOTICE
- `tomcat-embed-websocket-10.1.50.jar` — META-INF/NOTICE

````text
Apache Tomcat
Copyright 1999-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
````

### Aviso 48

- `aspectjweaver-1.9.25.1.jar` — LICENSE-AspectJ.adoc

````text
SPDX-License-Identifier: EPL-2.0 AND BSD-3-Clause AND Apache-1.1

= AspectJ^TM^ Compiler and Core Tools License

Source code for these binaries is available from https://eclipse.org/aspectj

== EPL-2.0

The Eclipse Foundation makes available all content in this distribution
("Content"). Unless otherwise indicated below, the Content is provided
to you under the terms and conditions of the Eclipse Public License
Version v 2.0 ("EPL"). A copy of the EPL is available at
link:[https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.txt].
For purposes of the EPL, "Program" will mean the Content.

If you did not receive this Content directly from the Eclipse
Foundation, the Content is being redistributed by another party
("Redistributor") and different terms and conditions may apply to your
use of any object code in the Content. Check the Redistributor's license
that was provided with the Content. If no such license exists, contact
the Redistributor. Unless otherwise indicated below, the terms and
conditions of the EPL still apply to any source code in the Content and
such source code may be obtained at link:[https://www.eclipse.org].

== Third Party Content

The Content includes items that have been sourced from third parties as
set out below. If you did not receive this Content directly from the
Eclipse Foundation, the following is provided for informational purposes
only, and you should look to the Redistributor's license for terms and
conditions of use.

=== BCEL v5.1 (Apache-1.1)

This product contains software developed by the Apache Software
Foundation (https://www.apache.org/[https://www.apache.org]).

AspectJ includes a modified version of the Apache Jakarta Byte Code
Engineering Library (BCEL) v5.1. BCEL is available at
https://commons.apache.org/bcel/. Source code for the modified version
of BCEL is available at Eclipse.org in the AspectJ source tree. This
code is made available under the Apache Software License v1.1.
The license is also reproduced here:

[source, text]
....
/* ====================================================================
 * The Apache Software License, Version 1.1
 *
 * Copyright (c) 2001 The Apache Software Foundation.  All rights
 * reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution,
 *    if any, must include the following acknowledgment:
 *       "This product includes software developed by the
 *        Apache Software Foundation (https://www.apache.org/)."
 *    Alternately, this acknowledgment may appear in the software itself,
 *    if and wherever such third-party acknowledgments normally appear.
 *
 * 4. The names "Apache" and "Apache Software Foundation" and
 *    "Apache BCEL" must not be used to endorse or promote products
 *    derived from this software without prior written permission. For
 *    written permission, please contact apache@apache.org.
 *
 * 5. Products derived from this software may not be called "Apache",
 *    "Apache BCEL", nor may "Apache" appear in their name, without
 *    prior written permission of the Apache Software Foundation.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL THE APACHE SOFTWARE FOUNDATION OR
 * ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <https://www.apache.org/>.
 */
....

=== ASM v9.8 (BSD-3-Clause)

AspectJ includes a binary version of ASM v9.8
(https://asm.ow2.io[https://asm.ow2.io]) The
source code for ASM is available from the ObjectWeb download site at
https://gitlab.ow2.org/asm/asm.

The ASM license is available at https://asm.ow2.io/license.html.
The license is also reproduced here:

[source, text]
....
ASM: a very small and fast Java bytecode manipulation framework
Copyright (c) 2000-2011 INRIA, France Telecom
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:
1. Redistributions of source code must retain the above copyright
  notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright
  notice, this list of conditions and the following disclaimer in the
  documentation and/or other materials provided with the distribution.
3. Neither the name of the copyright holders nor the names of its
  contributors may be used to endorse or promote products derived from
  this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
THE POSSIBILITY OF SUCH DAMAGE.
....
````

### Aviso 49

- `aspectjweaver-1.9.25.1.jar` — licenses/LICENSE-Apache-1.1.txt

````text
/* ====================================================================
 * The Apache Software License, Version 1.1
 *
 * Copyright (c) 2001 The Apache Software Foundation.  All rights
 * reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution,
 *    if any, must include the following acknowledgment:
 *       "This product includes software developed by the
 *        Apache Software Foundation (https://www.apache.org/)."
 *    Alternately, this acknowledgment may appear in the software itself,
 *    if and wherever such third-party acknowledgments normally appear.
 *
 * 4. The names "Apache" and "Apache Software Foundation" and
 *    "Apache BCEL" must not be used to endorse or promote products
 *    derived from this software without prior written permission. For
 *    written permission, please contact apache@apache.org.
 *
 * 5. Products derived from this software may not be called "Apache",
 *    "Apache BCEL", nor may "Apache" appear in their name, without
 *    prior written permission of the Apache Software Foundation.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL THE APACHE SOFTWARE FOUNDATION OR
 * ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <https://www.apache.org/>.
 */
````

### Aviso 50

- `aspectjweaver-1.9.25.1.jar` — licenses/LICENSE-EPL-2.0.txt

````text
Eclipse Public License - v 2.0

    THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
    PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
    OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.

1. DEFINITIONS

"Contribution" means:

  a) in the case of the initial Contributor, the initial content
     Distributed under this Agreement, and

  b) in the case of each subsequent Contributor:
     i) changes to the Program, and
     ii) additions to the Program;
  where such changes and/or additions to the Program originate from
  and are Distributed by that particular Contributor. A Contribution
  "originates" from a Contributor if it was added to the Program by
  such Contributor itself or anyone acting on such Contributor's behalf.
  Contributions do not include changes or additions to the Program that
  are not Modified Works.

"Contributor" means any person or entity that Distributes the Program.

"Licensed Patents" mean patent claims licensable by a Contributor which
are necessarily infringed by the use or sale of its Contribution alone
or when combined with the Program.

"Program" means the Contributions Distributed in accordance with this
Agreement.

"Recipient" means anyone who receives the Program under this Agreement
or any Secondary License (as applicable), including Contributors.

"Derivative Works" shall mean any work, whether in Source Code or other
form, that is based on (or derived from) the Program and for which the
editorial revisions, annotations, elaborations, or other modifications
represent, as a whole, an original work of authorship.

"Modified Works" shall mean any work in Source Code or other form that
results from an addition to, deletion from, or modification of the
contents of the Program, including, for purposes of clarity any new file
in Source Code form that contains any contents of the Program. Modified
Works shall not include works that contain only declarations,
interfaces, types, classes, structures, or files of the Program solely
in each case in order to link to, bind by name, or subclass the Program
or Modified Works thereof.

"Distribute" means the acts of a) distributing or b) making available
in any manner that enables the transfer of a copy.

"Source Code" means the form of a Program preferred for making
modifications, including but not limited to software source code,
documentation source, and configuration files.

"Secondary License" means either the GNU General Public License,
Version 2.0, or any later versions of that license, including any
exceptions or additional permissions as identified by the initial
Contributor.

2. GRANT OF RIGHTS

  a) Subject to the terms of this Agreement, each Contributor hereby
  grants Recipient a non-exclusive, worldwide, royalty-free copyright
  license to reproduce, prepare Derivative Works of, publicly display,
  publicly perform, Distribute and sublicense the Contribution of such
  Contributor, if any, and such Derivative Works.

  b) Subject to the terms of this Agreement, each Contributor hereby
  grants Recipient a non-exclusive, worldwide, royalty-free patent
  license under Licensed Patents to make, use, sell, offer to sell,
  import and otherwise transfer the Contribution of such Contributor,
  if any, in Source Code or other form. This patent license shall
  apply to the combination of the Contribution and the Program if, at
  the time the Contribution is added by the Contributor, such addition
  of the Contribution causes such combination to be covered by the
  Licensed Patents. The patent license shall not apply to any other
  combinations which include the Contribution. No hardware per se is
  licensed hereunder.

  c) Recipient understands that although each Contributor grants the
  licenses to its Contributions set forth herein, no assurances are
  provided by any Contributor that the Program does not infringe the
  patent or other intellectual property rights of any other entity.
  Each Contributor disclaims any liability to Recipient for claims
  brought by any other entity based on infringement of intellectual
  property rights or otherwise. As a condition to exercising the
  rights and licenses granted hereunder, each Recipient hereby
  assumes sole responsibility to secure any other intellectual
  property rights needed, if any. For example, if a third party
  patent license is required to allow Recipient to Distribute the
  Program, it is Recipient's responsibility to acquire that license
  before distributing the Program.

  d) Each Contributor represents that to its knowledge it has
  sufficient copyright rights in its Contribution, if any, to grant
  the copyright license set forth in this Agreement.

  e) Notwithstanding the terms of any Secondary License, no
  Contributor makes additional grants to any Recipient (other than
  those set forth in this Agreement) as a result of such Recipient's
  receipt of the Program under the terms of a Secondary License
  (if permitted under the terms of Section 3).

3. REQUIREMENTS

3.1 If a Contributor Distributes the Program in any form, then:

  a) the Program must also be made available as Source Code, in
  accordance with section 3.2, and the Contributor must accompany
  the Program with a statement that the Source Code for the Program
  is available under this Agreement, and informs Recipients how to
  obtain it in a reasonable manner on or through a medium customarily
  used for software exchange; and

  b) the Contributor may Distribute the Program under a license
  different than this Agreement, provided that such license:
     i) effectively disclaims on behalf of all other Contributors all
     warranties and conditions, express and implied, including
     warranties or conditions of title and non-infringement, and
     implied warranties or conditions of merchantability and fitness
     for a particular purpose;

     ii) effectively excludes on behalf of all other Contributors all
     liability for damages, including direct, indirect, special,
     incidental and consequential damages, such as lost profits;

     iii) does not attempt to limit or alter the recipients' rights
     in the Source Code under section 3.2; and

     iv) requires any subsequent distribution of the Program by any
     party to be under a license that satisfies the requirements
     of this section 3.

3.2 When the Program is Distributed as Source Code:

  a) it must be made available under this Agreement, or if the
  Program (i) is combined with other material in a separate file or
  files made available under a Secondary License, and (ii) the initial
  Contributor attached to the Source Code the notice described in
  Exhibit A of this Agreement, then the Program may be made available
  under the terms of such Secondary Licenses, and

  b) a copy of this Agreement must be included with each copy of
  the Program.

3.3 Contributors may not remove or alter any copyright, patent,
trademark, attribution notices, disclaimers of warranty, or limitations
of liability ("notices") contained within the Program from any copy of
the Program which they Distribute, provided that Contributors may add
their own appropriate notices.

4. COMMERCIAL DISTRIBUTION

Commercial distributors of software may accept certain responsibilities
with respect to end users, business partners and the like. While this
license is intended to facilitate the commercial use of the Program,
the Contributor who includes the Program in a commercial product
offering should do so in a manner which does not create potential
liability for other Contributors. Therefore, if a Contributor includes
the Program in a commercial product offering, such Contributor
("Commercial Contributor") hereby agrees to defend and indemnify every
other Contributor ("Indemnified Contributor") against any losses,
damages and costs (collectively "Losses") arising from claims, lawsuits
and other legal actions brought by a third party against the Indemnified
Contributor to the extent caused by the acts or omissions of such
Commercial Contributor in connection with its distribution of the Program
in a commercial product offering. The obligations in this section do not
apply to any claims or Losses relating to any actual or alleged
intellectual property infringement. In order to qualify, an Indemnified
Contributor must: a) promptly notify the Commercial Contributor in
writing of such claim, and b) allow the Commercial Contributor to control,
and cooperate with the Commercial Contributor in, the defense and any
related settlement negotiations. The Indemnified Contributor may
participate in any such claim at its own expense.

For example, a Contributor might include the Program in a commercial
product offering, Product X. That Contributor is then a Commercial
Contributor. If that Commercial Contributor then makes performance
claims, or offers warranties related to Product X, those performance
claims and warranties are such Commercial Contributor's responsibility
alone. Under this section, the Commercial Contributor would have to
defend claims against the other Contributors related to those performance
claims and warranties, and if a court requires any other Contributor to
pay any damages as a result, the Commercial Contributor must pay
those damages.

5. NO WARRANTY

EXCEPT AS EXPRESSLY SET FORTH IN THIS AGREEMENT, AND TO THE EXTENT
PERMITTED BY APPLICABLE LAW, THE PROGRAM IS PROVIDED ON AN "AS IS"
BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, EITHER EXPRESS OR
IMPLIED INCLUDING, WITHOUT LIMITATION, ANY WARRANTIES OR CONDITIONS OF
TITLE, NON-INFRINGEMENT, MERCHANTABILITY OR FITNESS FOR A PARTICULAR
PURPOSE. Each Recipient is solely responsible for determining the
appropriateness of using and distributing the Program and assumes all
risks associated with its exercise of rights under this Agreement,
including but not limited to the risks and costs of program errors,
compliance with applicable laws, damage to or loss of data, programs
or equipment, and unavailability or interruption of operations.

6. DISCLAIMER OF LIABILITY

EXCEPT AS EXPRESSLY SET FORTH IN THIS AGREEMENT, AND TO THE EXTENT
PERMITTED BY APPLICABLE LAW, NEITHER RECIPIENT NOR ANY CONTRIBUTORS
SHALL HAVE ANY LIABILITY FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING WITHOUT LIMITATION LOST
PROFITS), HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OR DISTRIBUTION OF THE PROGRAM OR THE
EXERCISE OF ANY RIGHTS GRANTED HEREUNDER, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGES.

7. GENERAL

If any provision of this Agreement is invalid or unenforceable under
applicable law, it shall not affect the validity or enforceability of
the remainder of the terms of this Agreement, and without further
action by the parties hereto, such provision shall be reformed to the
minimum extent necessary to make such provision valid and enforceable.

If Recipient institutes patent litigation against any entity
(including a cross-claim or counterclaim in a lawsuit) alleging that the
Program itself (excluding combinations of the Program with other software
or hardware) infringes such Recipient's patent(s), then such Recipient's
rights granted under Section 2(b) shall terminate as of the date such
litigation is filed.

All Recipient's rights under this Agreement shall terminate if it
fails to comply with any of the material terms or conditions of this
Agreement and does not cure such failure in a reasonable period of
time after becoming aware of such noncompliance. If all Recipient's
rights under this Agreement terminate, Recipient agrees to cease use
and distribution of the Program as soon as reasonably practicable.
However, Recipient's obligations under this Agreement and any licenses
granted by Recipient relating to the Program shall continue and survive.

Everyone is permitted to copy and distribute copies of this Agreement,
but in order to avoid inconsistency the Agreement is copyrighted and
may only be modified in the following manner. The Agreement Steward
reserves the right to publish new versions (including revisions) of
this Agreement from time to time. No one other than the Agreement
Steward has the right to modify this Agreement. The Eclipse Foundation
is the initial Agreement Steward. The Eclipse Foundation may assign the
responsibility to serve as the Agreement Steward to a suitable separate
entity. Each new version of the Agreement will be given a distinguishing
version number. The Program (including Contributions) may always be
Distributed subject to the version of the Agreement under which it was
received. In addition, after a new version of the Agreement is published,
Contributor may elect to Distribute the Program (including its
Contributions) under the new version.

Except as expressly stated in Sections 2(a) and 2(b) above, Recipient
receives no rights or licenses to the intellectual property of any
Contributor under this Agreement, whether expressly, by implication,
estoppel or otherwise. All rights in the Program not expressly granted
under this Agreement are reserved. Nothing in this Agreement is intended
to be enforceable by any entity that is not a Contributor or Recipient.
No third-party beneficiary rights are created under this Agreement.

Exhibit A - Form of Secondary Licenses Notice

"This Source Code may also be made available under the following
Secondary Licenses when the conditions for such availability set forth
in the Eclipse Public License, v. 2.0 are satisfied: {name license(s),
version(s), and exceptions or additional permissions here}."

  Simply including a copy of this Agreement, including this Exhibit A
  is not sufficient to license the Source Code under Secondary Licenses.

  If it is not possible or desirable to put the notice in a particular
  file, then You may include the notice in a location (such as a LICENSE
  file in a relevant directory) where a recipient would be likely to
  look for such a notice.

  You may add additional accurate notices of copyright ownership.
````

### Aviso 51

- `attoparser-2.0.7.RELEASE.jar` — META-INF/NOTICE.txt

````text
Copyright (c) 2012-2022, The ATTOPARSER team (https://www.attoparser.org)

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
````

### Aviso 52

- `bcprov-jdk18on-1.78.1.jar` — https://raw.githubusercontent.com/bcgit/bc-java/r1rv78v1/LICENSE.html

````text
<html>
<body bgcolor=#ffffff>

Copyright (c) 2000-2023 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)
<p>
Permission is hereby granted, free of charge, to any person obtaining a copy of this software
and associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:
<p>
The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.
<p>
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
</body>
</html>
````

### Aviso 53

- `checker-qual-3.49.5.jar` — META-INF/LICENSE.txt

````text
Checker Framework qualifiers
Copyright 2004-present by the Checker Framework developers

MIT License:

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
````

### Aviso 54

- `angus-activation-2.0.3.jar` — META-INF/NOTICE.md

````text
# Notices for Eclipse Angus

This content is produced and maintained by the Eclipse Angus project.

* Project home: https://projects.eclipse.org/projects/ee4j.angus

## Trademarks

Eclipse Angus is a trademark of the Eclipse Foundation.

## Copyright

All content is the property of the respective authors or their employers. For
more information regarding authorship of content, please consult the listed
source code repository logs.

## Declared Project Licenses

This program and the accompanying materials are made available under the terms
of the Eclipse Distribution License v1.0 which is available at
https://www.eclipse.org/org/documents/edl-v10.php.

SPDX-License-Identifier: BSD-3-Clause

## Source Code

The project maintains the following source code repositories:

* https://github.com/eclipse-ee4j/angus-activation
* https://github.com/eclipse-ee4j/angus-mail

## Third-party Content

This project leverages the following third party content.

None

## Cryptography

Content may contain encryption software. The country in which you are currently
may have restrictions on the import, possession, and use, and/or re-export to
another country, of encryption software. BEFORE using any encryption software,
please check the country's laws, regulations and policies concerning the import,
possession, or use, and re-export of encryption software, to see if this is
permitted.
````

### Aviso 55

- `jakarta.mail-2.0.5.jar` — META-INF/LICENSE.md

````text
# Eclipse Public License - v 2.0

        THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
        PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
        OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.

    1. DEFINITIONS

    "Contribution" means:

      a) in the case of the initial Contributor, the initial content
         Distributed under this Agreement, and

      b) in the case of each subsequent Contributor:
         i) changes to the Program, and
         ii) additions to the Program;
      where such changes and/or additions to the Program originate from
      and are Distributed by that particular Contributor. A Contribution
      "originates" from a Contributor if it was added to the Program by
      such Contributor itself or anyone acting on such Contributor's behalf.
      Contributions do not include changes or additions to the Program that
      are not Modified Works.

    "Contributor" means any person or entity that Distributes the Program.

    "Licensed Patents" mean patent claims licensable by a Contributor which
    are necessarily infringed by the use or sale of its Contribution alone
    or when combined with the Program.

    "Program" means the Contributions Distributed in accordance with this
    Agreement.

    "Recipient" means anyone who receives the Program under this Agreement
    or any Secondary License (as applicable), including Contributors.

    "Derivative Works" shall mean any work, whether in Source Code or other
    form, that is based on (or derived from) the Program and for which the
    editorial revisions, annotations, elaborations, or other modifications
    represent, as a whole, an original work of authorship.

    "Modified Works" shall mean any work in Source Code or other form that
    results from an addition to, deletion from, or modification of the
    contents of the Program, including, for purposes of clarity any new file
    in Source Code form that contains any contents of the Program. Modified
    Works shall not include works that contain only declarations,
    interfaces, types, classes, structures, or files of the Program solely
    in each case in order to link to, bind by name, or subclass the Program
    or Modified Works thereof.

    "Distribute" means the acts of a) distributing or b) making available
    in any manner that enables the transfer of a copy.

    "Source Code" means the form of a Program preferred for making
    modifications, including but not limited to software source code,
    documentation source, and configuration files.

    "Secondary License" means either the GNU General Public License,
    Version 2.0, or any later versions of that license, including any
    exceptions or additional permissions as identified by the initial
    Contributor.

    2. GRANT OF RIGHTS

      a) Subject to the terms of this Agreement, each Contributor hereby
      grants Recipient a non-exclusive, worldwide, royalty-free copyright
      license to reproduce, prepare Derivative Works of, publicly display,
      publicly perform, Distribute and sublicense the Contribution of such
      Contributor, if any, and such Derivative Works.

      b) Subject to the terms of this Agreement, each Contributor hereby
      grants Recipient a non-exclusive, worldwide, royalty-free patent
      license under Licensed Patents to make, use, sell, offer to sell,
      import and otherwise transfer the Contribution of such Contributor,
      if any, in Source Code or other form. This patent license shall
      apply to the combination of the Contribution and the Program if, at
      the time the Contribution is added by the Contributor, such addition
      of the Contribution causes such combination to be covered by the
      Licensed Patents. The patent license shall not apply to any other
      combinations which include the Contribution. No hardware per se is
      licensed hereunder.

      c) Recipient understands that although each Contributor grants the
      licenses to its Contributions set forth herein, no assurances are
      provided by any Contributor that the Program does not infringe the
      patent or other intellectual property rights of any other entity.
      Each Contributor disclaims any liability to Recipient for claims
      brought by any other entity based on infringement of intellectual
      property rights or otherwise. As a condition to exercising the
      rights and licenses granted hereunder, each Recipient hereby
      assumes sole responsibility to secure any other intellectual
      property rights needed, if any. For example, if a third party
      patent license is required to allow Recipient to Distribute the
      Program, it is Recipient's responsibility to acquire that license
      before distributing the Program.

      d) Each Contributor represents that to its knowledge it has
      sufficient copyright rights in its Contribution, if any, to grant
      the copyright license set forth in this Agreement.

      e) Notwithstanding the terms of any Secondary License, no
      Contributor makes additional grants to any Recipient (other than
      those set forth in this Agreement) as a result of such Recipient's
      receipt of the Program under the terms of a Secondary License
      (if permitted under the terms of Section 3).

    3. REQUIREMENTS

    3.1 If a Contributor Distributes the Program in any form, then:

      a) the Program must also be made available as Source Code, in
      accordance with section 3.2, and the Contributor must accompany
      the Program with a statement that the Source Code for the Program
      is available under this Agreement, and informs Recipients how to
      obtain it in a reasonable manner on or through a medium customarily
      used for software exchange; and

      b) the Contributor may Distribute the Program under a license
      different than this Agreement, provided that such license:
         i) effectively disclaims on behalf of all other Contributors all
         warranties and conditions, express and implied, including
         warranties or conditions of title and non-infringement, and
         implied warranties or conditions of merchantability and fitness
         for a particular purpose;

         ii) effectively excludes on behalf of all other Contributors all
         liability for damages, including direct, indirect, special,
         incidental and consequential damages, such as lost profits;

         iii) does not attempt to limit or alter the recipients' rights
         in the Source Code under section 3.2; and

         iv) requires any subsequent distribution of the Program by any
         party to be under a license that satisfies the requirements
         of this section 3.

    3.2 When the Program is Distributed as Source Code:

      a) it must be made available under this Agreement, or if the
      Program (i) is combined with other material in a separate file or
      files made available under a Secondary License, and (ii) the initial
      Contributor attached to the Source Code the notice described in
      Exhibit A of this Agreement, then the Program may be made available
      under the terms of such Secondary Licenses, and

      b) a copy of this Agreement must be included with each copy of
      the Program.

    3.3 Contributors may not remove or alter any copyright, patent,
    trademark, attribution notices, disclaimers of warranty, or limitations
    of liability ("notices") contained within the Program from any copy of
    the Program which they Distribute, provided that Contributors may add
    their own appropriate notices.

    4. COMMERCIAL DISTRIBUTION

    Commercial distributors of software may accept certain responsibilities
    with respect to end users, business partners and the like. While this
    license is intended to facilitate the commercial use of the Program,
    the Contributor who includes the Program in a commercial product
    offering should do so in a manner which does not create potential
    liability for other Contributors. Therefore, if a Contributor includes
    the Program in a commercial product offering, such Contributor
    ("Commercial Contributor") hereby agrees to defend and indemnify every
    other Contributor ("Indemnified Contributor") against any losses,
    damages and costs (collectively "Losses") arising from claims, lawsuits
    and other legal actions brought by a third party against the Indemnified
    Contributor to the extent caused by the acts or omissions of such
    Commercial Contributor in connection with its distribution of the Program
    in a commercial product offering. The obligations in this section do not
    apply to any claims or Losses relating to any actual or alleged
    intellectual property infringement. In order to qualify, an Indemnified
    Contributor must: a) promptly notify the Commercial Contributor in
    writing of such claim, and b) allow the Commercial Contributor to control,
    and cooperate with the Commercial Contributor in, the defense and any
    related settlement negotiations. The Indemnified Contributor may
    participate in any such claim at its own expense.

    For example, a Contributor might include the Program in a commercial
    product offering, Product X. That Contributor is then a Commercial
    Contributor. If that Commercial Contributor then makes performance
    claims, or offers warranties related to Product X, those performance
    claims and warranties are such Commercial Contributor's responsibility
    alone. Under this section, the Commercial Contributor would have to
    defend claims against the other Contributors related to those performance
    claims and warranties, and if a court requires any other Contributor to
    pay any damages as a result, the Commercial Contributor must pay
    those damages.

    5. NO WARRANTY

    EXCEPT AS EXPRESSLY SET FORTH IN THIS AGREEMENT, AND TO THE EXTENT
    PERMITTED BY APPLICABLE LAW, THE PROGRAM IS PROVIDED ON AN "AS IS"
    BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, EITHER EXPRESS OR
    IMPLIED INCLUDING, WITHOUT LIMITATION, ANY WARRANTIES OR CONDITIONS OF
    TITLE, NON-INFRINGEMENT, MERCHANTABILITY OR FITNESS FOR A PARTICULAR
    PURPOSE. Each Recipient is solely responsible for determining the
    appropriateness of using and distributing the Program and assumes all
    risks associated with its exercise of rights under this Agreement,
    including but not limited to the risks and costs of program errors,
    compliance with applicable laws, damage to or loss of data, programs
    or equipment, and unavailability or interruption of operations.

    6. DISCLAIMER OF LIABILITY

    EXCEPT AS EXPRESSLY SET FORTH IN THIS AGREEMENT, AND TO THE EXTENT
    PERMITTED BY APPLICABLE LAW, NEITHER RECIPIENT NOR ANY CONTRIBUTORS
    SHALL HAVE ANY LIABILITY FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
    EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING WITHOUT LIMITATION LOST
    PROFITS), HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
    CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
    ARISING IN ANY WAY OUT OF THE USE OR DISTRIBUTION OF THE PROGRAM OR THE
    EXERCISE OF ANY RIGHTS GRANTED HEREUNDER, EVEN IF ADVISED OF THE
    POSSIBILITY OF SUCH DAMAGES.

    7. GENERAL

    If any provision of this Agreement is invalid or unenforceable under
    applicable law, it shall not affect the validity or enforceability of
    the remainder of the terms of this Agreement, and without further
    action by the parties hereto, such provision shall be reformed to the
    minimum extent necessary to make such provision valid and enforceable.

    If Recipient institutes patent litigation against any entity
    (including a cross-claim or counterclaim in a lawsuit) alleging that the
    Program itself (excluding combinations of the Program with other software
    or hardware) infringes such Recipient's patent(s), then such Recipient's
    rights granted under Section 2(b) shall terminate as of the date such
    litigation is filed.

    All Recipient's rights under this Agreement shall terminate if it
    fails to comply with any of the material terms or conditions of this
    Agreement and does not cure such failure in a reasonable period of
    time after becoming aware of such noncompliance. If all Recipient's
    rights under this Agreement terminate, Recipient agrees to cease use
    and distribution of the Program as soon as reasonably practicable.
    However, Recipient's obligations under this Agreement and any licenses
    granted by Recipient relating to the Program shall continue and survive.

    Everyone is permitted to copy and distribute copies of this Agreement,
    but in order to avoid inconsistency the Agreement is copyrighted and
    may only be modified in the following manner. The Agreement Steward
    reserves the right to publish new versions (including revisions) of
    this Agreement from time to time. No one other than the Agreement
    Steward has the right to modify this Agreement. The Eclipse Foundation
    is the initial Agreement Steward. The Eclipse Foundation may assign the
    responsibility to serve as the Agreement Steward to a suitable separate
    entity. Each new version of the Agreement will be given a distinguishing
    version number. The Program (including Contributions) may always be
    Distributed subject to the version of the Agreement under which it was
    received. In addition, after a new version of the Agreement is published,
    Contributor may elect to Distribute the Program (including its
    Contributions) under the new version.

    Except as expressly stated in Sections 2(a) and 2(b) above, Recipient
    receives no rights or licenses to the intellectual property of any
    Contributor under this Agreement, whether expressly, by implication,
    estoppel or otherwise. All rights in the Program not expressly granted
    under this Agreement are reserved. Nothing in this Agreement is intended
    to be enforceable by any entity that is not a Contributor or Recipient.
    No third-party beneficiary rights are created under this Agreement.

    Exhibit A - Form of Secondary Licenses Notice

    "This Source Code may also be made available under the following
    Secondary Licenses when the conditions for such availability set forth
    in the Eclipse Public License, v. 2.0 are satisfied: {name license(s),
    version(s), and exceptions or additional permissions here}."

      Simply including a copy of this Agreement, including this Exhibit A
      is not sufficient to license the Source Code under Secondary Licenses.

      If it is not possible or desirable to put the notice in a particular
      file, then You may include the notice in a location (such as a LICENSE
      file in a relevant directory) where a recipient would be likely to
      look for such a notice.

      You may add additional accurate notices of copyright ownership.

---

## The GNU General Public License (GPL) Version 2, June 1991

    Copyright (C) 1989, 1991 Free Software Foundation, Inc.
    51 Franklin Street, Fifth Floor
    Boston, MA 02110-1335
    USA

    Everyone is permitted to copy and distribute verbatim copies
    of this license document, but changing it is not allowed.

    Preamble

    The licenses for most software are designed to take away your freedom to
    share and change it. By contrast, the GNU General Public License is
    intended to guarantee your freedom to share and change free software--to
    make sure the software is free for all its users. This General Public
    License applies to most of the Free Software Foundation's software and
    to any other program whose authors commit to using it. (Some other Free
    Software Foundation software is covered by the GNU Library General
    Public License instead.) You can apply it to your programs, too.

    When we speak of free software, we are referring to freedom, not price.
    Our General Public Licenses are designed to make sure that you have the
    freedom to distribute copies of free software (and charge for this
    service if you wish), that you receive source code or can get it if you
    want it, that you can change the software or use pieces of it in new
    free programs; and that you know you can do these things.

    To protect your rights, we need to make restrictions that forbid anyone
    to deny you these rights or to ask you to surrender the rights. These
    restrictions translate to certain responsibilities for you if you
    distribute copies of the software, or if you modify it.

    For example, if you distribute copies of such a program, whether gratis
    or for a fee, you must give the recipients all the rights that you have.
    You must make sure that they, too, receive or can get the source code.
    And you must show them these terms so they know their rights.

    We protect your rights with two steps: (1) copyright the software, and
    (2) offer you this license which gives you legal permission to copy,
    distribute and/or modify the software.

    Also, for each author's protection and ours, we want to make certain
    that everyone understands that there is no warranty for this free
    software. If the software is modified by someone else and passed on, we
    want its recipients to know that what they have is not the original, so
    that any problems introduced by others will not reflect on the original
    authors' reputations.

    Finally, any free program is threatened constantly by software patents.
    We wish to avoid the danger that redistributors of a free program will
    individually obtain patent licenses, in effect making the program
    proprietary. To prevent this, we have made it clear that any patent must
    be licensed for everyone's free use or not licensed at all.

    The precise terms and conditions for copying, distribution and
    modification follow.

    TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION

    0. This License applies to any program or other work which contains a
    notice placed by the copyright holder saying it may be distributed under
    the terms of this General Public License. The "Program", below, refers
    to any such program or work, and a "work based on the Program" means
    either the Program or any derivative work under copyright law: that is
    to say, a work containing the Program or a portion of it, either
    verbatim or with modifications and/or translated into another language.
    (Hereinafter, translation is included without limitation in the term
    "modification".) Each licensee is addressed as "you".

    Activities other than copying, distribution and modification are not
    covered by this License; they are outside its scope. The act of running
    the Program is not restricted, and the output from the Program is
    covered only if its contents constitute a work based on the Program
    (independent of having been made by running the Program). Whether that
    is true depends on what the Program does.

    1. You may copy and distribute verbatim copies of the Program's source
    code as you receive it, in any medium, provided that you conspicuously
    and appropriately publish on each copy an appropriate copyright notice
    and disclaimer of warranty; keep intact all the notices that refer to
    this License and to the absence of any warranty; and give any other
    recipients of the Program a copy of this License along with the Program.

    You may charge a fee for the physical act of transferring a copy, and
    you may at your option offer warranty protection in exchange for a fee.

    2. You may modify your copy or copies of the Program or any portion of
    it, thus forming a work based on the Program, and copy and distribute
    such modifications or work under the terms of Section 1 above, provided
    that you also meet all of these conditions:

        a) You must cause the modified files to carry prominent notices
        stating that you changed the files and the date of any change.

        b) You must cause any work that you distribute or publish, that in
        whole or in part contains or is derived from the Program or any part
        thereof, to be licensed as a whole at no charge to all third parties
        under the terms of this License.

        c) If the modified program normally reads commands interactively
        when run, you must cause it, when started running for such
        interactive use in the most ordinary way, to print or display an
        announcement including an appropriate copyright notice and a notice
        that there is no warranty (or else, saying that you provide a
        warranty) and that users may redistribute the program under these
        conditions, and telling the user how to view a copy of this License.
        (Exception: if the Program itself is interactive but does not
        normally print such an announcement, your work based on the Program
        is not required to print an announcement.)

    These requirements apply to the modified work as a whole. If
    identifiable sections of that work are not derived from the Program, and
    can be reasonably considered independent and separate works in
    themselves, then this License, and its terms, do not apply to those
    sections when you distribute them as separate works. But when you
    distribute the same sections as part of a whole which is a work based on
    the Program, the distribution of the whole must be on the terms of this
    License, whose permissions for other licensees extend to the entire
    whole, and thus to each and every part regardless of who wrote it.

    Thus, it is not the intent of this section to claim rights or contest
    your rights to work written entirely by you; rather, the intent is to
    exercise the right to control the distribution of derivative or
    collective works based on the Program.

    In addition, mere aggregation of another work not based on the Program
    with the Program (or with a work based on the Program) on a volume of a
    storage or distribution medium does not bring the other work under the
    scope of this License.

    3. You may copy and distribute the Program (or a work based on it,
    under Section 2) in object code or executable form under the terms of
    Sections 1 and 2 above provided that you also do one of the following:

        a) Accompany it with the complete corresponding machine-readable
        source code, which must be distributed under the terms of Sections 1
        and 2 above on a medium customarily used for software interchange; or,

        b) Accompany it with a written offer, valid for at least three
        years, to give any third party, for a charge no more than your cost
        of physically performing source distribution, a complete
        machine-readable copy of the corresponding source code, to be
        distributed under the terms of Sections 1 and 2 above on a medium
        customarily used for software interchange; or,

        c) Accompany it with the information you received as to the offer to
        distribute corresponding source code. (This alternative is allowed
        only for noncommercial distribution and only if you received the
        program in object code or executable form with such an offer, in
        accord with Subsection b above.)

    The source code for a work means the preferred form of the work for
    making modifications to it. For an executable work, complete source code
    means all the source code for all modules it contains, plus any
    associated interface definition files, plus the scripts used to control
    compilation and installation of the executable. However, as a special
    exception, the source code distributed need not include anything that is
    normally distributed (in either source or binary form) with the major
    components (compiler, kernel, and so on) of the operating system on
    which the executable runs, unless that component itself accompanies the
    executable.

    If distribution of executable or object code is made by offering access
    to copy from a designated place, then offering equivalent access to copy
    the source code from the same place counts as distribution of the source
    code, even though third parties are not compelled to copy the source
    along with the object code.

    4. You may not copy, modify, sublicense, or distribute the Program
    except as expressly provided under this License. Any attempt otherwise
    to copy, modify, sublicense or distribute the Program is void, and will
    automatically terminate your rights under this License. However, parties
    who have received copies, or rights, from you under this License will
    not have their licenses terminated so long as such parties remain in
    full compliance.

    5. You are not required to accept this License, since you have not
    signed it. However, nothing else grants you permission to modify or
    distribute the Program or its derivative works. These actions are
    prohibited by law if you do not accept this License. Therefore, by
    modifying or distributing the Program (or any work based on the
    Program), you indicate your acceptance of this License to do so, and all
    its terms and conditions for copying, distributing or modifying the
    Program or works based on it.

    6. Each time you redistribute the Program (or any work based on the
    Program), the recipient automatically receives a license from the
    original licensor to copy, distribute or modify the Program subject to
    these terms and conditions. You may not impose any further restrictions
    on the recipients' exercise of the rights granted herein. You are not
    responsible for enforcing compliance by third parties to this License.

    7. If, as a consequence of a court judgment or allegation of patent
    infringement or for any other reason (not limited to patent issues),
    conditions are imposed on you (whether by court order, agreement or
    otherwise) that contradict the conditions of this License, they do not
    excuse you from the conditions of this License. If you cannot distribute
    so as to satisfy simultaneously your obligations under this License and
    any other pertinent obligations, then as a consequence you may not
    distribute the Program at all. For example, if a patent license would
    not permit royalty-free redistribution of the Program by all those who
    receive copies directly or indirectly through you, then the only way you
    could satisfy both it and this License would be to refrain entirely from
    distribution of the Program.

    If any portion of this section is held invalid or unenforceable under
    any particular circumstance, the balance of the section is intended to
    apply and the section as a whole is intended to apply in other
    circumstances.

    It is not the purpose of this section to induce you to infringe any
    patents or other property right claims or to contest validity of any
    such claims; this section has the sole purpose of protecting the
    integrity of the free software distribution system, which is implemented
    by public license practices. Many people have made generous
    contributions to the wide range of software distributed through that
    system in reliance on consistent application of that system; it is up to
    the author/donor to decide if he or she is willing to distribute
    software through any other system and a licensee cannot impose that choice.

    This section is intended to make thoroughly clear what is believed to be
    a consequence of the rest of this License.

    8. If the distribution and/or use of the Program is restricted in
    certain countries either by patents or by copyrighted interfaces, the
    original copyright holder who places the Program under this License may
    add an explicit geographical distribution limitation excluding those
    countries, so that distribution is permitted only in or among countries
    not thus excluded. In such case, this License incorporates the
    limitation as if written in the body of this License.

    9. The Free Software Foundation may publish revised and/or new
    versions of the General Public License from time to time. Such new
    versions will be similar in spirit to the present version, but may
    differ in detail to address new problems or concerns.

    Each version is given a distinguishing version number. If the Program
    specifies a version number of this License which applies to it and "any
    later version", you have the option of following the terms and
    conditions either of that version or of any later version published by
    the Free Software Foundation. If the Program does not specify a version
    number of this License, you may choose any version ever published by the
    Free Software Foundation.

    10. If you wish to incorporate parts of the Program into other free
    programs whose distribution conditions are different, write to the
    author to ask for permission. For software which is copyrighted by the
    Free Software Foundation, write to the Free Software Foundation; we
    sometimes make exceptions for this. Our decision will be guided by the
    two goals of preserving the free status of all derivatives of our free
    software and of promoting the sharing and reuse of software generally.

    NO WARRANTY

    11. BECAUSE THE PROGRAM IS LICENSED FREE OF CHARGE, THERE IS NO
    WARRANTY FOR THE PROGRAM, TO THE EXTENT PERMITTED BY APPLICABLE LAW.
    EXCEPT WHEN OTHERWISE STATED IN WRITING THE COPYRIGHT HOLDERS AND/OR
    OTHER PARTIES PROVIDE THE PROGRAM "AS IS" WITHOUT WARRANTY OF ANY KIND,
    EITHER EXPRESSED OR IMPLIED, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
    WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE. THE
    ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE PROGRAM IS WITH
    YOU. SHOULD THE PROGRAM PROVE DEFECTIVE, YOU ASSUME THE COST OF ALL
    NECESSARY SERVICING, REPAIR OR CORRECTION.

    12. IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN
    WRITING WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MAY MODIFY
    AND/OR REDISTRIBUTE THE PROGRAM AS PERMITTED ABOVE, BE LIABLE TO YOU FOR
    DAMAGES, INCLUDING ANY GENERAL, SPECIAL, INCIDENTAL OR CONSEQUENTIAL
    DAMAGES ARISING OUT OF THE USE OR INABILITY TO USE THE PROGRAM
    (INCLUDING BUT NOT LIMITED TO LOSS OF DATA OR DATA BEING RENDERED
    INACCURATE OR LOSSES SUSTAINED BY YOU OR THIRD PARTIES OR A FAILURE OF
    THE PROGRAM TO OPERATE WITH ANY OTHER PROGRAMS), EVEN IF SUCH HOLDER OR
    OTHER PARTY HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.

    END OF TERMS AND CONDITIONS

    How to Apply These Terms to Your New Programs

    If you develop a new program, and you want it to be of the greatest
    possible use to the public, the best way to achieve this is to make it
    free software which everyone can redistribute and change under these terms.

    To do so, attach the following notices to the program. It is safest to
    attach them to the start of each source file to most effectively convey
    the exclusion of warranty; and each file should have at least the
    "copyright" line and a pointer to where the full notice is found.

        One line to give the program's name and a brief idea of what it does.
        Copyright (C) <year> <name of author>

        This program is free software; you can redistribute it and/or modify
        it under the terms of the GNU General Public License as published by
        the Free Software Foundation; either version 2 of the License, or
        (at your option) any later version.

        This program is distributed in the hope that it will be useful, but
        WITHOUT ANY WARRANTY; without even the implied warranty of
        MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
        General Public License for more details.

        You should have received a copy of the GNU General Public License
        along with this program; if not, write to the Free Software
        Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1335 USA

    Also add information on how to contact you by electronic and paper mail.

    If the program is interactive, make it output a short notice like this
    when it starts in an interactive mode:

        Gnomovision version 69, Copyright (C) year name of author
        Gnomovision comes with ABSOLUTELY NO WARRANTY; for details type
        `show w'. This is free software, and you are welcome to redistribute
        it under certain conditions; type `show c' for details.

    The hypothetical commands `show w' and `show c' should show the
    appropriate parts of the General Public License. Of course, the commands
    you use may be called something other than `show w' and `show c'; they
    could even be mouse-clicks or menu items--whatever suits your program.

    You should also get your employer (if you work as a programmer) or your
    school, if any, to sign a "copyright disclaimer" for the program, if
    necessary. Here is a sample; alter the names:

        Yoyodyne, Inc., hereby disclaims all copyright interest in the
        program `Gnomovision' (which makes passes at compilers) written by
        James Hacker.

        signature of Ty Coon, 1 April 1989
        Ty Coon, President of Vice

    This General Public License does not permit incorporating your program
    into proprietary programs. If your program is a subroutine library, you
    may consider it more useful to permit linking proprietary applications
    with the library. If this is what you want to do, use the GNU Library
    General Public License instead of this License.

---

## CLASSPATH EXCEPTION

    Linking this library statically or dynamically with other modules is
    making a combined work based on this library.  Thus, the terms and
    conditions of the GNU General Public License version 2 cover the whole
    combination.

    As a special exception, the copyright holders of this library give you
    permission to link this library with independent modules to produce an
    executable, regardless of the license terms of these independent
    modules, and to copy and distribute the resulting executable under
    terms of your choice, provided that you also meet, for each linked
    independent module, the terms and conditions of the license of that
    module.  An independent module is a module which is not derived from or
    based on this library.  If you modify this library, you may extend this
    exception to your version of the library, but you are not obligated to
    do so.  If you do not wish to do so, delete this exception statement
    from your version.
````

### Aviso 56

- `jakarta.mail-2.0.5.jar` — META-INF/NOTICE.md

````text
# Notices for Jakarta Mail

This content is produced and maintained by the Jakarta Mail project.

* Project home: https://projects.eclipse.org/projects/ee4j.mail

## Trademarks

Jakarta Mail is a trademark of the Eclipse Foundation.

## Copyright

All content is the property of the respective authors or their employers. For
more information regarding authorship of content, please consult the listed
source code repository logs.

## Declared Project Licenses

This program and the accompanying materials are made available under the terms
of the Eclipse Public License v. 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0. This Source Code may also be made
available under the following Secondary Licenses when the conditions for such
availability set forth in the Eclipse Public License v. 2.0 are satisfied: GNU
General Public License, version 2 with the GNU Classpath Exception which is
available at https://www.gnu.org/software/classpath/license.html.

SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0

## Source Code

The project maintains the following source code repositories:

* https://github.com/eclipse-ee4j/mail

## Third-party Content

This project leverages the following third party content.

None

## Cryptography

Content may contain encryption software. The country in which you are currently
may have restrictions on the import, possession, and use, and/or re-export to
another country, of encryption software. BEFORE using any encryption software,
please check the country's laws, regulations and policies concerning the import,
possession, or use, and re-export of encryption software, to see if this is
permitted.
````

### Aviso 57

- `flyway-core-10.20.1.jar` — META-INF/LICENSE.txt

````text
Copyright (C) Red Gate Software Ltd 2010-2024

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
````

### Aviso 58

- `jaxb-core-4.0.6.jar` — META-INF/LICENSE.md
- `jaxb-runtime-4.0.6.jar` — META-INF/LICENSE.md
- `txw2-4.0.6.jar` — META-INF/LICENSE.md

````text
Copyright (c) 2018 Oracle and/or its affiliates. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

  - Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.

  - Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.

  - Neither the name of the Eclipse Foundation, Inc. nor the names of its
    contributors may be used to endorse or promote products derived
    from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
````

### Aviso 59

- `jaxb-core-4.0.6.jar` — META-INF/NOTICE.md
- `jaxb-runtime-4.0.6.jar` — META-INF/NOTICE.md
- `txw2-4.0.6.jar` — META-INF/NOTICE.md

````text
# Notices for Eclipse Implementation of JAXB

This content is produced and maintained by the Eclipse Implementation of JAXB
project.

* Project home: https://projects.eclipse.org/projects/ee4j.jaxb-impl

## Trademarks

Eclipse Implementation of JAXB is a trademark of the Eclipse Foundation.

## Copyright

All content is the property of the respective authors or their employers. For
more information regarding authorship of content, please consult the listed
source code repository logs.

## Declared Project Licenses

This program and the accompanying materials are made available under the terms
of the Eclipse Distribution License v. 1.0 which is available at
http://www.eclipse.org/org/documents/edl-v10.php.

SPDX-License-Identifier: BSD-3-Clause

## Source Code

The project maintains the following source code repositories:

* https://github.com/eclipse-ee4j/jaxb-ri
* https://github.com/eclipse-ee4j/jaxb-istack-commons
* https://github.com/eclipse-ee4j/jaxb-dtd-parser
* https://github.com/eclipse-ee4j/jaxb-fi
* https://github.com/eclipse-ee4j/jaxb-stax-ex
* https://github.com/eclipse-ee4j/jax-rpc-ri

## Third-party Content

This project leverages the following third party content.

Apache Ant (1.10.2)

* License: Apache-2.0 AND W3C AND LicenseRef-Public-Domain

Apache Ant (1.10.2)

* License: Apache-2.0 AND W3C AND LicenseRef-Public-Domain

Apache Felix (1.2.0)

* License: Apache License, 2.0

args4j (2.33)

* License: MIT License

dom4j (1.6.1)

* License: Custom license based on Apache 1.1

file-management (3.0.0)

* License: Apache-2.0
* Project: https://maven.apache.org/shared/file-management/
* Source:
   https://svn.apache.org/viewvc/maven/shared/tags/file-management-3.0.0/

JUnit (4.12)

* License: Eclipse Public License

JUnit (4.12)

* License: Eclipse Public License

maven-compat (3.5.2)

* License: Apache-2.0
* Project: https://maven.apache.org/ref/3.5.2/maven-compat/
* Source:
   https://mvnrepository.com/artifact/org.apache.maven/maven-compat/3.5.2

maven-core (3.5.2)

* License: Apache-2.0
* Project: https://maven.apache.org/ref/3.5.2/maven-core/index.html
* Source: https://mvnrepository.com/artifact/org.apache.maven/maven-core/3.5.2

maven-plugin-annotations (3.5)

* License: Apache-2.0
* Project: https://maven.apache.org/plugin-tools/maven-plugin-annotations/
* Source:
   https://github.com/apache/maven-plugin-tools/tree/master/maven-plugin-annotations

maven-plugin-api (3.5.2)

* License: Apache-2.0

maven-resolver-api (1.1.1)

* License: Apache-2.0

maven-resolver-api (1.1.1)

* License: Apache-2.0

maven-resolver-connector-basic (1.1.1)

* License: Apache-2.0

maven-resolver-impl (1.1.1)

* License: Apache-2.0

maven-resolver-spi (1.1.1)

* License: Apache-2.0

maven-resolver-transport-file (1.1.1)

* License: Apache-2.0
* Project: https://maven.apache.org/resolver/maven-resolver-transport-file/
* Source:
   https://github.com/apache/maven-resolver/tree/master/maven-resolver-transport-file

maven-resolver-util (1.1.1)

* License: Apache-2.0

maven-settings (3.5.2)

* License: Apache-2.0
* Source:
   https://mvnrepository.com/artifact/org.apache.maven/maven-settings/3.5.2

OSGi Service Platform Core Companion Code (6.0)

* License: Apache License, 2.0

plexus-archiver (3.5)

* License: Apache-2.0
* Project: https://codehaus-plexus.github.io/plexus-archiver/
* Source: https://github.com/codehaus-plexus/plexus-archiver

plexus-io (3.0.0)

* License: Apache-2.0

plexus-utils (3.1.0)

* License: Apache- 2.0 or Apache- 1.1 or BSD or Public Domain or Indiana
   University Extreme! Lab Software License V1.1.1 (Apache 1.1 style)

relaxng-datatype (1.0)

* License: New BSD license

Sax (0.2)

* License: SAX-PD
* Project: http://www.megginson.com/downloads/SAX/
* Source: http://sourceforge.net/project/showfiles.php?group_id=29449

testng (6.14.2)

* License: Apache-2.0 AND (MIT OR GPL-1.0+)
* Project: https://testng.org/doc/index.html
* Source: https://github.com/cbeust/testng

wagon-http-lightweight (3.0.0)

* License: Pending
* Project: https://maven.apache.org/wagon/
* Source:
   https://mvnrepository.com/artifact/org.apache.maven.wagon/wagon-http-lightweight/3.0.0

xz for java (1.8)

* License: LicenseRef-Public-Domain

## Cryptography

Content may contain encryption software. The country in which you are currently
may have restrictions on the import, possession, and use, and/or re-export to
another country, of encryption software. BEFORE using any encryption software,
please check the country's laws, regulations and policies concerning the import,
possession, or use, and re-export of encryption software, to see if this is
permitted.
````

### Aviso 60

- `HdrHistogram-2.2.2.jar` — META-INF/LICENSE.txt

````text
The code in this repository code was Written by Gil Tene, Michael Barker,
and Matt Warren, and released to the public domain, as explained at
http://creativecommons.org/publicdomain/zero/1.0/

For users of this code who wish to consume it under the "BSD" license
rather than under the public domain or CC0 contribution text mentioned
above, the code found under this directory is *also* provided under the
following license (commonly referred to as the BSD 2-Clause License). This
license does not detract from the above stated release of the code into
the public domain, and simply represents an additional license granted by
the Author.

-----------------------------------------------------------------------------
** Beginning of "BSD 2-Clause License" text. **

 Copyright (c) 2012, 2013, 2014, 2015, 2016 Gil Tene
 Copyright (c) 2014 Michael Barker
 Copyright (c) 2014 Matt Warren
 All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions are met:

 1. Redistributions of source code must retain the above copyright notice,
    this list of conditions and the following disclaimer.

 2. Redistributions in binary form must reproduce the above copyright notice,
    this list of conditions and the following disclaimer in the documentation
    and/or other materials provided with the distribution.

 THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
 THE POSSIBILITY OF SUCH DAMAGE.
````

### Aviso 61

- `hibernate-core-6.6.39.Final.jar` — https://raw.githubusercontent.com/hibernate/hibernate-orm/6.6.39/lgpl.txt

````text
GNU LESSER GENERAL PUBLIC LICENSE
                       Version 2.1, February 1999

 Copyright (C) 1991, 1999 Free Software Foundation, Inc.
 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 Everyone is permitted to copy and distribute verbatim copies
 of this license document, but changing it is not allowed.

[This is the first released version of the Lesser GPL.  It also counts
 as the successor of the GNU Library Public License, version 2, hence
 the version number 2.1.]

                            Preamble

  The licenses for most software are designed to take away your
freedom to share and change it.  By contrast, the GNU General Public
Licenses are intended to guarantee your freedom to share and change
free software--to make sure the software is free for all its users.

  This license, the Lesser General Public License, applies to some
specially designated software packages--typically libraries--of the
Free Software Foundation and other authors who decide to use it.  You
can use it too, but we suggest you first think carefully about whether
this license or the ordinary General Public License is the better
strategy to use in any particular case, based on the explanations below.

  When we speak of free software, we are referring to freedom of use,
not price.  Our General Public Licenses are designed to make sure that
you have the freedom to distribute copies of free software (and charge
for this service if you wish); that you receive source code or can get
it if you want it; that you can change the software and use pieces of
it in new free programs; and that you are informed that you can do
these things.

  To protect your rights, we need to make restrictions that forbid
distributors to deny you these rights or to ask you to surrender these
rights.  These restrictions translate to certain responsibilities for
you if you distribute copies of the library or if you modify it.

  For example, if you distribute copies of the library, whether gratis
or for a fee, you must give the recipients all the rights that we gave
you.  You must make sure that they, too, receive or can get the source
code.  If you link other code with the library, you must provide
complete object files to the recipients, so that they can relink them
with the library after making changes to the library and recompiling
it.  And you must show them these terms so they know their rights.

  We protect your rights with a two-step method: (1) we copyright the
library, and (2) we offer you this license, which gives you legal
permission to copy, distribute and/or modify the library.

  To protect each distributor, we want to make it very clear that
there is no warranty for the free library.  Also, if the library is
modified by someone else and passed on, the recipients should know
that what they have is not the original version, so that the original
author's reputation will not be affected by problems that might be
introduced by others.


  Finally, software patents pose a constant threat to the existence of
any free program.  We wish to make sure that a company cannot
effectively restrict the users of a free program by obtaining a
restrictive license from a patent holder.  Therefore, we insist that
any patent license obtained for a version of the library must be
consistent with the full freedom of use specified in this license.

  Most GNU software, including some libraries, is covered by the
ordinary GNU General Public License.  This license, the GNU Lesser
General Public License, applies to certain designated libraries, and
is quite different from the ordinary General Public License.  We use
this license for certain libraries in order to permit linking those
libraries into non-free programs.

  When a program is linked with a library, whether statically or using
a shared library, the combination of the two is legally speaking a
combined work, a derivative of the original library.  The ordinary
General Public License therefore permits such linking only if the
entire combination fits its criteria of freedom.  The Lesser General
Public License permits more lax criteria for linking other code with
the library.

  We call this license the "Lesser" General Public License because it
does Less to protect the user's freedom than the ordinary General
Public License.  It also provides other free software developers Less
of an advantage over competing non-free programs.  These disadvantages
are the reason we use the ordinary General Public License for many
libraries.  However, the Lesser license provides advantages in certain
special circumstances.

  For example, on rare occasions, there may be a special need to
encourage the widest possible use of a certain library, so that it becomes
a de-facto standard.  To achieve this, non-free programs must be
allowed to use the library.  A more frequent case is that a free
library does the same job as widely used non-free libraries.  In this
case, there is little to gain by limiting the free library to free
software only, so we use the Lesser General Public License.

  In other cases, permission to use a particular library in non-free
programs enables a greater number of people to use a large body of
free software.  For example, permission to use the GNU C Library in
non-free programs enables many more people to use the whole GNU
operating system, as well as its variant, the GNU/Linux operating
system.

  Although the Lesser General Public License is Less protective of the
users' freedom, it does ensure that the user of a program that is
linked with the Library has the freedom and the wherewithal to run
that program using a modified version of the Library.

  The precise terms and conditions for copying, distribution and
modification follow.  Pay close attention to the difference between a
"work based on the library" and a "work that uses the library".  The
former contains code derived from the library, whereas the latter must
be combined with the library in order to run.


                  GNU LESSER GENERAL PUBLIC LICENSE
   TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION

  0. This License Agreement applies to any software library or other
program which contains a notice placed by the copyright holder or
other authorized party saying it may be distributed under the terms of
this Lesser General Public License (also called "this License").
Each licensee is addressed as "you".

  A "library" means a collection of software functions and/or data
prepared so as to be conveniently linked with application programs
(which use some of those functions and data) to form executables.

  The "Library", below, refers to any such software library or work
which has been distributed under these terms.  A "work based on the
Library" means either the Library or any derivative work under
copyright law: that is to say, a work containing the Library or a
portion of it, either verbatim or with modifications and/or translated
straightforwardly into another language.  (Hereinafter, translation is
included without limitation in the term "modification".)

  "Source code" for a work means the preferred form of the work for
making modifications to it.  For a library, complete source code means
all the source code for all modules it contains, plus any associated
interface definition files, plus the scripts used to control compilation
and installation of the library.

  Activities other than copying, distribution and modification are not
covered by this License; they are outside its scope.  The act of
running a program using the Library is not restricted, and output from
such a program is covered only if its contents constitute a work based
on the Library (independent of the use of the Library in a tool for
writing it).  Whether that is true depends on what the Library does
and what the program that uses the Library does.

  1. You may copy and distribute verbatim copies of the Library's
complete source code as you receive it, in any medium, provided that
you conspicuously and appropriately publish on each copy an
appropriate copyright notice and disclaimer of warranty; keep intact
all the notices that refer to this License and to the absence of any
warranty; and distribute a copy of this License along with the
Library.

  You may charge a fee for the physical act of transferring a copy,
and you may at your option offer warranty protection in exchange for a
fee.


  2. You may modify your copy or copies of the Library or any portion
of it, thus forming a work based on the Library, and copy and
distribute such modifications or work under the terms of Section 1
above, provided that you also meet all of these conditions:

    a) The modified work must itself be a software library.

    b) You must cause the files modified to carry prominent notices
    stating that you changed the files and the date of any change.

    c) You must cause the whole of the work to be licensed at no
    charge to all third parties under the terms of this License.

    d) If a facility in the modified Library refers to a function or a
    table of data to be supplied by an application program that uses
    the facility, other than as an argument passed when the facility
    is invoked, then you must make a good faith effort to ensure that,
    in the event an application does not supply such function or
    table, the facility still operates, and performs whatever part of
    its purpose remains meaningful.

    (For example, a function in a library to compute square roots has
    a purpose that is entirely well-defined independent of the
    application.  Therefore, Subsection 2d requires that any
    application-supplied function or table used by this function must
    be optional: if the application does not supply it, the square
    root function must still compute square roots.)

These requirements apply to the modified work as a whole.  If
identifiable sections of that work are not derived from the Library,
and can be reasonably considered independent and separate works in
themselves, then this License, and its terms, do not apply to those
sections when you distribute them as separate works.  But when you
distribute the same sections as part of a whole which is a work based
on the Library, the distribution of the whole must be on the terms of
this License, whose permissions for other licensees extend to the
entire whole, and thus to each and every part regardless of who wrote
it.

Thus, it is not the intent of this section to claim rights or contest
your rights to work written entirely by you; rather, the intent is to
exercise the right to control the distribution of derivative or
collective works based on the Library.

In addition, mere aggregation of another work not based on the Library
with the Library (or with a work based on the Library) on a volume of
a storage or distribution medium does not bring the other work under
the scope of this License.

  3. You may opt to apply the terms of the ordinary GNU General Public
License instead of this License to a given copy of the Library.  To do
this, you must alter all the notices that refer to this License, so
that they refer to the ordinary GNU General Public License, version 2,
instead of to this License.  (If a newer version than version 2 of the
ordinary GNU General Public License has appeared, then you can specify
that version instead if you wish.)  Do not make any other change in
these notices.


  Once this change is made in a given copy, it is irreversible for
that copy, so the ordinary GNU General Public License applies to all
subsequent copies and derivative works made from that copy.

  This option is useful when you wish to copy part of the code of
the Library into a program that is not a library.

  4. You may copy and distribute the Library (or a portion or
derivative of it, under Section 2) in object code or executable form
under the terms of Sections 1 and 2 above provided that you accompany
it with the complete corresponding machine-readable source code, which
must be distributed under the terms of Sections 1 and 2 above on a
medium customarily used for software interchange.

  If distribution of object code is made by offering access to copy
from a designated place, then offering equivalent access to copy the
source code from the same place satisfies the requirement to
distribute the source code, even though third parties are not
compelled to copy the source along with the object code.

  5. A program that contains no derivative of any portion of the
Library, but is designed to work with the Library by being compiled or
linked with it, is called a "work that uses the Library".  Such a
work, in isolation, is not a derivative work of the Library, and
therefore falls outside the scope of this License.

  However, linking a "work that uses the Library" with the Library
creates an executable that is a derivative of the Library (because it
contains portions of the Library), rather than a "work that uses the
library".  The executable is therefore covered by this License.
Section 6 states terms for distribution of such executables.

  When a "work that uses the Library" uses material from a header file
that is part of the Library, the object code for the work may be a
derivative work of the Library even though the source code is not.
Whether this is true is especially significant if the work can be
linked without the Library, or if the work is itself a library.  The
threshold for this to be true is not precisely defined by law.

  If such an object file uses only numerical parameters, data
structure layouts and accessors, and small macros and small inline
functions (ten lines or less in length), then the use of the object
file is unrestricted, regardless of whether it is legally a derivative
work.  (Executables containing this object code plus portions of the
Library will still fall under Section 6.)

  Otherwise, if the work is a derivative of the Library, you may
distribute the object code for the work under the terms of Section 6.
Any executables containing that work also fall under Section 6,
whether or not they are linked directly with the Library itself.


  6. As an exception to the Sections above, you may also combine or
link a "work that uses the Library" with the Library to produce a
work containing portions of the Library, and distribute that work
under terms of your choice, provided that the terms permit
modification of the work for the customer's own use and reverse
engineering for debugging such modifications.

  You must give prominent notice with each copy of the work that the
Library is used in it and that the Library and its use are covered by
this License.  You must supply a copy of this License.  If the work
during execution displays copyright notices, you must include the
copyright notice for the Library among them, as well as a reference
directing the user to the copy of this License.  Also, you must do one
of these things:

    a) Accompany the work with the complete corresponding
    machine-readable source code for the Library including whatever
    changes were used in the work (which must be distributed under
    Sections 1 and 2 above); and, if the work is an executable linked
    with the Library, with the complete machine-readable "work that
    uses the Library", as object code and/or source code, so that the
    user can modify the Library and then relink to produce a modified
    executable containing the modified Library.  (It is understood
    that the user who changes the contents of definitions files in the
    Library will not necessarily be able to recompile the application
    to use the modified definitions.)

    b) Use a suitable shared library mechanism for linking with the
    Library.  A suitable mechanism is one that (1) uses at run time a
    copy of the library already present on the user's computer system,
    rather than copying library functions into the executable, and (2)
    will operate properly with a modified version of the library, if
    the user installs one, as long as the modified version is
    interface-compatible with the version that the work was made with.

    c) Accompany the work with a written offer, valid for at
    least three years, to give the same user the materials
    specified in Subsection 6a, above, for a charge no more
    than the cost of performing this distribution.

    d) If distribution of the work is made by offering access to copy
    from a designated place, offer equivalent access to copy the above
    specified materials from the same place.

    e) Verify that the user has already received a copy of these
    materials or that you have already sent this user a copy.

  For an executable, the required form of the "work that uses the
Library" must include any data and utility programs needed for
reproducing the executable from it.  However, as a special exception,
the materials to be distributed need not include anything that is
normally distributed (in either source or binary form) with the major
components (compiler, kernel, and so on) of the operating system on
which the executable runs, unless that component itself accompanies
the executable.

  It may happen that this requirement contradicts the license
restrictions of other proprietary libraries that do not normally
accompany the operating system.  Such a contradiction means you cannot
use both them and the Library together in an executable that you
distribute.


  7. You may place library facilities that are a work based on the
Library side-by-side in a single library together with other library
facilities not covered by this License, and distribute such a combined
library, provided that the separate distribution of the work based on
the Library and of the other library facilities is otherwise
permitted, and provided that you do these two things:

    a) Accompany the combined library with a copy of the same work
    based on the Library, uncombined with any other library
    facilities.  This must be distributed under the terms of the
    Sections above.

    b) Give prominent notice with the combined library of the fact
    that part of it is a work based on the Library, and explaining
    where to find the accompanying uncombined form of the same work.

  8. You may not copy, modify, sublicense, link with, or distribute
the Library except as expressly provided under this License.  Any
attempt otherwise to copy, modify, sublicense, link with, or
distribute the Library is void, and will automatically terminate your
rights under this License.  However, parties who have received copies,
or rights, from you under this License will not have their licenses
terminated so long as such parties remain in full compliance.

  9. You are not required to accept this License, since you have not
signed it.  However, nothing else grants you permission to modify or
distribute the Library or its derivative works.  These actions are
prohibited by law if you do not accept this License.  Therefore, by
modifying or distributing the Library (or any work based on the
Library), you indicate your acceptance of this License to do so, and
all its terms and conditions for copying, distributing or modifying
the Library or works based on it.

  10. Each time you redistribute the Library (or any work based on the
Library), the recipient automatically receives a license from the
original licensor to copy, distribute, link with or modify the Library
subject to these terms and conditions.  You may not impose any further
restrictions on the recipients' exercise of the rights granted herein.
You are not responsible for enforcing compliance by third parties with
this License.


  11. If, as a consequence of a court judgment or allegation of patent
infringement or for any other reason (not limited to patent issues),
conditions are imposed on you (whether by court order, agreement or
otherwise) that contradict the conditions of this License, they do not
excuse you from the conditions of this License.  If you cannot
distribute so as to satisfy simultaneously your obligations under this
License and any other pertinent obligations, then as a consequence you
may not distribute the Library at all.  For example, if a patent
license would not permit royalty-free redistribution of the Library by
all those who receive copies directly or indirectly through you, then
the only way you could satisfy both it and this License would be to
refrain entirely from distribution of the Library.

If any portion of this section is held invalid or unenforceable under any
particular circumstance, the balance of the section is intended to apply,
and the section as a whole is intended to apply in other circumstances.

It is not the purpose of this section to induce you to infringe any
patents or other property right claims or to contest validity of any
such claims; this section has the sole purpose of protecting the
integrity of the free software distribution system which is
implemented by public license practices.  Many people have made
generous contributions to the wide range of software distributed
through that system in reliance on consistent application of that
system; it is up to the author/donor to decide if he or she is willing
to distribute software through any other system and a licensee cannot
impose that choice.

This section is intended to make thoroughly clear what is believed to
be a consequence of the rest of this License.

  12. If the distribution and/or use of the Library is restricted in
certain countries either by patents or by copyrighted interfaces, the
original copyright holder who places the Library under this License may add
an explicit geographical distribution limitation excluding those countries,
so that distribution is permitted only in or among countries not thus
excluded.  In such case, this License incorporates the limitation as if
written in the body of this License.

  13. The Free Software Foundation may publish revised and/or new
versions of the Lesser General Public License from time to time.
Such new versions will be similar in spirit to the present version,
but may differ in detail to address new problems or concerns.

Each version is given a distinguishing version number.  If the Library
specifies a version number of this License which applies to it and
"any later version", you have the option of following the terms and
conditions either of that version or of any later version published by
the Free Software Foundation.  If the Library does not specify a
license version number, you may choose any version ever published by
the Free Software Foundation.


  14. If you wish to incorporate parts of the Library into other free
programs whose distribution conditions are incompatible with these,
write to the author to ask for permission.  For software which is
copyrighted by the Free Software Foundation, write to the Free
Software Foundation; we sometimes make exceptions for this.  Our
decision will be guided by the two goals of preserving the free status
of all derivatives of our free software and of promoting the sharing
and reuse of software generally.

                            NO WARRANTY

  15. BECAUSE THE LIBRARY IS LICENSED FREE OF CHARGE, THERE IS NO
WARRANTY FOR THE LIBRARY, TO THE EXTENT PERMITTED BY APPLICABLE LAW.
EXCEPT WHEN OTHERWISE STATED IN WRITING THE COPYRIGHT HOLDERS AND/OR
OTHER PARTIES PROVIDE THE LIBRARY "AS IS" WITHOUT WARRANTY OF ANY
KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE.  THE ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE
LIBRARY IS WITH YOU.  SHOULD THE LIBRARY PROVE DEFECTIVE, YOU ASSUME
THE COST OF ALL NECESSARY SERVICING, REPAIR OR CORRECTION.

  16. IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN
WRITING WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MAY MODIFY
AND/OR REDISTRIBUTE THE LIBRARY AS PERMITTED ABOVE, BE LIABLE TO YOU
FOR DAMAGES, INCLUDING ANY GENERAL, SPECIAL, INCIDENTAL OR
CONSEQUENTIAL DAMAGES ARISING OUT OF THE USE OR INABILITY TO USE THE
LIBRARY (INCLUDING BUT NOT LIMITED TO LOSS OF DATA OR DATA BEING
RENDERED INACCURATE OR LOSSES SUSTAINED BY YOU OR THIRD PARTIES OR A
FAILURE OF THE LIBRARY TO OPERATE WITH ANY OTHER SOFTWARE), EVEN IF
SUCH HOLDER OR OTHER PARTY HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH
DAMAGES.

                     END OF TERMS AND CONDITIONS


           How to Apply These Terms to Your New Libraries

  If you develop a new library, and you want it to be of the greatest
possible use to the public, we recommend making it free software that
everyone can redistribute and change.  You can do so by permitting
redistribution under these terms (or, alternatively, under the terms of the
ordinary General Public License).

  To apply these terms, attach the following notices to the library.  It is
safest to attach them to the start of each source file to most effectively
convey the exclusion of warranty; and each file should have at least the
"copyright" line and a pointer to where the full notice is found.

    <one line to give the library's name and a brief idea of what it does.>
    Copyright (C) <year>  <name of author>

    This library is free software; you can redistribute it and/or
    modify it under the terms of the GNU Lesser General Public
    License as published by the Free Software Foundation; either
    version 2.1 of the License, or (at your option) any later version.

    This library is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
    Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public
    License along with this library; if not, write to the Free Software
    Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA

Also add information on how to contact you by electronic and paper mail.

You should also get your employer (if you work as a programmer) or your
school, if any, to sign a "copyright disclaimer" for the library, if
necessary.  Here is a sample; alter the names:

  Yoyodyne, Inc., hereby disclaims all copyright interest in the
  library `Frob' (a library for tweaking knobs) written by James Random Hacker.

  <signature of Ty Coon>, 1 April 1990
  Ty Coon, President of Vice

That's all there is to it!
````

### Aviso 62

- `asm-9.7.1.jar` — https://repo.maven.apache.org/maven2/org/ow2/asm/asm/9.7.1/asm-9.7.1-sources.jar (cabeçalho ClassReader.java)

````text
// ASM: a very small and fast Java bytecode manipulation framework
// Copyright (c) 2000-2011 INRIA, France Telecom
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions
// are met:
// 1. Redistributions of source code must retain the above copyright
//    notice, this list of conditions and the following disclaimer.
// 2. Redistributions in binary form must reproduce the above copyright
//    notice, this list of conditions and the following disclaimer in the
//    documentation and/or other materials provided with the distribution.
// 3. Neither the name of the copyright holders nor the names of its
//    contributors may be used to endorse or promote products derived from
//    this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
// LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
// CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
// SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
// INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
// CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
// ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
// THE POSSIBILITY OF SUCH DAMAGE.
````

### Aviso 63

- `postgresql-42.7.8.jar` — META-INF/LICENSE

````text
Copyright (c) 1997, PostgreSQL Global Development Group
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.

Additional License files can be found in the 'licenses' folder located in the same directory as the LICENSE file (i.e. this file)

- Software produced outside the ASF which is available under other licenses (not Apache-2.0)

BSD-2-Clause
* com.ongres.scram:scram-client:3.2
* com.ongres.scram:scram-common:3.2
* com.ongres.stringprep:saslprep:2.2
* com.ongres.stringprep:stringprep:2.2
````

### Aviso 64

- `postgresql-42.7.8.jar` — META-INF/licenses/com.ongres.scram/scram-client-3.2/META-INF/LICENSE
- `postgresql-42.7.8.jar` — META-INF/licenses/com.ongres.scram/scram-common-3.2/META-INF/LICENSE

````text
Copyright (c) 2017 OnGres, Inc.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
this list of conditions and the following disclaimer in the documentation and/or
other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
````

### Aviso 65

- `postgresql-42.7.8.jar` — META-INF/licenses/com.ongres.stringprep/saslprep-2.2/META-INF/LICENSE
- `postgresql-42.7.8.jar` — META-INF/licenses/com.ongres.stringprep/stringprep-2.2/META-INF/LICENSE

````text
Copyright (c) 2019 OnGres, Inc.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
this list of conditions and the following disclaimer in the documentation and/or
other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
````

### Aviso 66

- `reactive-streams-1.0.4.jar` — https://raw.githubusercontent.com/reactive-streams/reactive-streams-jvm/v1.0.4/LICENSE

````text
MIT No Attribution

Copyright 2014 Reactive Streams

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
````

### Aviso 67

- `jul-to-slf4j-2.0.17.jar` — META-INF/LICENSE.txt
- `slf4j-api-2.0.17.jar` — META-INF/LICENSE.txt

````text
Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland)
All rights reserved.

Permission is hereby granted, free  of charge, to any person obtaining
a  copy  of this  software  and  associated  documentation files  (the
"Software"), to  deal in  the Software without  restriction, including
without limitation  the rights to  use, copy, modify,  merge, publish,
distribute,  sublicense, and/or sell  copies of  the Software,  and to
permit persons to whom the Software  is furnished to do so, subject to
the following conditions:

The  above  copyright  notice  and  this permission  notice  shall  be
included in all copies or substantial portions of the Software.

THE  SOFTWARE IS  PROVIDED  "AS  IS", WITHOUT  WARRANTY  OF ANY  KIND,
EXPRESS OR  IMPLIED, INCLUDING  BUT NOT LIMITED  TO THE  WARRANTIES OF
MERCHANTABILITY,    FITNESS    FOR    A   PARTICULAR    PURPOSE    AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE,  ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
````

### Aviso 68

- `springdoc-openapi-starter-common-2.8.3.jar` — META-INF/COPYRIGHT
- `springdoc-openapi-starter-webflux-api-2.8.3.jar` — META-INF/COPYRIGHT
- `springdoc-openapi-starter-webflux-ui-2.8.3.jar` — META-INF/COPYRIGHT
- `springdoc-openapi-starter-webmvc-api-2.8.3.jar` — META-INF/COPYRIGHT
- `springdoc-openapi-starter-webmvc-ui-2.8.3.jar` — META-INF/COPYRIGHT

````text
Copyright 2019-2023 the original author or authors.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
````

### Aviso 69

- `spring-amqp-3.2.8.jar` — META-INF/notice.txt
- `spring-rabbit-3.2.8.jar` — META-INF/notice.txt

````text
Spring AMQP Framework 3.2.8
Copyright (c) 2010-2025 Pivotal, Inc.

This product is licensed to you under the Apache License, Version 2.0
(the "License"). You may not use this product except in compliance with
the License.

This product may include a number of subcomponents with separate
copyright notices and license terms. Your use of the source code for
these subcomponents is subject to the terms and conditions of the
subcomponent's license, as noted in the license.txt file.
````

### Aviso 70

- `spring-boot-actuator-autoconfigure-3.4.13.jar` — META-INF/NOTICE.txt
- `spring-boot-actuator-3.4.13.jar` — META-INF/NOTICE.txt
- `spring-boot-autoconfigure-3.4.13.jar` — META-INF/NOTICE.txt
- `spring-boot-jarmode-tools-3.4.13.jar` — META-INF/NOTICE.txt
- `spring-boot-3.4.13.jar` — META-INF/NOTICE.txt

````text
Spring Boot 3.4.13
Copyright (c) 2012-2025 VMware, Inc.

This product is licensed to you under the Apache License, Version 2.0
(the "License"). You may not use this product except in compliance with
the License.
````

### Aviso 71

- `spring-data-commons-3.4.13.jar` — license.txt
- `spring-data-jpa-3.4.13.jar` — license.txt

````text
Apache License
                           Version 2.0, January 2004
                        https://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

=======================================================================

To the extent any open source subcomponents are licensed under the EPL and/or other
similar licenses that require the source code and/or modifications to
source code to be made available (as would be noted above), you may obtain a
copy of the source code corresponding to the binaries for such open source
components and modifications thereto, if any, (the "Source Files"), by
downloading the Source Files from https://www.springsource.org/download,
or by sending a request, with your name and address to: VMware, Inc., 3401 Hillview
Avenue, Palo Alto, CA 94304, United States of America or email info@vmware.com.  All
such requests should clearly specify:  OPEN SOURCE FILES REQUEST, Attention General
Counsel.  VMware shall mail a copy of the Source Files to you on a CD or equivalent
physical medium.  This offer to obtain a copy of the Source Files is valid for three
years from the date you acquired this Software product.
````

### Aviso 72

- `spring-data-commons-3.4.13.jar` — notice.txt

````text
Spring Data Commons 3.4.13 (2024.1.13)
Copyright (c) [2010-2021] Pivotal Software, Inc.

This product is licensed to you under the Apache License, Version 2.0 (the "License").
You may not use this product except in compliance with the License.

This product may include a number of subcomponents with
separate copyright notices and license terms. Your use of the source
code for the these subcomponents is subject to the terms and
conditions of the subcomponent's license, as noted in the LICENSE file.
````

### Aviso 73

- `spring-data-jpa-3.4.13.jar` — notice.txt

````text
Spring Data JPA 2.7 M2 (2021.2.0)
Copyright (c) [2011-2019] Pivotal Software, Inc.

This product is licensed to you under the Apache License, Version 2.0 (the "License").
You may not use this product except in compliance with the License.

This product may include a number of subcomponents with
separate copyright notices and license terms. Your use of the source
code for the these subcomponents is subject to the terms and
conditions of the subcomponent's license, as noted in the LICENSE file.
````

### Aviso 74

- `spring-data-keyvalue-3.4.13.jar` — license.txt

````text
Apache License
                           Version 2.0, January 2004
                        https://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

=======================================================================

SPRING FRAMEWORK ${version} SUBCOMPONENTS:

Spring Framework ${version} includes a number of subcomponents
with separate copyright notices and license terms. The product that
includes this file does not necessarily use all the open source
subcomponents referred to below. Your use of the source
code for these subcomponents is subject to the terms and
conditions of the following licenses.


>>> ASM 4.0 (org.ow2.asm:asm:4.0, org.ow2.asm:asm-commons:4.0):

Copyright (c) 2000-2011 INRIA, France Telecom
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

1. Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holders nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
THE POSSIBILITY OF SUCH DAMAGE.

Copyright (c) 1999-2009, OW2 Consortium <https://www.ow2.org/>


>>> CGLIB 3.0 (cglib:cglib:3.0):

Per the LICENSE file in the CGLIB JAR distribution downloaded from
https://sourceforge.net/projects/cglib/files/cglib3/3.0/cglib-3.0.jar/download,
CGLIB 3.0 is licensed under the Apache License, version 2.0, the text of which
is included above.


=======================================================================

To the extent any open source subcomponents are licensed under the EPL and/or
other similar licenses that require the source code and/or modifications to
source code to be made available (as would be noted above), you may obtain a
copy of the source code corresponding to the binaries for such open source
components and modifications thereto, if any, (the "Source Files"), by
downloading the Source Files from https://www.springsource.org/download, or by
sending a request, with your name and address to:

    VMware, Inc., 3401 Hillview Avenue
    Palo Alto, CA 94304
    United States of America

or email info@vmware.com.  All such requests should clearly specify:

    OPEN SOURCE FILES REQUEST
    Attention General Counsel

VMware shall mail a copy of the Source Files to you on a CD or equivalent
physical medium. This offer to obtain a copy of the Source Files is valid for
three years from the date you acquired this Software product.
````

### Aviso 75

- `spring-data-keyvalue-3.4.13.jar` — notice.txt

````text
Spring Data KeyValue 3.4.13 (2024.1.13)
Copyright (c) 2015-2019 Pivotal Software, Inc.

This product is licensed to you under the Apache License, Version 2.0
(the "License"). You may not use this product except in compliance with
the License.

This product may include a number of subcomponents with separate
copyright notices and license terms. Your use of the source code for
these subcomponents is subject to the terms and conditions of the
subcomponent's license, as noted in the license.txt file.
````

### Aviso 76

- `spring-data-redis-3.4.13.jar` — notice.txt

````text
Spring Data Redis 3.4.13 (2024.1.13)
Copyright (c) [2010-2019] Pivotal Software, Inc.

This product is licensed to you under the Apache License, Version 2.0 (the "License").
You may not use this product except in compliance with the License.

This product may include a number of subcomponents with
separate copyright notices and license terms. Your use of the source
code for the these subcomponents is subject to the terms and
conditions of the subcomponent's license, as noted in the LICENSE file.
````

### Aviso 77

- `spring-modulith-api-1.3.2.jar` — META-INF/NOTICE

````text
Spring Modulith 1.3.2
Copyright (c) 2021-2024 Broadcom, Inc.

This product is licensed to you under the Apache License, Version 2.0
(the "License"). You may not use this product except in compliance with
the License.
````

### Aviso 78

- `spring-retry-2.0.12.jar` — META-INF/NOTICE.txt

````text
Copyright (c) 2006-2023 the original author or authors.

This product is licensed to you under the Apache License, Version 2.0
(the "License"). You may not use this product except in compliance with
the License.
````

### Aviso 79

- `spring-aop-6.2.15.jar` — META-INF/license.txt
- `spring-aspects-6.2.15.jar` — META-INF/license.txt
- `spring-beans-6.2.15.jar` — META-INF/license.txt
- `spring-context-support-6.2.15.jar` — META-INF/license.txt
- `spring-context-6.2.15.jar` — META-INF/license.txt
- `spring-core-6.2.15.jar` — META-INF/license.txt
- `spring-expression-6.2.15.jar` — META-INF/license.txt
- `spring-jcl-6.2.15.jar` — META-INF/license.txt
- `spring-jdbc-6.2.15.jar` — META-INF/license.txt
- `spring-messaging-6.2.15.jar` — META-INF/license.txt
- `spring-orm-6.2.15.jar` — META-INF/license.txt
- `spring-oxm-6.2.15.jar` — META-INF/license.txt
- `spring-tx-6.2.15.jar` — META-INF/license.txt
- `spring-web-6.2.15.jar` — META-INF/license.txt
- `spring-webflux-6.2.15.jar` — META-INF/license.txt
- `spring-webmvc-6.2.15.jar` — META-INF/license.txt

````text
Apache License
                           Version 2.0, January 2004
                        https://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

=======================================================================

SPRING FRAMEWORK 6.2.15 SUBCOMPONENTS:

Spring Framework 6.2.15 includes a number of subcomponents
with separate copyright notices and license terms. The product that
includes this file does not necessarily use all the open source
subcomponents referred to below. Your use of the source
code for these subcomponents is subject to the terms and
conditions of the following licenses.


>>> ASM 9.1 (org.ow2.asm:asm:9.1, org.ow2.asm:asm-commons:9.1):

Copyright (c) 2000-2011 INRIA, France Telecom
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

1. Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holders nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
THE POSSIBILITY OF SUCH DAMAGE.

Copyright (c) 1999-2009, OW2 Consortium <https://www.ow2.org/>


>>> CGLIB 3.3 (cglib:cglib:3.3):

Per the LICENSE file in the CGLIB JAR distribution downloaded from
https://github.com/cglib/cglib/releases/download/RELEASE_3_3_0/cglib-3.3.0.jar,
CGLIB 3.3 is licensed under the Apache License, version 2.0, the text of which
is included above.


>>> JavaPoet 1.13.0 (com.squareup:javapoet:1.13.0):

Per the LICENSE file in the JavaPoet JAR distribution downloaded from
https://github.com/square/javapoet/archive/refs/tags/javapoet-1.13.0.zip,
JavaPoet 1.13.0 is licensed under the Apache License, version 2.0, the text of
which is included above.


>>> Objenesis 3.4 (org.objenesis:objenesis:3.4):

Per the LICENSE file in the Objenesis ZIP distribution downloaded from
http://objenesis.org/download.html, Objenesis 3.4 is licensed under the
Apache License, version 2.0, the text of which is included above.

Per the NOTICE file in the Objenesis ZIP distribution downloaded from
http://objenesis.org/download.html and corresponding to section 4d of the
Apache License, Version 2.0, in this case for Objenesis:

Objenesis
Copyright 2006-2019 Joe Walnes, Henri Tremblay, Leonardo Mesquita


===============================================================================

To the extent any open source components are licensed under the EPL and/or
other similar licenses that require the source code and/or modifications to
source code to be made available (as would be noted above), you may obtain a
copy of the source code corresponding to the binaries for such open source
components and modifications thereto, if any, (the "Source Files"), by
downloading the Source Files from https://spring.io/projects, Pivotal's website
at https://network.pivotal.io/open-source, or by sending a request, with your
name and address to: Pivotal Software, Inc., 875 Howard Street, 5th floor, San
Francisco, CA 94103, Attention: General Counsel. All such requests should
clearly specify: OPEN SOURCE FILES REQUEST, Attention General Counsel. Pivotal
can mail a copy of the Source Files to you on a CD or equivalent physical
medium.

This offer to obtain a copy of the Source Files is valid for three years from
the date you acquired this Software product. Alternatively, the Source Files
may accompany the Software.
````

### Aviso 80

- `spring-aop-6.2.15.jar` — META-INF/notice.txt
- `spring-aspects-6.2.15.jar` — META-INF/notice.txt
- `spring-beans-6.2.15.jar` — META-INF/notice.txt
- `spring-context-support-6.2.15.jar` — META-INF/notice.txt
- `spring-context-6.2.15.jar` — META-INF/notice.txt
- `spring-core-6.2.15.jar` — META-INF/notice.txt
- `spring-expression-6.2.15.jar` — META-INF/notice.txt
- `spring-jcl-6.2.15.jar` — META-INF/notice.txt
- `spring-jdbc-6.2.15.jar` — META-INF/notice.txt
- `spring-messaging-6.2.15.jar` — META-INF/notice.txt
- `spring-orm-6.2.15.jar` — META-INF/notice.txt
- `spring-oxm-6.2.15.jar` — META-INF/notice.txt
- `spring-tx-6.2.15.jar` — META-INF/notice.txt
- `spring-web-6.2.15.jar` — META-INF/notice.txt
- `spring-webflux-6.2.15.jar` — META-INF/notice.txt
- `spring-webmvc-6.2.15.jar` — META-INF/notice.txt

````text
Spring Framework 6.2.15
Copyright (c) 2002-2025 Pivotal, Inc.

This product is licensed to you under the Apache License, Version 2.0
(the "License"). You may not use this product except in compliance with
the License.

This product may include a number of subcomponents with separate
copyright notices and license terms. Your use of the source code for
these subcomponents is subject to the terms and conditions of the
subcomponent's license, as noted in the license.txt file.
````

### Aviso 81

- `thymeleaf-spring6-3.1.3.RELEASE.jar` — META-INF/NOTICE.txt

````text
Copyright (c) 2011-2022, The THYMELEAF team (http://www.thymeleaf.org)

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
````

### Aviso 82

- `unbescape-1.1.6.RELEASE.jar` — META-INF/NOTICE.txt

````text
Copyright (c) 2014-2017, The UNBESCAPE team (http://www.unbescape.org)

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
````

### Aviso 83

- `swagger-ui-5.18.2.jar` — https://raw.githubusercontent.com/swagger-api/swagger-ui/v5.18.2/NOTICE

````text
swagger-ui
Copyright 2020-2021 SmartBear Software Inc.
````

### Aviso 84

- `webjars-locator-lite-1.0.1.jar` — https://raw.githubusercontent.com/webjars/webjars-locator-lite/webjars-locator-lite-1.0.1/LICENSE.md

````text
Copyright (c) 2013 James Ward

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
````
