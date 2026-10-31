FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /project
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build --chown=1001:0 /project/target/quarkus-app/ /app/
USER 1001
EXPOSE 8443
ENTRYPOINT ["java","-jar","/app/quarkus-run.jar"]
