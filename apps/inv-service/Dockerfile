# Usa uma imagem leve do Java 21
FROM eclipse-temurin:21-jre-alpine

# Define a pasta de trabalho dentro do container
WORKDIR /app

# Copia o .jar que você gerou no build para dentro do container
# O nome do jar pode variar, o asterisco (*) ajuda a pegar qualquer versão
COPY target/*.jar app.jar

# Comando para rodar o app
ENTRYPOINT ["java", "-jar", "app.jar"]