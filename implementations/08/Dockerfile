FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -B package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/quarkus-app/ /app/
USER 10001:10001
EXPOSE 8443
ENTRYPOINT ["java","-jar","/app/quarkus-run.jar"]
