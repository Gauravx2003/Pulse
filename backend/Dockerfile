# Build Stage (context: repository root, see docker-compose.yml)
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app/backend
COPY backend/pom.xml .
RUN mvn dependency:go-offline -B
COPY backend/src ./src
COPY backend/modules ./modules
COPY backend/calc ./calc
# extracted seed JSON, packaged as classpath seed/extracted by pom.xml
COPY seed/extracted /app/seed/extracted
RUN mvn package -DskipTests -B

# Run Stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/backend/target/pulse-backend-1.0.0-SNAPSHOT-fat.jar app.jar
EXPOSE 8088
ENV PORT=8088
ENTRYPOINT ["java", "-jar", "app.jar"]
