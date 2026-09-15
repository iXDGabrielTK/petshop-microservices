# -------- STAGE 1: Build --------
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

RUN ./mvnw dependency:go-offline -B

COPY common-lib ./common-lib
COPY src ./src

RUN ./mvnw clean package -DskipTests

# -------- STAGE 2: Runtime --------
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Segurança: não rodar como root
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /build/target/mail-service-1.0-SNAPSHOT.jar app.jar

USER appuser

EXPOSE 8082

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]