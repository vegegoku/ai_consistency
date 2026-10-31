FROM maven:3.10.0-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/quarkus-app/ ./
USER 10001
EXPOSE 8443
ENTRYPOINT ["java","-jar","quarkus-run.jar"]
