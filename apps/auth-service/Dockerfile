# -------- STAGE 1: Build --------
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copia common-lib e os arquivos da aplicação
COPY common-lib ./common-lib
COPY apps/auth-service/pom.xml ./apps/auth-service/
COPY apps/auth-service/src ./apps/auth-service/src
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

# Compila a biblioteca comum primeiro se necessário e depois o serviço
RUN ./mvnw clean package -pl apps/auth-service -am -DskipTests

# -------- STAGE 2: Runtime --------
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /build/apps/auth-service/target/*.jar app.jar

USER appuser

EXPOSE 8081

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]