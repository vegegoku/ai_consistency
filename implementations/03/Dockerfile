FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
RUN mvn -B package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/quarkus-app/ ./
USER 10001
EXPOSE 8080 8443
ENTRYPOINT ["java","-jar","quarkus-run.jar"]
