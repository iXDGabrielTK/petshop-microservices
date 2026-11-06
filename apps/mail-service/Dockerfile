# -------- STAGE 1: Build --------
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copia os arquivos POM para o Maven resolver a estrutura multi-módulos
COPY pom.xml .
COPY apps/common-lib/pom.xml ./apps/common-lib/
COPY apps/mail-service/pom.xml ./apps/mail-service/
COPY apps/auth-service/pom.xml ./apps/auth-service/
COPY apps/api-gateway/pom.xml ./apps/api-gateway/
COPY apps/inv-service/pom.xml ./apps/inv-service/

# Copia os códigos-fonte necessários
COPY apps/common-lib/src ./apps/common-lib/src
COPY apps/mail-service/src ./apps/mail-service/src

# Compila common-lib e mail-service
RUN mvn clean package -pl apps/mail-service -am -DskipTests

# -------- STAGE 2: Runtime --------
FROM eclipse-temurin:21-jre-alpine@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474

WORKDIR /app

COPY LICENSE /app/licenses/LICENSE
COPY THIRD-PARTY-NOTICES.md /app/licenses/THIRD-PARTY-NOTICES.md

# Segurança: não rodar como root
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /build/apps/mail-service/target/*.jar app.jar

USER appuser

EXPOSE 8082

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]