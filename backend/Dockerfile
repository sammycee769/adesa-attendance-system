# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies first so rebuilds are faster
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
# Tests are skipped: they use Testcontainers, which needs Docker and won't work during a Render build
RUN mvn clean package -DskipTests -B

# ---- Run stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --no-create-home appuser
COPY --from=build /app/target/*.jar app.jar
USER appuser

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Dserver.port=${PORT:-8080} -jar app.jar"]