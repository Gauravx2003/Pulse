# Build Stage
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
COPY modules ./modules
RUN mvn package -DskipTests -B

# Run Stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/pulse-backend-1.0.0-SNAPSHOT-fat.jar app.jar
EXPOSE 8088
ENV PORT=8088
ENTRYPOINT ["java", "-jar", "app.jar"]
