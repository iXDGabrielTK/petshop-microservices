# -------- STAGE 1: Build --------
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copia os arquivos POM para o Maven resolver a estrutura multi-módulos
COPY pom.xml .
COPY apps/common-lib/pom.xml ./apps/common-lib/
COPY apps/api-gateway/pom.xml ./apps/api-gateway/
COPY apps/auth-service/pom.xml ./apps/auth-service/
COPY apps/inv-service/pom.xml ./apps/inv-service/
COPY apps/mail-service/pom.xml ./apps/mail-service/

# Copia os códigos-fonte necessários
COPY apps/common-lib/src ./apps/common-lib/src
COPY apps/api-gateway/src ./apps/api-gateway/src

# Compila common-lib e api-gateway
RUN mvn clean package -pl apps/api-gateway -am -DskipTests

# -------- STAGE 2: Runtime --------
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Segurança: não rodar como root
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /build/apps/api-gateway/target/*.jar app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]