# Stage 1: build the jar (runs the tests too)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package

# Stage 2: a small image with only Java and the jar
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/relay.jar relay.jar
EXPOSE 9000
ENTRYPOINT ["java", "-jar", "relay.jar"]